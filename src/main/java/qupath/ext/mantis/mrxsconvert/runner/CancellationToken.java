package qupath.ext.mantis.mrxsconvert.runner;

/** Set from any thread (e.g. the Cancel button); the runner polls it. */
public final class CancellationToken {

	private volatile boolean cancelled;

	public void cancel() {
		cancelled = true;
	}

	public boolean isCancelled() {
		return cancelled;
	}
}
