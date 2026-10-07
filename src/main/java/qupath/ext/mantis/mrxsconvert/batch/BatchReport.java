package qupath.ext.mantis.mrxsconvert.batch;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * The end-of-batch summary (B4, spec 07 rule 7).
 *
 * @param jobs     every job, in plan order
 * @param warnings user-facing warnings, e.g. the heap guard (rule 8)
 */
public record BatchReport(List<JobOutcome> jobs, List<String> warnings) {

	public BatchReport {
		jobs = List.copyOf(jobs);
		warnings = List.copyOf(warnings);
	}

	/**
	 * @param state    the final state; {@link JobState.Failed} carries the reason
	 * @param logFile  the conversion's log, or null if none was written
	 * @param duration time spent on the job
	 */
	public record JobOutcome(Path input, Path output, JobState state, Path logFile, Duration duration) {
	}

	public long count(JobState.Kind kind) {
		return jobs.stream().filter(j -> j.state().kind() == kind).count();
	}
}
