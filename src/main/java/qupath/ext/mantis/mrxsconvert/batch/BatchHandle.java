package qupath.ext.mantis.mrxsconvert.batch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.mantis.mrxsconvert.plan.PlannedJob;
import qupath.ext.mantis.mrxsconvert.runner.CancellationToken;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * A running batch, from {@link BatchQueue#start}: per-job state, overall progress, {@link #cancel()} and the final
 * {@link #report()}. Safe to use from any thread.
 */
public final class BatchHandle {

	private static final Logger logger = LoggerFactory.getLogger(BatchHandle.class);

	private final List<PlannedJob> jobs;
	private final List<String> warnings;
	private final AtomicReferenceArray<JobState> states;
	private final BatchListener listener;
	private final CancellationToken cancel = new CancellationToken();
	final CompletableFuture<BatchReport> report = new CompletableFuture<>();

	BatchHandle(List<PlannedJob> jobs, List<JobState> initialStates, List<String> warnings, BatchListener listener) {
		this.jobs = List.copyOf(jobs);
		this.warnings = List.copyOf(warnings);
		this.states = new AtomicReferenceArray<>(initialStates.toArray(JobState[]::new));
		this.listener = listener;
	}

	/** The plan's jobs, in order; indexes match {@link #state(int)}. */
	public List<PlannedJob> jobs() {
		return jobs;
	}

	public JobState state(int index) {
		return states.get(index);
	}

	/** User-facing warnings, e.g. the heap guard (spec 07 rule 8). Known at start. */
	public List<String> warnings() {
		return warnings;
	}

	/** Overall progress, 0–1: the mean of every job's progress, finished ones (skipped, failed…) counting as done. */
	public double progress() {
		if (jobs.isEmpty())
			return 1;
		double sum = 0;
		for (int i = 0; i < states.length(); i++)
			sum += states.get(i).fraction();
		return sum / states.length();
	}

	public boolean isCancelled() {
		return cancel.isCancelled();
	}

	/**
	 * Cancels the batch (D4): queued jobs become {@link JobState.Cancelled} at once, running ones stop and clean up
	 * (their tool processes are killed); finished outputs are kept. Returns at once; {@link #report()} completes once
	 * the running jobs have stopped.
	 */
	public void cancel() {
		cancel.cancel();
		for (int i = 0; i < states.length(); i++)
			if (states.compareAndSet(i, JobState.QUEUED, JobState.CANCELLED))
				notifyListener(i, JobState.CANCELLED);
	}

	/** Completes when every job has finished. Never completes exceptionally. */
	public CompletableFuture<BatchReport> report() {
		return report;
	}

	CancellationToken token() {
		return cancel;
	}

	/** QUEUED → RUNNING; false if the job was cancelled before it could start. */
	boolean tryStart(int index, JobState running) {
		if (!states.compareAndSet(index, JobState.QUEUED, running))
			return false;
		notifyListener(index, running);
		return true;
	}

	void set(int index, JobState state) {
		states.set(index, state);
		notifyListener(index, state);
	}

	private void notifyListener(int index, JobState state) {
		try {
			listener.jobChanged(index, state);
		} catch (RuntimeException e) {
			logger.warn("Batch listener failed", e);
		}
	}
}
