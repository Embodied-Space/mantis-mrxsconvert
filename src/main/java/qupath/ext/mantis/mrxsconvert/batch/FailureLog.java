package qupath.ext.mantis.mrxsconvert.batch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import static java.nio.file.StandardOpenOption.APPEND;
import static java.nio.file.StandardOpenOption.CREATE;

/**
 * The log of a job that failed before the runner wrote one (invalid slide, not enough disk space, tools not
 * extracted), so every failed job has a log to show (D9, spec 08). Same line format as the runner's log.
 */
final class FailureLog {

	private static final Logger logger = LoggerFactory.getLogger(FailureLog.class);
	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

	private FailureLog() {
	}

	/**
	 * Appends to {@code logFile}, in case the runner started one.
	 *
	 * @param output  null if no output could be named
	 * @param details extra lines, e.g. the disk-space figures
	 * @return the log file, or null if it couldn't be written
	 */
	static Path write(Path logFile, Path input, Path output, String reason, List<String> details) {
		String prefix = LocalDateTime.now().format(TIME) + " [mrxsconvert] ";
		List<String> lines = new ArrayList<>();
		lines.add(prefix + "Not converting " + input);
		if (output != null)
			lines.add(prefix + "Output " + output);
		details.forEach(d -> lines.add(prefix + d));
		lines.add(prefix + "Result FAILED: " + reason);
		try {
			Files.createDirectories(logFile.getParent());
			Files.write(logFile, lines, StandardCharsets.UTF_8, CREATE, APPEND);
			return logFile;
		} catch (IOException e) {
			logger.warn("Could not write the log file {}", logFile, e);
			return null;
		}
	}
}
