package qupath.ext.mantis.mrxsconvert.temp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import static java.nio.file.StandardOpenOption.CREATE_NEW;
import static java.nio.file.StandardOpenOption.READ;
import static java.nio.file.StandardOpenOption.WRITE;

/**
 * A working directory for one conversion's intermediate Zarr (spec 05): {@code %TEMP%\mantis-mrxsconvert\<uuid>\}.
 * <p>
 * While open, the workspace holds an exclusive lock on its {@code owner.lock}. The OS releases that lock when the
 * owning process dies, however it dies, so {@link #cleanupOrphans()} can tell a live workspace from a leftover one
 * without trusting PIDs (D6).
 */
public final class TempWorkspace implements AutoCloseable {

	private static final Logger logger = LoggerFactory.getLogger(TempWorkspace.class);

	static final String LOCK_FILE = "owner.lock";
	/** A workspace with no owner lock is only removed once it is this old: it may be being created right now. */
	static final Duration UNLOCKED_ORPHAN_AGE = Duration.ofHours(24);
	private static final int DELETE_ATTEMPTS = 5;

	// File locks belong to the whole JVM, so this JVM's own workspaces are tracked here instead
	private static final Set<Path> openWorkspaces = ConcurrentHashMap.newKeySet();

	private final Path dir;
	private final FileChannel lockChannel;
	private boolean closed;

	private TempWorkspace(Path dir, FileChannel lockChannel) {
		this.dir = dir;
		this.lockChannel = lockChannel;
	}

	/** {@code <java.io.tmpdir>\mantis-mrxsconvert}; in QuPath that is {@code %TEMP%\mantis-mrxsconvert}. */
	public static Path defaultRoot() {
		return Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().resolve("mantis-mrxsconvert");
	}

	/** Creates a new workspace under {@link #defaultRoot()}. */
	public static TempWorkspace create() throws IOException {
		return create(defaultRoot());
	}

	public static TempWorkspace create(Path root) throws IOException {
		Files.createDirectories(root);
		Path dir = root.toAbsolutePath().normalize().resolve(UUID.randomUUID().toString());
		Files.createDirectory(dir);
		FileChannel channel = null;
		try {
			channel = FileChannel.open(dir.resolve(LOCK_FILE), CREATE_NEW, READ, WRITE);
			channel.lock();
			// Written only once locked: cleanup treats an empty lock file as "still being created"
			String owner = "pid=" + ProcessHandle.current().pid() + "\nstarted="
					+ ProcessHandle.current().info().startInstant().map(Instant::toString).orElse("unknown") + "\n";
			channel.write(ByteBuffer.wrap(owner.getBytes(StandardCharsets.UTF_8)));
			channel.force(false);
		} catch (IOException | RuntimeException e) {
			if (channel != null)
				channel.close();
			deleteTree(dir);
			throw e;
		}
		openWorkspaces.add(dir);
		return new TempWorkspace(dir, channel);
	}

	public Path dir() {
		return dir;
	}

	/** Releases the lock and deletes the workspace. Never throws: a failed delete is logged and left to cleanup. */
	@Override
	public synchronized void close() {
		if (closed)
			return;
		closed = true;
		try {
			lockChannel.close();
		} catch (IOException e) {
			logger.debug("Could not release {}", dir.resolve(LOCK_FILE), e);
		}
		openWorkspaces.remove(dir);
		if (!deleteWithRetries(dir))
			logger.warn("Could not delete temporary folder {}; it will be removed the next time QuPath starts", dir);
	}

	/**
	 * Deletes workspaces under {@link #defaultRoot()} left behind by QuPath instances that have exited or crashed.
	 * Workspaces of running instances are kept. Blocks for a while if files are held open, so call it off the
	 * JavaFX thread. Never throws.
	 *
	 * @return the number of workspaces deleted
	 */
	public static int cleanupOrphans() {
		return cleanupOrphans(defaultRoot());
	}

	static int cleanupOrphans(Path root) {
		List<Path> dirs;
		try (Stream<Path> entries = Files.list(root)) {
			dirs = entries.filter(Files::isDirectory).toList();
		} catch (NoSuchFileException e) {
			return 0;
		} catch (IOException e) {
			logger.warn("Could not list temporary folder {}", root, e);
			return 0;
		}
		int deleted = 0;
		for (Path dir : dirs) {
			try {
				if (cleanupIfOrphan(dir.toAbsolutePath().normalize()))
					deleted++;
			} catch (IOException | RuntimeException e) {
				logger.warn("Could not clean up temporary folder {}", dir, e);
			}
		}
		if (deleted > 0)
			logger.info("Removed {} leftover temporary folder(s) from {}", deleted, root);
		return deleted;
	}

	private static boolean cleanupIfOrphan(Path dir) throws IOException {
		if (openWorkspaces.contains(dir))
			return false;
		Path lockFile = dir.resolve(LOCK_FILE);
		if (!Files.exists(lockFile))
			return isOld(dir) && deleteOrWarn(dir);

		try (FileChannel channel = FileChannel.open(lockFile, READ, WRITE)) {
			FileLock lock = tryLock(channel);
			if (lock == null)
				return false;
			// Empty: the owner opened the file but hasn't locked it yet
			if (channel.size() == 0 && !isOld(dir)) {
				lock.release();
				return false;
			}
			// Delete everything but the lock file while holding the lock, so no live owner can be affected
			try (Stream<Path> children = Files.list(dir)) {
				for (Path child : children.filter(p -> !p.equals(lockFile)).toList()) {
					if (!deleteWithRetries(child)) {
						logger.warn("Could not delete leftover temporary folder {}; a file in it may be open", dir);
						return false;
					}
				}
			}
		}
		return deleteOrWarn(dir);
	}

	private static FileLock tryLock(FileChannel channel) throws IOException {
		try {
			return channel.tryLock();
		} catch (OverlappingFileLockException e) {
			// This JVM holds it
			return null;
		}
	}

	private static boolean isOld(Path dir) throws IOException {
		Instant modified = Files.getLastModifiedTime(dir).toInstant();
		return modified.plus(UNLOCKED_ORPHAN_AGE).isBefore(Instant.now());
	}

	private static boolean deleteOrWarn(Path dir) {
		if (deleteWithRetries(dir))
			return true;
		logger.warn("Could not delete leftover temporary folder {}; a file in it may be open", dir);
		return false;
	}

	/**
	 * Deletes a file or directory tree, retrying with backoff because virus scanners and the search indexer briefly
	 * hold freshly written files open on Windows.
	 *
	 * @return true if nothing is left
	 */
	public static boolean deleteWithRetries(Path path) {
		// Cleanup must still happen when a cancelled job's thread was interrupted
		boolean interrupted = Thread.interrupted();
		try {
			long delayMillis = 100;
			for (int attempt = 1; ; attempt++) {
				try {
					deleteTree(path);
					return true;
				} catch (IOException e) {
					if (attempt == DELETE_ATTEMPTS) {
						logger.debug("Could not delete {}", path, e);
						return false;
					}
				}
				try {
					Thread.sleep(delayMillis);
				} catch (InterruptedException e) {
					interrupted = true;
				}
				delayMillis *= 2;
			}
		} finally {
			if (interrupted)
				Thread.currentThread().interrupt();
		}
	}

	private static void deleteTree(Path path) throws IOException {
		if (!Files.exists(path))
			return;
		try (Stream<Path> walk = Files.walk(path)) {
			for (Path p : walk.sorted(Comparator.reverseOrder()).toList())
				Files.deleteIfExists(p);
		}
	}
}
