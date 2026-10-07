package qupath.ext.mantis.mrxsconvert.runner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;

import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.READ;
import static java.nio.file.StandardOpenOption.WRITE;

/**
 * Claims one output for a conversion: an exclusive lock on a hidden {@code <output>.partial.lock} next to it, held
 * for the whole conversion. The OS drops the lock when its owner dies, so a {@code .partial} whose lock is free is a
 * leftover from a crash and may be deleted, while one being written by another QuPath is left alone. Works on network
 * shares too, where workspaces in each machine's temp folder can't be seen.
 */
final class OutputLock implements AutoCloseable {

	private static final Logger logger = LoggerFactory.getLogger(OutputLock.class);

	private final Path file;
	private final FileChannel channel;

	private OutputLock(Path file, FileChannel channel) {
		this.file = file;
		this.channel = channel;
	}

	static Path lockFile(ConversionJob job) {
		Path partial = job.partialOutput();
		return partial.resolveSibling(partial.getFileName() + ".lock");
	}

	/** @return the lock, or null if another conversion (in this or another process) holds it */
	static OutputLock tryAcquire(ConversionJob job) throws IOException {
		Path file = lockFile(job);
		FileChannel channel = FileChannel.open(file, CREATE, READ, WRITE);
		FileLock lock;
		try {
			lock = channel.tryLock();
		} catch (OverlappingFileLockException e) {
			// Held by this JVM
			lock = null;
		} catch (IOException | RuntimeException e) {
			channel.close();
			throw e;
		}
		if (lock == null) {
			channel.close();
			return null;
		}
		try {
			Files.setAttribute(file, "dos:hidden", true);
		} catch (IOException | RuntimeException e) {
			logger.debug("Could not hide {}", file, e);
		}
		return new OutputLock(file, channel);
	}

	/** Releases the lock and deletes the lock file. Never throws. */
	@Override
	public void close() {
		try {
			channel.close();
		} catch (IOException e) {
			logger.debug("Could not release {}", file, e);
		}
		try {
			Files.deleteIfExists(file);
		} catch (IOException e) {
			// Another conversion of the same output may have it open already; it deletes it when done
			logger.debug("Could not delete {}", file, e);
		}
	}
}
