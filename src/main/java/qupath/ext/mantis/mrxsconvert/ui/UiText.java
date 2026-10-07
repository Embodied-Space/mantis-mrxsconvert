package qupath.ext.mantis.mrxsconvert.ui;

import qupath.ext.mantis.mrxsconvert.batch.BatchReport;
import qupath.ext.mantis.mrxsconvert.batch.JobState;
import qupath.ext.mantis.mrxsconvert.plan.Plan;
import qupath.ext.mantis.mrxsconvert.plan.PlannedJob;

import java.text.MessageFormat;
import java.util.ResourceBundle;

/**
 * The dialog's user-facing text (spec 08), kept apart from JavaFX so it can be unit-tested. Every string goes through
 * {@link MessageFormat}, so apostrophes in {@code strings.properties} are always doubled.
 */
final class UiText {

	private static final ResourceBundle resources = ResourceBundle.getBundle("qupath.ext.mantis.mrxsconvert.strings");

	private UiText() {
	}

	static String get(String key, Object... args) {
		return MessageFormat.format(resources.getString(key), args);
	}

	/** The job list's status column before the batch starts. */
	static String plannedStatus(PlannedJob job) {
		return switch (job.status()) {
			case READY -> get("ui.status.ready");
			case SKIP_EXISTS -> get("ui.status.skip");
			case INVALID -> get("ui.status.invalid", job.reason());
		};
	}

	/** The progress view's status column. */
	static String state(JobState state) {
		return switch (state) {
			case JobState.Queued q -> get("ui.state.queued");
			case JobState.Running r -> switch (r.phase()) {
				case EXTRACTING -> get("ui.state.extracting");
				case WRITING -> get("ui.state.writing");
			};
			case JobState.Succeeded s -> get("ui.state.succeeded");
			case JobState.Skipped s -> get("ui.state.skipped");
			case JobState.Failed f -> get("ui.state.failed");
			case JobState.Cancelled c -> get("ui.state.cancelled");
		};
	}

	/**
	 * Why Start is disabled, or how many slides will run.
	 *
	 * @param plan             the latest plan, or null if none yet
	 * @param empty            nothing has been added
	 * @param planning         a newer plan is being made
	 * @param missingOutputDir "In this folder" is chosen but no folder is set
	 */
	static String startHint(Plan plan, boolean empty, boolean planning, boolean missingOutputDir) {
		if (empty)
			return get("ui.setup.hint.empty");
		if (missingOutputDir)
			return get("ui.setup.hint.no-folder");
		if (planning || plan == null)
			return get("ui.setup.hint.planning");
		if (!plan.problems().isEmpty())
			return get("ui.setup.hint.problems");
		long ready = plan.jobs().stream().filter(j -> j.status() == PlannedJob.Status.READY).count();
		return ready == 0 ? get("ui.setup.hint.nothing") : get("ui.setup.hint.ready", ready);
	}

	/** The end-of-batch counts (spec 08 §4). */
	static String summary(BatchReport report) {
		return get("ui.batch.summary", report.count(JobState.Kind.SUCCEEDED), report.count(JobState.Kind.SKIPPED),
				report.count(JobState.Kind.FAILED), report.count(JobState.Kind.CANCELLED));
	}
}
