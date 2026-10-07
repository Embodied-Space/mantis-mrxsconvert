package qupath.ext.mantis.mrxsconvert.payload;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.CREATE_NEW;
import static java.nio.file.StandardOpenOption.READ;
import static java.nio.file.StandardOpenOption.WRITE;

/**
 * Extracts the bundled JRE and tool jars (spec 02) from the extension jar to
 * {@code %LOCALAPPDATA%\mantis-mrxsconvert\tools\<payload-id>\} and returns their paths.
 * <p>
 * Safe across QuPath instances. Each one holds a shared lock on {@code <id>.inuse} while it uses a payload.
 * Extraction runs under an exclusive lock on {@code <id>.lock}: files go to {@code <id>.extracting}, are checked
 * against the manifest's SHA-256s, get a {@code .complete} marker and are renamed into place in one step.
 * Payload dirs of other versions are deleted when no instance holds their {@code .inuse} lock.
 * The lock files themselves are left behind: deleting them could let two instances lock different files of one name.
 */
public final class ToolPayload {

	private static final Logger logger = LoggerFactory.getLogger(ToolPayload.class);

	static final String RESOURCE_ROOT = "/qupath/ext/mantis/mrxsconvert/payload/";
	static final String MARKER = ".complete";
	private static final String STAGING_SUFFIX = ".extracting";
	private static final String LOCK_SUFFIX = ".lock";
	private static final String IN_USE_SUFFIX = ".inuse";
	private static final Duration MOVE_RETRY = Duration.ofSeconds(60);
	private static final Pattern PAYLOAD_DIR = Pattern.compile("[0-9a-f]{16}(" + Pattern.quote(STAGING_SUFFIX) + ")?");

	/*
	 * File locks belong to the whole JVM: locking a file twice from one JVM throws instead of waiting.
	 * So all work runs while holding ToolPayload.class, and in-use locks are kept here, one per payload dir.
	 */
	private static final Map<Path, FileChannel> inUseLocks = new HashMap<>();
	private static ToolPayload defaultPayload;

	private final Path toolsDir;
	private final PayloadSource source;
	private ToolPaths paths;

	ToolPayload(Path toolsDir, PayloadSource source) {
		this.toolsDir = toolsDir.toAbsolutePath().normalize();
		this.source = source;
	}

	/**
	 * Returns the extracted tools, extracting them first if needed. The first call takes a few seconds;
	 * later calls return at once. Blocks, so never call it on the JavaFX thread.
	 *
	 * @throws IOException if the payload can't be extracted, e.g. the disk is full or the extension jar is damaged
	 */
	public static ToolPaths ensureExtracted() throws IOException {
		synchronized (ToolPayload.class) {
			if (defaultPayload == null)
				defaultPayload = new ToolPayload(defaultToolsDir(), PayloadSource.classpath(ToolPayload.class, RESOURCE_ROOT));
			return defaultPayload.extract();
		}
	}

	/** {@code %LOCALAPPDATA%\mantis-mrxsconvert\tools} (E4). */
	static Path defaultToolsDir() {
		String localAppData = System.getenv("LOCALAPPDATA");
		Path base = localAppData == null || localAppData.isBlank()
				? Path.of(System.getProperty("user.home"), "AppData", "Local")
				: Path.of(localAppData);
		return base.resolve("mantis-mrxsconvert").resolve("tools");
	}

	ToolPaths extract() throws IOException {
		synchronized (ToolPayload.class) {
			if (paths != null && isComplete(paths.root()))
				return paths;
			PayloadManifest manifest = PayloadManifest.read(source);
			Files.createDirectories(toolsDir);
			Path target = toolsDir.resolve(manifest.id());
			// Lock before checking the marker, so another instance's cleanup can't delete the dir under us
			holdInUseLock(target);
			if (!isComplete(target)) {
				try (FileChannel channel = FileChannel.open(sibling(target, LOCK_SUFFIX), CREATE, WRITE);
				     FileLock ignored = channel.lock()) {
					if (!isComplete(target))
						install(manifest, target);
				}
			}
			paths = ToolPaths.of(target);
			deleteOtherPayloads(manifest.id());
			return paths;
		}
	}

	private void install(PayloadManifest manifest, Path target) throws IOException {
		Path staging = sibling(target, STAGING_SUFFIX);
		logger.info("Extracting conversion tools to {}", target);
		deleteTree(staging);
		// A dir without the marker is incomplete or damaged
		deleteTree(target);
		try {
			Files.createDirectories(staging);
			for (PayloadManifest.Entry entry : manifest.files())
				extractFile(entry, staging);
			Files.writeString(staging.resolve(MARKER), manifest.id());
			moveIntoPlace(staging, target);
		} catch (IOException | RuntimeException e) {
			try {
				deleteTree(staging);
			} catch (IOException suppressed) {
				e.addSuppressed(suppressed);
			}
			throw e;
		}
	}

	private void extractFile(PayloadManifest.Entry entry, Path staging) throws IOException {
		Path dest = PayloadManifest.resolveInside(staging, entry.path());
		Files.createDirectories(dest.getParent());
		MessageDigest digest = sha256();
		long size;
		try (InputStream in = source.open(entry.path());
		     OutputStream out = new DigestOutputStream(Files.newOutputStream(dest, CREATE_NEW), digest)) {
			size = in.transferTo(out);
		}
		if (size != entry.size() || !HexFormat.of().formatHex(digest.digest()).equals(entry.sha256()))
			throw new IOException("The extension jar is damaged (" + entry.path() + " fails its checksum). Reinstall the extension.");
	}

	private static void holdInUseLock(Path target) throws IOException {
		Path lockFile = sibling(target, IN_USE_SUFFIX);
		if (inUseLocks.containsKey(lockFile))
			return;
		FileChannel channel = FileChannel.open(lockFile, CREATE, READ, WRITE);
		try {
			channel.lock(0, Long.MAX_VALUE, true);
		} catch (IOException | RuntimeException e) {
			channel.close();
			throw e;
		}
		inUseLocks.put(lockFile, channel);
	}

	/** For tests: lets go of every in-use lock, so their temp dirs can be deleted. */
	static void releaseInUseLocks() throws IOException {
		synchronized (ToolPayload.class) {
			for (FileChannel channel : inUseLocks.values())
				channel.close();
			inUseLocks.clear();
		}
	}

	/** Best effort: failures are logged and ignored. */
	private void deleteOtherPayloads(String currentId) {
		Set<String> ids = new TreeSet<>();
		try (Stream<Path> entries = Files.list(toolsDir)) {
			entries.filter(Files::isDirectory)
					.map(p -> p.getFileName().toString())
					.filter(name -> PAYLOAD_DIR.matcher(name).matches())
					.map(name -> name.substring(0, 16))
					.filter(id -> !id.equals(currentId))
					.forEach(ids::add);
		} catch (IOException e) {
			logger.debug("Could not list {}", toolsDir, e);
			return;
		}
		for (String id : ids) {
			try {
				deleteIfUnused(toolsDir.resolve(id));
			} catch (IOException | RuntimeException e) {
				logger.debug("Could not delete old conversion tools {}", id, e);
			}
		}
	}

	private static void deleteIfUnused(Path dir) throws IOException {
		try (FileChannel channel = FileChannel.open(sibling(dir, IN_USE_SUFFIX), CREATE, READ, WRITE)) {
			FileLock lock = tryLock(channel);
			if (lock == null) {
				logger.debug("Old conversion tools {} are in use; keeping them", dir);
				return;
			}
			try (lock) {
				logger.info("Deleting old conversion tools {}", dir);
				// Marker first, so a partly deleted dir is never taken as complete
				Files.deleteIfExists(dir.resolve(MARKER));
				deleteTree(dir);
				deleteTree(sibling(dir, STAGING_SUFFIX));
			}
		}
	}

	private static FileLock tryLock(FileChannel channel) throws IOException {
		try {
			return channel.tryLock();
		} catch (OverlappingFileLockException e) {
			// This JVM holds it
			return null;
		}
	}

	private static void moveIntoPlace(Path staging, Path target) throws IOException {
		long deadline = System.nanoTime() + MOVE_RETRY.toNanos();
		while (true) {
			try {
				Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE);
				return;
			} catch (AccessDeniedException e) {
				// Virus scanners hold freshly written files open while they scan them, which blocks renaming their dir.
				// Scanning ~385 MB of new jars on a slow lab PC can take a while.
				if (System.nanoTime() > deadline)
					throw e;
				try {
					Thread.sleep(250);
				} catch (InterruptedException ie) {
					Thread.currentThread().interrupt();
					throw new InterruptedIOException("Interrupted while installing conversion tools");
				}
			}
		}
	}

	private static void deleteTree(Path dir) throws IOException {
		if (!Files.exists(dir))
			return;
		try (Stream<Path> walk = Files.walk(dir)) {
			for (Path p : walk.sorted(Comparator.reverseOrder()).toList())
				Files.deleteIfExists(p);
		}
	}

	private static boolean isComplete(Path dir) {
		return Files.isRegularFile(dir.resolve(MARKER));
	}

	private static Path sibling(Path dir, String suffix) {
		return dir.resolveSibling(dir.getFileName() + suffix);
	}

	private static MessageDigest sha256() {
		try {
			return MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}
}
