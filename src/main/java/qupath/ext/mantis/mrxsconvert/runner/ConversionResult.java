package qupath.ext.mantis.mrxsconvert.runner;

import java.nio.file.Path;
import java.time.Duration;

/**
 * @param status   how the conversion ended
 * @param reason   a short, user-facing explanation when {@link Status#FAILED}; otherwise null
 * @param duration time taken
 * @param logPath  the job's log file
 */
public record ConversionResult(Status status, String reason, Duration duration, Path logPath) {

	public enum Status {SUCCEEDED, FAILED, CANCELLED}
}
