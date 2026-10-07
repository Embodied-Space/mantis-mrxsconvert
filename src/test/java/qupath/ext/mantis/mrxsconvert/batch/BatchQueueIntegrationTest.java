package qupath.ext.mantis.mrxsconvert.batch;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qupath.ext.mantis.mrxsconvert.batch.JobState.Kind;
import qupath.ext.mantis.mrxsconvert.payload.ExtensionJarPayload;
import qupath.ext.mantis.mrxsconvert.payload.ToolPaths;
import qupath.ext.mantis.mrxsconvert.plan.InputPlanner;
import qupath.ext.mantis.mrxsconvert.plan.Plan;
import qupath.ext.mantis.mrxsconvert.plan.PlannedJob;
import qupath.ext.mantis.mrxsconvert.plan.Selection;
import qupath.ext.mantis.mrxsconvert.runner.ConversionRunner;
import qupath.ext.mantis.mrxsconvert.settings.Compression;
import qupath.ext.mantis.mrxsconvert.settings.ConversionSettings;
import qupath.ext.mantis.mrxsconvert.settings.MachineLimits;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Plan → batch → real runner on the {@code reference/} samples: a full run, the rerun skipping it, and a cancel.
 * Skipped when the samples or the payload are missing.
 */
class BatchQueueIntegrationTest {

	private static final Path SCANS = Path.of("reference", "original scans");
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
	void nothingLeftBehind() throws IOException {
		assertEquals(List.of(), toolProcesses());
		try (Stream<Path> left = Files.list(workRoot())) {
			assertEquals(List.of(), left.toList(), "workspace left behind");
		}
	}

	private Path workRoot() throws IOException {
		return Files.createDirectories(temp.resolve("work"));
	}

	private BatchQueue queue() throws IOException {
		var runner = new ConversionRunner(workRoot());
		return new BatchQueue((job, settings, progress, cancel, gate) ->
				runner.convert(job, settings, tools, progress, cancel, gate),
				DiskSpace.SYSTEM, workRoot(), temp.resolve("logs"), MachineLimits.current());
	}

	private Plan plan() {
		return InputPlanner.plan(List.of(new Selection.Folder(SCANS)), Optional.of(temp.resolve("out")));
	}

	private static ConversionSettings parallel(int n) {
		return new ConversionSettings(n, null, Compression.LZW, null, 1024, 1024, null, 4, null);
	}

	@Test
	void convertsBothSamplesInParallelThenSkipsThemOnRerun() throws Exception {
		Plan plan = plan();
		assertTrue(plan.canStart(), plan.toString());
		BatchReport report = queue().start(plan, parallel(2), BatchListener.NONE).report().get(10, TimeUnit.MINUTES);

		assertEquals(List.of(Kind.SUCCEEDED, Kind.SUCCEEDED),
				report.jobs().stream().map(j -> j.state().kind()).toList(), report.toString());
		for (var job : report.jobs()) {
			// Mirrored under the override, including the selected folder's name (E1)
			assertEquals(temp.resolve("out").resolve("original scans"), job.output().getParent());
			assertTrue(Files.isRegularFile(job.output()));
			assertTrue(Files.isRegularFile(job.logFile()));
		}

		Plan rerun = plan();
		assertEquals(List.of(PlannedJob.Status.SKIP_EXISTS, PlannedJob.Status.SKIP_EXISTS),
				rerun.jobs().stream().map(PlannedJob::status).toList());
		assertFalse(rerun.canStart());
	}

	@Test
	void cancelStopsTheRunningToolAndTheQueue() throws Exception {
		BatchHandle handle = queue().start(plan(), parallel(1), BatchListener.NONE);
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
		while (toolProcesses().isEmpty()) {
			assertTrue(System.nanoTime() < deadline, "no tool process started");
			Thread.sleep(100);
		}
		handle.cancel();

		BatchReport report = handle.report().get(2, TimeUnit.MINUTES);
		assertEquals(List.of(Kind.CANCELLED, Kind.CANCELLED),
				report.jobs().stream().map(j -> j.state().kind()).toList(), report.toString());
		for (var job : report.jobs())
			assertFalse(Files.exists(job.output()));
	}

	/** Every process running the bundled {@code java.exe}. */
	private static List<ProcessHandle> toolProcesses() {
		String java = tools.javaExe().toString();
		return ProcessHandle.allProcesses()
				.filter(ProcessHandle::isAlive)
				.filter(p -> p.info().command().map(java::equalsIgnoreCase).orElse(false))
				.toList();
	}
}
