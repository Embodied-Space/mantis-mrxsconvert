package qupath.ext.mantis.mrxsconvert.plan;

import java.nio.file.Path;
import java.util.List;

/**
 * The result of {@link InputPlanner#plan}: the jobs, in the order the user added them, and anything that must be fixed
 * before the batch can start.
 */
public record Plan(List<PlannedJob> jobs, List<Problem> problems) {

	public Plan {
		jobs = List.copyOf(jobs);
		problems = List.copyOf(problems);
	}

	/**
	 * A blocking issue: Start stays disabled while there is one (D2).
	 *
	 * @param message user-facing explanation
	 * @param inputs  the slides or folders involved
	 */
	public record Problem(String message, List<Path> inputs) {
		public Problem {
			inputs = List.copyOf(inputs);
		}
	}

	/** True if there are no problems and at least one job would run. */
	public boolean canStart() {
		return problems.isEmpty() && jobs.stream().anyMatch(j -> j.status() == PlannedJob.Status.READY);
	}
}
