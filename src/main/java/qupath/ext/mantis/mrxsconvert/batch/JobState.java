package qupath.ext.mantis.mrxsconvert.batch;

import qupath.ext.mantis.mrxsconvert.runner.ProgressListener.Phase;

import java.nio.file.Path;

/** Where one job of a batch is. Immutable; a new state replaces the old one. */
public sealed interface JobState {

	JobState QUEUED = new Queued();
	JobState CANCELLED = new Cancelled();

	enum Kind {QUEUED, RUNNING, SUCCEEDED, SKIPPED, FAILED, CANCELLED}

	Kind kind();

	/** True once the job has ended, however it ended. */
	default boolean isFinished() {
		return kind() != Kind.QUEUED && kind() != Kind.RUNNING;
	}

	/** This job's progress, 0–1; finished jobs count as 1. */
	default double fraction() {
		return isFinished() ? 1 : 0;
	}

	record Queued() implements JobState {
		@Override
		public Kind kind() {
			return Kind.QUEUED;
		}
	}

	/** @param fraction this conversion's progress, 0–1 */
	record Running(Phase phase, double fraction) implements JobState {
		@Override
		public Kind kind() {
			return Kind.RUNNING;
		}
	}

	/** @param logFile the conversion's log */
	record Succeeded(Path logFile) implements JobState {
		@Override
		public Kind kind() {
			return Kind.SUCCEEDED;
		}
	}

	/** The output already existed (B4). */
	record Skipped() implements JobState {
		@Override
		public Kind kind() {
			return Kind.SKIPPED;
		}
	}

	/**
	 * @param reason  user-facing explanation
	 * @param logFile the conversion's log, or null if it failed before converting (e.g. not enough disk space)
	 */
	record Failed(String reason, Path logFile) implements JobState {
		@Override
		public Kind kind() {
			return Kind.FAILED;
		}
	}

	record Cancelled() implements JobState {
		@Override
		public Kind kind() {
			return Kind.CANCELLED;
		}
	}
}
