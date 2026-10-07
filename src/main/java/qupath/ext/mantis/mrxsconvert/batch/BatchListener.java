package qupath.ext.mantis.mrxsconvert.batch;

/** Told about every state change after the batch starts. Called on batch threads, never the JavaFX thread. */
@FunctionalInterface
public interface BatchListener {

	BatchListener NONE = (index, state) -> {};

	/** @param index the job's position in the plan */
	void jobChanged(int index, JobState state);
}
