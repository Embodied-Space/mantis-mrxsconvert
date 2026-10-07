package qupath.ext.mantis.mrxsconvert.batch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.mantis.mrxsconvert.AppDirs;
import qupath.ext.mantis.mrxsconvert.batch.DiskSpaceGuard.NotEnoughSpace;
import qupath.ext.mantis.mrxsconvert.batch.DiskSpaceGuard.Reservation;
import qupath.ext.mantis.mrxsconvert.plan.Plan;
import qupath.ext.mantis.mrxsconvert.plan.PlannedJob;
import qupath.ext.mantis.mrxsconvert.runner.ConversionJob;
import qupath.ext.mantis.mrxsconvert.runner.ConversionResult;
import qupath.ext.mantis.mrxsconvert.runner.ConversionRunner;
import qupath.ext.mantis.mrxsconvert.runner.ProgressListener.Phase;
import qupath.ext.mantis.mrxsconvert.runner.StepGate;
import qupath.ext.mantis.mrxsconvert.settings.Compression;
import qupath.ext.mantis.mrxsconvert.settings.ConversionSettings;
import qupath.ext.mantis.mrxsconvert.settings.MachineLimits;
import qupath.ext.mantis.mrxsconvert.temp.TempWorkspace;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.ResourceBundle;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

/**
 * Runs a {@link Plan} (spec 07): {@code parallelFiles} conversions at a time on background threads, a disk-space
 * check before each, cancellation, and a report at the end. A failed job never stops the others (B4).
 */
public final class BatchQueue {

	private static final Logger logger = LoggerFactory.getLogger(BatchQueue.class);
	private static final ResourceBundle resources = ResourceBundle.getBundle("qupath.ext.mantis.mrxsconvert.strings");
	private static final DateTimeFormatter LOG_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss");
	private static final AtomicInteger batchCount = new AtomicInteger();

	/** Heap guard (rule 8): warn when the JVMs' combined max heap passes this share of RAM. */
	static final double HEAP_WARNING_SHARE = 0.75;
	/** The JVM's default max heap (E3) is a quarter of RAM. */
	static final double DEFAULT_HEAP_SHARE = 0.25;

	private final JobConverter converter;
	private final DiskSpaceGuard space;
	private final Path logDir;
	private final MachineLimits limits;

	/**
	 * @param tempRoot where the runner puts its workspaces; its volume is checked for the Zarr
	 * @param logDir   where each conversion's log goes (D9)
	 */
	public BatchQueue(JobConverter converter, DiskSpace disk, Path tempRoot, Path logDir, MachineLimits limits) {
		this.converter = converter;
		this.space = new DiskSpaceGuard(disk, tempRoot);
		this.logDir = logDir;
		this.limits = limits;
	}

	/** The real runner, temp folder, logs folder and disks. */
	public static BatchQueue forQuPath() {
		Path tempRoot = TempWorkspace.defaultRoot();
		return new BatchQueue(JobConverter.using(new ConversionRunner(tempRoot)), DiskSpace.SYSTEM, tempRoot,
				AppDirs.logs(), MachineLimits.current());
	}

	/**
	 * Starts the batch and returns at once. Skipped and invalid jobs are final in the returned handle's initial
	 * states; the listener hears about every change after that.
	 *
	 * @throws IllegalArgumentException if the plan has blocking problems
	 */
	public BatchHandle start(Plan plan, ConversionSettings settings, BatchListener listener) {
		if (!plan.problems().isEmpty())
			throw new IllegalArgumentException("Plan has blocking problems: " + plan.problems());
		List<PlannedJob> jobs = plan.jobs();
		String batchName = LocalDateTime.now().format(LOG_TIME);
		List<Path> logFiles = IntStream.range(0, jobs.size())
				.mapToObj(i -> logDir.resolve(logName(batchName, i, jobs.get(i)))).toList();
		List<JobState> initial = IntStream.range(0, jobs.size())
				.mapToObj(i -> initialState(jobs.get(i), logFiles.get(i))).toList();
		var handle = new BatchHandle(jobs, initial, heapWarning(settings, limits).stream().toList(), listener);
		Duration[] durations = new Duration[jobs.size()];

		int id = batchCount.incrementAndGet();
		AtomicInteger threadCount = new AtomicInteger();
		ExecutorService pool = Executors.newFixedThreadPool(settings.parallelFiles(), r -> Thread.ofPlatform()
				.daemon().name("mrxsconvert-batch-" + id + "-" + threadCount.incrementAndGet()).unstarted(r));
		List<CompletableFuture<Void>> tasks = new ArrayList<>();
		// Invalid jobs' logs first, so they exist before conversions take the workers
		for (int i = 0; i < jobs.size(); i++) {
			if (jobs.get(i).status() != PlannedJob.Status.INVALID)
				continue;
			int index = i;
			tasks.add(CompletableFuture.runAsync(() -> {
				PlannedJob job = jobs.get(index);
				if (FailureLog.write(logFiles.get(index), job.input(), job.output(), job.reason(), List.of()) == null)
					handle.set(index, new JobState.Failed(job.reason(), null));
			}, pool));
		}
		for (int i = 0; i < jobs.size(); i++) {
			if (initial.get(i) != JobState.QUEUED)
				continue;
			int index = i;
			// FIFO: with one worker, jobs run strictly in plan order
			tasks.add(CompletableFuture.runAsync(() -> {
				long start = System.nanoTime();
				try {
					runJob(handle, index, settings, logFiles.get(index));
				} catch (RuntimeException e) {
					logger.warn("Conversion of {} failed", jobs.get(index).input(), e);
					fail(handle, index, logFiles.get(index), message("batch.failed.unexpected", e),
							List.of("Error: " + stackTrace(e)));
				}
				durations[index] = Duration.ofNanos(System.nanoTime() - start);
			}, pool));
		}
		pool.shutdown();
		CompletableFuture.allOf(tasks.toArray(CompletableFuture[]::new)).whenComplete((ignored, error) -> {
			List<BatchReport.JobOutcome> outcomes = new ArrayList<>();
			for (int i = 0; i < jobs.size(); i++)
				outcomes.add(new BatchReport.JobOutcome(jobs.get(i).input(), jobs.get(i).output(), handle.state(i),
						Files.exists(logFiles.get(i)) ? logFiles.get(i) : null,
						durations[i] == null ? Duration.ZERO : durations[i]));
			handle.report.complete(new BatchReport(outcomes, handle.warnings()));
		});
		return handle;
	}

	private static JobState initialState(PlannedJob job, Path logFile) {
		return switch (job.status()) {
			case READY -> JobState.QUEUED;
			case SKIP_EXISTS -> new JobState.Skipped();
			// The log is written on a batch thread right after start
			case INVALID -> new JobState.Failed(job.reason(), logFile);
		};
	}

	private void runJob(BatchHandle handle, int index, ConversionSettings settings, Path logFile) {
		PlannedJob job = handle.jobs().get(index);
		if (!handle.tryStart(index, new JobState.Running(Phase.EXTRACTING, 0)))
			return;
		if (handle.isCancelled()) {
			handle.set(index, JobState.CANCELLED);
			return;
		}
		// Rule 4: the output may have appeared since planning
		if (Files.exists(job.output())) {
			handle.set(index, new JobState.Skipped());
			return;
		}
		Reservation reservation;
		try {
			reservation = space.reserve(DiskSpaceGuard.inputBytes(job.input()), job.output().getParent(),
					settings.compression());
		} catch (NotEnoughSpace e) {
			fail(handle, index, logFile, e.getMessage(), e.details());
			return;
		} catch (IOException e) {
			logger.warn("Could not check disk space for {}", job.input(), e);
			fail(handle, index, logFile, message("batch.failed.space-check", e.getMessage()),
					List.of("Error: " + e));
			return;
		}
		try (reservation) {
			StepGate gate = settings.compression() == Compression.UNCOMPRESSED
					? zarr -> recheckUncompressed(reservation, zarr) : StepGate.NONE;
			ConversionResult result = converter.convert(new ConversionJob(job.input(), job.output(), logFile),
					settings, (phase, fraction) -> handle.set(index, new JobState.Running(phase, fraction)),
					handle.token(), gate);
			handle.set(index, switch (result.status()) {
				case SUCCEEDED -> new JobState.Succeeded(result.logPath());
				case FAILED -> new JobState.Failed(result.reason(), result.logPath());
				case CANCELLED -> JobState.CANCELLED;
			});
		}
	}

	/** Fails a job the runner never logged, writing its log first (D9). */
	private static void fail(BatchHandle handle, int index, Path logFile, String reason, List<String> details) {
		PlannedJob job = handle.jobs().get(index);
		handle.set(index, new JobState.Failed(reason,
				FailureLog.write(logFile, job.input(), job.output(), reason, details)));
	}

	private static String stackTrace(Throwable e) {
		var writer = new StringWriter();
		e.printStackTrace(new PrintWriter(writer));
		return writer.toString().strip();
	}

	/** Uncompressed size is only known from the Zarr (rule 3). If it can't be read, the 80× pre-flight stands. */
	private static String recheckUncompressed(Reservation reservation, Path zarr) {
		try {
			return reservation.recheckOutput(ZarrSize.uncompressedTiffBytes(zarr));
		} catch (IOException | ArithmeticException e) {
			logger.warn("Could not compute the uncompressed size from {}; relying on the earlier estimate", zarr, e);
			return null;
		}
	}

	/** e.g. {@code 2026-10-07_143012_03_slide name.log}: unique per conversion, sorted by time. */
	static String logName(String batchName, int index, PlannedJob job) {
		String name = job.input().getFileName().toString();
		int dot = name.lastIndexOf('.');
		return String.format("%s_%02d_%s.log", batchName, index + 1, dot > 0 ? name.substring(0, dot) : name);
	}

	/**
	 * Rule 8: a warning when {@code parallelFiles} JVMs at their max heap could take more than 75 % of RAM. With Auto
	 * heap each JVM counts as 25 % of RAM, so this starts at 4 parallel files. Never blocks.
	 */
	public static Optional<String> heapWarning(ConversionSettings settings, MachineLimits limits) {
		long ram = limits.physicalMemoryBytes();
		if (ram <= 0)
			return Optional.empty();
		double perJvm = settings.heapMb() == null ? ram * DEFAULT_HEAP_SHARE : settings.heapMb() * 1024.0 * 1024.0;
		double total = perJvm * settings.parallelFiles();
		if (total <= ram * HEAP_WARNING_SHARE)
			return Optional.empty();
		return Optional.of(message("batch.warning.heap", settings.parallelFiles(),
				DiskSpaceGuard.formatBytes((long) total), DiskSpaceGuard.formatBytes(ram)));
	}

	private static String message(String key, Object... args) {
		return MessageFormat.format(resources.getString(key), args);
	}
}
