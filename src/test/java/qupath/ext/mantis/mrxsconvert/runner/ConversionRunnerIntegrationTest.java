package qupath.ext.mantis.mrxsconvert.runner;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qupath.ext.mantis.mrxsconvert.payload.ExtensionJarPayload;
import qupath.ext.mantis.mrxsconvert.payload.ToolPaths;
import qupath.ext.mantis.mrxsconvert.runner.ConversionResult.Status;
import qupath.ext.mantis.mrxsconvert.runner.ProgressListener.Phase;
import qupath.ext.mantis.mrxsconvert.settings.ConversionSettings;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Spec 03 acceptance with the real payload (from the {@code -all} jar) and the local samples in {@code reference/}.
 * Skipped when either is missing. Takes a few minutes.
 */
class ConversionRunnerIntegrationTest {

	private static final Path SCANS = Path.of("reference", "original scans");
	private static final Path OUTPUTS = Path.of("reference", "outputs");
	private static final String APOL1 = "APOL1_650_APOL1_570_Nephrin_520_MTS 43.1 A_2";
	private static final String PROX1 = "PROX1_500_Mm lung_MCT 1.2 A_8";
	/**
	 * Kept between runs: extracting the payload takes a while. Not under build/: an IDE's file watcher there can
	 * block renaming the freshly extracted folder into place.
	 */
	private static final Path TOOLS_DIR = Path.of(System.getProperty("java.io.tmpdir"), "mantis-mrxsconvert-test-tools");

	private static ToolPaths tools;

	@TempDir
	Path temp;

	@BeforeAll
	static void extract() throws Exception {
		assumeTrue(Files.isDirectory(SCANS), "reference/ samples not present");
		tools = ExtensionJarPayload.extract(TOOLS_DIR);
		assumeTrue(tools != null, "mrxsconvert.extensionJar not set; run through Gradle");
	}

	@AfterAll
	static void release() throws IOException {
		ExtensionJarPayload.release();
	}

	@AfterEach
	void noToolProcessesRemain() {
		assertEquals(List.of(), toolProcesses());
	}

	private Path workRoot() {
		return temp.resolve("work");
	}

	/** Output in a folder with spaces that doesn't exist yet, like a mirrored tree (E1). */
	private ConversionJob job(String scan) {
		return new ConversionJob(SCANS.resolve(scan + ".mrxs"), temp.resolve("out dir").resolve(scan + ".ome.tiff"),
				temp.resolve("logs").resolve(scan + ".log"));
	}

	private ConversionResult convert(ConversionJob job, ProgressListener progress, CancellationToken cancel) {
		return new ConversionRunner(workRoot()).convert(job, ConversionSettings.DEFAULTS, tools, progress, cancel);
	}

	@Test
	void defaultSettingsMatchTheKnownGoodOutput() throws IOException {
		ConversionJob job = job(APOL1);
		List<String> phases = new CopyOnWriteArrayList<>();
		ConversionResult result = convert(job, (phase, fraction) -> phases.add(phase + " " + fraction),
				new CancellationToken());

		String log = Files.readString(job.logFile());
		assertEquals(Status.SUCCEEDED, result.status(), result.reason() + "\n" + log);
		assertEquals(List.of("EXTRACTING 0.0", "WRITING 0.5", "WRITING 1.0"), phases);
		assertTrue(Files.isRegularFile(job.output()));
		assertNothingLeftBehind(job);

		// C3: the same images, pixel layout and channels as the manual CLI output. Tiles: spec 09.
		List<String> expected = OmeTiffMetadata.summary(OUTPUTS.resolve("4colorpyramid.ome.tiff"));
		assertEquals(expected, OmeTiffMetadata.summary(job.output()));
		assertTrue(expected.stream().filter(e -> e.startsWith("Channel")).count() >= 4, expected.toString());

		assertTrue(log.contains(ConversionRunner.B2R_MAIN) && log.contains(ConversionRunner.R2O_MAIN), log);
		assertEquals(2, log.split("Exit code 0 ", -1).length - 1, log);
		assertFalse(log.contains("Picked up"), log);
		// No logback configuration errors, no Java 25 native-access warnings
		assertFalse(log.contains("ERROR in ch.qos.logback"), log);
		assertFalse(log.contains("WARNING: A restricted method"), log);
	}

	@Test
	void failedStep1LeavesNothingAndLogsTheToolOutput() throws IOException {
		Path bad = Files.writeString(temp.resolve("not a slide.mrxs"), "this is not a slide");
		ConversionJob job = new ConversionJob(bad, temp.resolve("not a slide.ome.tiff"), temp.resolve("bad.log"));
		ConversionResult result = convert(job, ProgressListener.NONE, new CancellationToken());

		String log = Files.readString(job.logFile());
		assertEquals(Status.FAILED, result.status(), log);
		assertTrue(result.reason().startsWith("Reading the slide failed"), result.reason());
		assertFalse(log.contains(ConversionRunner.R2O_MAIN), log);
		assertTrue(log.lines().anyMatch(line -> !line.contains("[mrxsconvert]")), "no tool output in the log:\n" + log);
		assertNothingLeftBehind(job);
	}

	@Test
	void cancelDuringStep1() throws Exception {
		ConversionJob job = job(PROX1);
		var cancel = new CancellationToken();
		var running = CompletableFuture.supplyAsync(() -> convert(job, ProgressListener.NONE, cancel));
		try {
			awaitToolProcess(Duration.ofSeconds(30));
			// Let bioformats2raw start writing the Zarr
			awaitFile(() -> onlyWorkspace().resolve(ConversionRunner.ZARR_DIR), Duration.ofSeconds(60));
			assertFalse(running.isDone(), "step 1 finished too fast to cancel");
		} finally {
			cancel.cancel();
		}

		ConversionResult result = running.get(60, TimeUnit.SECONDS);
		assertEquals(Status.CANCELLED, result.status(), Files.readString(job.logFile()));
		assertFalse(Files.readString(job.logFile()).contains(ConversionRunner.R2O_MAIN));
		assertNothingLeftBehind(job);
	}

	@Test
	void cancelDuringStep2() throws Exception {
		ConversionJob job = job(PROX1);
		var cancel = new CancellationToken();
		var writing = new CountDownLatch(1);
		var running = CompletableFuture.supplyAsync(() -> convert(job, (phase, fraction) -> {
			if (phase == Phase.WRITING)
				writing.countDown();
		}, cancel));

		try {
			assertTrue(writing.await(5, TimeUnit.MINUTES), "step 2 never started");
			awaitToolProcess(Duration.ofSeconds(30));
			// Let raw2ometiff start writing the .partial
			awaitFile(job::partialOutput, Duration.ofSeconds(60));
			assertFalse(running.isDone(), "step 2 finished too fast to cancel");
		} finally {
			cancel.cancel();
		}

		ConversionResult result = running.get(60, TimeUnit.SECONDS);
		assertEquals(Status.CANCELLED, result.status(), Files.readString(job.logFile()));
		assertNothingLeftBehind(job);
	}

	private void assertNothingLeftBehind(ConversionJob job) throws IOException {
		assertFalse(Files.exists(job.partialOutput()), "partial left behind");
		assertFalse(Files.exists(OutputLock.lockFile(job)), "lock file left behind");
		try (Stream<Path> left = Files.list(workRoot())) {
			assertEquals(List.of(), left.toList(), "workspace left behind");
		}
		assertEquals(List.of(), toolProcesses());
	}

	private Path onlyWorkspace() throws IOException {
		try (Stream<Path> dirs = Files.list(workRoot())) {
			List<Path> all = dirs.toList();
			assertEquals(1, all.size(), all.toString());
			return all.getFirst();
		}
	}

	private interface PathSupplier {
		Path get() throws IOException;
	}

	private static void awaitFile(PathSupplier file, Duration timeout) throws IOException, InterruptedException {
		long deadline = System.nanoTime() + timeout.toNanos();
		while (!Files.exists(file.get())) {
			assertTrue(System.nanoTime() < deadline, "never appeared: " + file.get());
			Thread.sleep(100);
		}
	}

	private static void awaitToolProcess(Duration timeout) throws InterruptedException {
		long deadline = System.nanoTime() + timeout.toNanos();
		while (toolProcesses().isEmpty()) {
			assertTrue(System.nanoTime() < deadline, "no tool process started");
			Thread.sleep(100);
		}
	}

	/** Every process running the bundled {@code java.exe}, whoever its parent is. */
	private static List<ProcessHandle> toolProcesses() {
		String java = tools.javaExe().toString();
		return ProcessHandle.allProcesses()
				.filter(ProcessHandle::isAlive)
				.filter(p -> p.info().command().map(java::equalsIgnoreCase).orElse(false))
				.toList();
	}
}
