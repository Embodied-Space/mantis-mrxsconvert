package qupath.ext.mantis.mrxsconvert.batch;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qupath.ext.mantis.mrxsconvert.batch.DiskSpace.Volume;
import qupath.ext.mantis.mrxsconvert.batch.JobState.Kind;
import qupath.ext.mantis.mrxsconvert.plan.InputPlanner;
import qupath.ext.mantis.mrxsconvert.plan.Plan;
import qupath.ext.mantis.mrxsconvert.plan.PlannedJob;
import qupath.ext.mantis.mrxsconvert.plan.Selection;
import qupath.ext.mantis.mrxsconvert.runner.ConversionJob;
import qupath.ext.mantis.mrxsconvert.runner.ConversionResult;
import qupath.ext.mantis.mrxsconvert.runner.ConversionResult.Status;
import qupath.ext.mantis.mrxsconvert.runner.ProgressListener.Phase;
import qupath.ext.mantis.mrxsconvert.settings.Compression;
import qupath.ext.mantis.mrxsconvert.settings.ConversionSettings;
import qupath.ext.mantis.mrxsconvert.settings.MachineLimits;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Spec 07 acceptance with a fake runner and fake disks. */
class BatchQueueTest {

	private static final long GB = 1L << 30;
	private static final MachineLimits LIMITS = new MachineLimits(8, 16 * GB);
	private static final Duration TIMEOUT = Duration.ofSeconds(30);

	@TempDir
	Path temp;

	private long freeBytes = 100 * GB;
	private final List<String> started = new CopyOnWriteArrayList<>();
	private final AtomicInteger running = new AtomicInteger();
	private final AtomicInteger maxRunning = new AtomicInteger();

	/** One volume for everything, with {@link #freeBytes} free. */
	private final DiskSpace disk = path -> new Volume("C", "Disk (C:)", freeBytes);

	@BeforeEach
	void createLogDir() throws IOException {
		// The real runner creates it
		Files.createDirectories(temp.resolve("logs"));
	}

	/** Slides {@code s1.mrxs}…{@code sN.mrxs}, each with a data folder, planned next to themselves. */
	private Plan slides(int n) throws IOException {
		List<Selection> selections = new ArrayList<>();
		for (int i = 1; i <= n; i++) {
			Path slide = Files.write(temp.resolve("s" + i + ".mrxs"), new byte[1000]);
			Path data = Files.createDirectories(temp.resolve("s" + i));
			Files.write(data.resolve("Slidedat.ini"), new byte[0]);
			selections.add(new Selection.File(slide));
		}
		return InputPlanner.plan(selections, Optional.empty());
	}

	private BatchQueue queue(JobConverter converter) {
		return new BatchQueue(converter, disk, temp.resolve("tmp"), temp.resolve("logs"), LIMITS);
	}

	private static ConversionSettings parallel(int n) {
		return new ConversionSettings(n, null, Compression.LZW, null, 1024, 1024, null, 4, null);
	}

	/** A converter that runs {@code body}, writes the log and output on success, and tracks concurrency. */
	private JobConverter converter(Function<ConversionJob, Status> body) {
		return (job, settings, progress, cancel, gate) -> {
			String name = job.input().getFileName().toString();
			started.add(name);
			maxRunning.accumulateAndGet(running.incrementAndGet(), Math::max);
			try {
				Files.writeString(job.logFile(), "log of " + name);
				progress.update(Phase.EXTRACTING, 0.25);
				Status status = body.apply(job);
				if (status == Status.SUCCEEDED)
					Files.createFile(job.output());
				return new ConversionResult(status, status == Status.FAILED ? "boom" : null, Duration.ZERO,
						job.logFile());
			} catch (IOException e) {
				throw new RuntimeException(e);
			} finally {
				running.decrementAndGet();
			}
		};
	}

	private static Status sleepThen(Status status) {
		try {
			Thread.sleep(50);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		return status;
	}

	private static BatchReport await(BatchHandle handle) throws Exception {
		return handle.report().get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
	}

	private static List<Kind> kinds(BatchReport report) {
		return report.jobs().stream().map(j -> j.state().kind()).toList();
	}

	@Test
	void oneAtATimeRunsStrictlyInOrder() throws Exception {
		BatchReport report = await(queue(converter(j -> sleepThen(Status.SUCCEEDED)))
				.start(slides(5), parallel(1), BatchListener.NONE));
		assertEquals(List.of("s1.mrxs", "s2.mrxs", "s3.mrxs", "s4.mrxs", "s5.mrxs"), started);
		assertEquals(1, maxRunning.get());
		assertEquals(5, report.count(Kind.SUCCEEDED));
		assertTrue(report.jobs().stream().allMatch(j -> j.logFile() != null && Files.exists(j.logFile())));
	}

	@Test
	void neverMoreThanParallelFilesAtOnce() throws Exception {
		BatchReport report = await(queue(converter(j -> sleepThen(Status.SUCCEEDED)))
				.start(slides(5), parallel(2), BatchListener.NONE));
		assertEquals(2, maxRunning.get());
		assertEquals(5, report.count(Kind.SUCCEEDED));
	}

	@Test
	void aFailureDoesNotStopTheOthers() throws Exception {
		BatchReport report = await(queue(converter(j -> j.input().getFileName().toString().equals("s2.mrxs")
				? Status.FAILED : Status.SUCCEEDED)).start(slides(5), parallel(1), BatchListener.NONE));
		assertEquals(List.of(Kind.SUCCEEDED, Kind.FAILED, Kind.SUCCEEDED, Kind.SUCCEEDED, Kind.SUCCEEDED),
				kinds(report));
		var failed = assertInstanceOf(JobState.Failed.class, report.jobs().get(1).state());
		assertEquals("boom", failed.reason());
		assertTrue(failed.logFile().toString().endsWith("_02_s2.log"), failed.logFile().toString());
	}

	@Test
	void aConverterThatThrowsOnlyFailsItsOwnJob() throws Exception {
		BatchReport report = await(queue(converter(j -> {
			throw new IllegalStateException("bug");
		})).start(slides(2), parallel(1), BatchListener.NONE));
		assertEquals(List.of(Kind.FAILED, Kind.FAILED), kinds(report));
		assertEquals(2, started.size());
		// Appended to what the converter had logged
		String log = failedLog(report, 0);
		assertTrue(log.startsWith("log of s1.mrxs") && log.contains("java.lang.IllegalStateException: bug"), log);
	}

	/** Every failed job has a log, and it states the reason (D9, spec 08). */
	private static String failedLog(BatchReport report, int index) throws IOException {
		var failed = assertInstanceOf(JobState.Failed.class, report.jobs().get(index).state());
		assertEquals(failed.logFile(), report.jobs().get(index).logFile());
		String log = Files.readString(failed.logFile());
		assertTrue(log.contains("Result FAILED: " + failed.reason()), log);
		return log;
	}

	@Test
	void lowDiskSpaceFailsThatJobWithAReadableMessage() throws Exception {
		Plan plan = slides(3);
		// s2 is 50 MB: it needs 13 × 50 MB + 1 GiB, the others only a few KB more than 1 GiB
		Files.write(temp.resolve("s2").resolve("Data0000.dat"), new byte[50_000_000]);
		freeBytes = GB + 1_000_000;
		BatchReport report = await(queue(converter(j -> Status.SUCCEEDED)).start(plan, parallel(1),
				BatchListener.NONE));
		assertEquals(List.of(Kind.SUCCEEDED, Kind.FAILED, Kind.SUCCEEDED), kinds(report));
		String reason = ((JobState.Failed) report.jobs().get(1).state()).reason();
		assertEquals("Not enough disk space: need 1.6 GB, have 1.0 GB on Disk (C:).", reason);
		String log = failedLog(report, 1);
		assertTrue(log.contains("Input size 47.7 MB") && log.contains("on Disk (C:): needs 619.9 MB + 1.0 GB headroom; "
				+ "1.0 GB free, 0 bytes of it reserved by running conversions"), log);
		assertEquals(List.of("s1.mrxs", "s3.mrxs"), started);
	}

	@Test
	void cancelDuringJobTwoOfFive() throws Exception {
		var jobTwoRunning = new CountDownLatch(1);
		JobConverter blockOnJobTwo = (job, settings, progress, cancel, gate) -> {
			started.add(job.input().getFileName().toString());
			if (!job.input().getFileName().toString().equals("s2.mrxs"))
				return new ConversionResult(Status.SUCCEEDED, null, Duration.ZERO, job.logFile());
			jobTwoRunning.countDown();
			// Like the runner: poll the token until cancelled
			while (!cancel.isCancelled())
				Thread.onSpinWait();
			return new ConversionResult(Status.CANCELLED, null, Duration.ZERO, job.logFile());
		};
		List<String> events = new CopyOnWriteArrayList<>();
		BatchHandle handle = queue(blockOnJobTwo).start(slides(5), parallel(1),
				(index, state) -> events.add(index + ":" + state.kind()));
		assertTrue(jobTwoRunning.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS));
		handle.cancel();
		// Queued jobs are cancelled at once, not when their turn comes
		for (int i = 2; i < 5; i++)
			assertEquals(Kind.CANCELLED, handle.state(i).kind());

		BatchReport report = await(handle);
		assertEquals(List.of(Kind.SUCCEEDED, Kind.CANCELLED, Kind.CANCELLED, Kind.CANCELLED, Kind.CANCELLED),
				kinds(report));
		assertEquals(List.of("s1.mrxs", "s2.mrxs"), started);
		assertTrue(events.containsAll(List.of("0:RUNNING", "0:SUCCEEDED", "1:RUNNING", "1:CANCELLED",
				"2:CANCELLED", "3:CANCELLED", "4:CANCELLED")), events.toString());
		assertEquals(1.0, handle.progress());
	}

	@Test
	void skippedAndInvalidJobsAreFinalAtStart() throws Exception {
		Plan plan = slides(3);
		Files.createFile(temp.resolve("s1.ome.tiff"));
		Files.delete(temp.resolve("s3").resolve("Slidedat.ini"));
		plan = InputPlanner.plan(plan.jobs().stream().map(j -> (Selection) new Selection.File(j.input())).toList(),
				Optional.empty());
		var converting = new CountDownLatch(1);
		var release = new CountDownLatch(1);
		BatchHandle handle = queue((job, settings, progress, cancel, gate) -> {
			converting.countDown();
			try {
				release.await();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			return new ConversionResult(Status.SUCCEEDED, null, Duration.ZERO, job.logFile());
		}).start(plan, parallel(1), BatchListener.NONE);

		assertEquals(Kind.SKIPPED, handle.state(0).kind());
		assertEquals(Kind.FAILED, handle.state(2).kind());
		assertTrue(converting.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS));
		assertEquals(Kind.RUNNING, handle.state(1).kind());
		assertEquals(2 / 3.0, handle.progress(), 1e-9);
		release.countDown();

		BatchReport report = await(handle);
		assertEquals(List.of(Kind.SKIPPED, Kind.SUCCEEDED, Kind.FAILED), kinds(report));
		assertTrue(((JobState.Failed) report.jobs().get(2).state()).reason().contains("Slidedat.ini"));
		// Known at start, so the UI can offer it at once
		assertEquals(((JobState.Failed) handle.state(2)).logFile(), report.jobs().get(2).logFile());
		assertTrue(failedLog(report, 2).contains("Not converting " + temp.resolve("s3.mrxs")));
		assertEquals(1, report.count(Kind.SKIPPED));
	}

	@Test
	void outputThatAppearsAfterPlanningIsSkipped() throws Exception {
		Plan plan = slides(1);
		Files.createFile(plan.jobs().getFirst().output());
		BatchReport report = await(queue(converter(j -> Status.SUCCEEDED)).start(plan, parallel(1),
				BatchListener.NONE));
		assertEquals(List.of(Kind.SKIPPED), kinds(report));
		assertEquals(List.of(), started);
	}

	@Test
	void uncompressedIsRecheckedWithTheExactSizeBeforeStepTwo() throws Exception {
		// 1000-byte slide: the 80× pre-flight passes easily, the Zarr then says 32 GiB
		freeBytes = 20 * GB;
		Path zarr = Files.createDirectories(temp.resolve("zarr").resolve("0").resolve("0"));
		Files.writeString(zarr.resolve(".zarray"),
				"{\"shape\": [1, 2, 1, 131072, 131072], \"chunks\": [1, 1, 1, 1024, 1024], \"dtype\": \"|u1\"}");
		List<String> refusals = new CopyOnWriteArrayList<>();
		JobConverter converter = (job, settings, progress, cancel, gate) -> {
			String refusal = gate.beforeWriting(temp.resolve("zarr"));
			refusals.add(String.valueOf(refusal));
			return refusal == null ? new ConversionResult(Status.SUCCEEDED, null, Duration.ZERO, job.logFile())
					: new ConversionResult(Status.FAILED, refusal, Duration.ZERO, job.logFile());
		};
		var uncompressed = new ConversionSettings(1, null, Compression.UNCOMPRESSED, null, 1024, 1024, null, 4, null);
		BatchReport report = await(queue(converter).start(slides(1), uncompressed, BatchListener.NONE));
		assertEquals(List.of(Kind.FAILED), kinds(report));
		assertTrue(refusals.getFirst().startsWith("Not enough disk space: need 33.0 GB"), refusals.toString());

		// LZW gets no gate
		refusals.clear();
		report = await(queue(converter).start(slides(1), parallel(1), BatchListener.NONE));
		assertEquals(List.of(Kind.SUCCEEDED), kinds(report));
		assertEquals(List.of("null"), refusals);
	}

	@Test
	void planWithProblemsIsRefused() throws IOException {
		Plan plan = new Plan(slides(1).jobs(), List.of(new Plan.Problem("clash", List.of())));
		assertThrows(IllegalArgumentException.class,
				() -> queue(converter(j -> Status.SUCCEEDED)).start(plan, parallel(1), BatchListener.NONE));
	}

	@Test
	void emptyBatchReportsAtOnce() throws Exception {
		BatchReport report = await(queue(converter(j -> Status.SUCCEEDED))
				.start(new Plan(List.of(), List.of()), parallel(1), BatchListener.NONE));
		assertEquals(List.of(), report.jobs());
	}

	@Test
	void heapWarningStartsAtFourParallelFilesWithAutoHeap() {
		assertFalse(BatchQueue.heapWarning(parallel(3), LIMITS).isPresent());
		String warning = BatchQueue.heapWarning(parallel(4), LIMITS).orElseThrow();
		assertTrue(warning.startsWith("Converting 4 files at once could use up to 16.0 GB"), warning);

		var fixedHeap = new ConversionSettings(2, 7 * 1024, Compression.LZW, null, 1024, 1024, null, 4, null);
		assertTrue(BatchQueue.heapWarning(fixedHeap, LIMITS).isPresent());
		var smallHeap = new ConversionSettings(8, 1024, Compression.LZW, null, 1024, 1024, null, 4, null);
		assertFalse(BatchQueue.heapWarning(smallHeap, LIMITS).isPresent());
		assertFalse(BatchQueue.heapWarning(parallel(8), new MachineLimits(8, 0)).isPresent());
	}

	@Test
	void heapWarningIsInTheReport() throws Exception {
		BatchHandle handle = queue(converter(j -> Status.SUCCEEDED)).start(new Plan(List.of(), List.of()),
				parallel(4), BatchListener.NONE);
		assertEquals(1, handle.warnings().size());
		assertEquals(handle.warnings(), await(handle).warnings());
	}

	@Test
	void logNamesAreUniquePerJob() {
		var job = new PlannedJob(Path.of("D:\\a\\My slide 1.2.mrxs"), Path.of("D:\\a\\My slide 1.2.ome.tiff"),
				PlannedJob.Status.READY, null);
		assertEquals("2026-10-07_143012_03_My slide 1.2.log", BatchQueue.logName("2026-10-07_143012", 2, job));
	}
}
