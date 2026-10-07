package qupath.ext.mantis.mrxsconvert.runner;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qupath.ext.mantis.mrxsconvert.payload.ToolPaths;
import qupath.ext.mantis.mrxsconvert.runner.ConversionResult.Status;
import qupath.ext.mantis.mrxsconvert.settings.Compression;
import qupath.ext.mantis.mrxsconvert.settings.ConversionSettings;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Command lines and pre-checks; {@link ConversionRunnerIntegrationTest} runs the real tools. */
class ConversionRunnerTest {

	private static final ToolPaths TOOLS = new ToolPaths(Path.of("C:\\t"), Path.of("C:\\t\\jre\\bin\\java.exe"),
			"C:\\t\\common\\*;C:\\t\\b2r\\*", "C:\\t\\common\\*;C:\\t\\r2o\\*",
			Path.of("C:\\t\\b2r\\config\\logback.xml"), Path.of("C:\\t\\r2o\\config\\logback.xml"));
	private static final Path WORKSPACE = Path.of("C:\\Temp\\mantis-mrxsconvert\\w");
	private static final ConversionJob JOB = new ConversionJob(Path.of("D:\\scans\\My slide 1.2 A.mrxs"),
			Path.of("D:\\scans\\My slide 1.2 A.ome.tiff"), Path.of("C:\\logs\\job.log"));

	@TempDir
	Path temp;

	@Test
	void defaultBioformats2rawCommandHasNoOptionalArgs() {
		assertEquals(List.of("C:\\t\\jre\\bin\\java.exe",
						"--add-opens", "java.base/java.lang=ALL-UNNAMED",
						"--enable-native-access=ALL-UNNAMED", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8",
						"-Djava.io.tmpdir=C:\\Temp\\mantis-mrxsconvert\\w",
						"-Dlogback.configurationFile=C:\\t\\b2r\\config\\logback.xml",
						"-cp", "C:\\t\\common\\*;C:\\t\\b2r\\*", "com.glencoesoftware.bioformats2raw.Converter",
						"D:\\scans\\My slide 1.2 A.mrxs", "C:\\Temp\\mantis-mrxsconvert\\w\\pyramid"),
				ConversionRunner.bioformats2rawCommand(JOB, ConversionSettings.DEFAULTS, TOOLS, WORKSPACE));
	}

	@Test
	void defaultRaw2ometiffCommandWritesThePartialFile() {
		assertEquals(List.of("C:\\t\\jre\\bin\\java.exe",
						"--enable-native-access=ALL-UNNAMED", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8",
						"-Djava.io.tmpdir=C:\\Temp\\mantis-mrxsconvert\\w",
						"-Dlogback.configurationFile=C:\\t\\r2o\\config\\logback.xml",
						"-cp", "C:\\t\\common\\*;C:\\t\\r2o\\*", "com.glencoesoftware.pyramid.PyramidFromDirectoryWriter",
						"C:\\Temp\\mantis-mrxsconvert\\w\\pyramid", "D:\\scans\\My slide 1.2 A.ome.tiff.partial"),
				ConversionRunner.raw2ometiffCommand(JOB, ConversionSettings.DEFAULTS, TOOLS, WORKSPACE));
	}

	@Test
	void changedSettingsAddTheirArgs() {
		var s = new ConversionSettings(1, 512, Compression.JPEG, 0.9, 512, 512, null, 2, null);
		List<String> b2r = ConversionRunner.bioformats2rawCommand(JOB, s, TOOLS, WORKSPACE);
		List<String> r2o = ConversionRunner.raw2ometiffCommand(JOB, s, TOOLS, WORKSPACE);
		assertEquals("-Xmx512m", b2r.get(1));
		assertEquals("-Xmx512m", r2o.get(1));
		assertEquals(List.of("--tile-width", "512", "--tile-height", "512", "--max_workers", "2"),
				b2r.subList(b2r.size() - 6, b2r.size()));
		assertEquals(List.of("--compression", "JPEG", "--quality", "0.9", "--max_workers", "2"),
				r2o.subList(r2o.size() - 6, r2o.size()));
	}

	@Test
	void environmentIsScrubbed() {
		var env = new HashMap<>(new ProcessBuilder().environment());
		for (String name : ConversionRunner.SCRUBBED_ENV)
			env.put(name, "x");
		env.put("PATH", "C:\\Windows");
		ConversionRunner.scrubEnvironment(env);
		for (String name : ConversionRunner.SCRUBBED_ENV)
			assertFalse(env.containsKey(name), name);
		assertEquals("C:\\Windows", env.get("PATH"));
	}

	@Test
	void environmentIsScrubbedIgnoringCase() {
		var env = new ProcessBuilder().environment();
		env.put("java_tool_options", "-Dx=1");
		ConversionRunner.scrubEnvironment(env);
		assertFalse(env.containsKey("JAVA_TOOL_OPTIONS"));
	}

	@Test
	void jobPathsAreAbsolute() {
		var job = new ConversionJob(Path.of("a.mrxs"), Path.of("a.ome.tiff"), Path.of("a.log"));
		assertTrue(job.input().isAbsolute());
		assertTrue(job.output().isAbsolute());
		assertTrue(job.logFile().isAbsolute());
		assertEquals("a.ome.tiff.partial", job.partialOutput().getFileName().toString());
	}

	@Test
	void quotesArgsWithSpacesForTheLog() {
		assertEquals("java -cp \"C:\\my dir\\*\" Main", ConversionRunner.quote(List.of("java", "-cp", "C:\\my dir\\*", "Main")));
	}

	@Test
	void existingOutputFailsBeforeAnyWork() throws IOException {
		Path output = Files.writeString(temp.resolve("slide.ome.tiff"), "keep");
		var job = new ConversionJob(temp.resolve("slide.mrxs"), output, temp.resolve("logs/slide.log"));
		var result = new ConversionRunner(temp.resolve("work"))
				.convert(job, ConversionSettings.DEFAULTS, TOOLS, ProgressListener.NONE, new CancellationToken());
		assertEquals(Status.FAILED, result.status());
		assertTrue(result.reason().contains(output.toString()), result.reason());
		assertEquals("keep", Files.readString(output));
		assertFalse(Files.exists(temp.resolve("work")));
		assertTrue(Files.readString(job.logFile()).contains(result.reason()));
	}

	@Test
	void leftoverPartialFromACrashIsDeleted() throws IOException {
		var job = new ConversionJob(temp.resolve("slide.mrxs"), temp.resolve("slide.ome.tiff"), temp.resolve("slide.log"));
		Files.writeString(job.partialOutput(), "left by a crash");
		// Fails later, at starting the (missing) tools
		var result = convert(job);
		assertTrue(result.reason().startsWith("Could not start"), result.reason());
		assertFalse(Files.exists(job.partialOutput()));
		assertFalse(Files.exists(OutputLock.lockFile(job)));
		assertTrue(Files.readString(job.logFile()).contains("left by an earlier conversion"));
	}

	/** B (2026-10-07): a .partial whose lock another QuPath holds is being written; leave it alone. */
	@Test
	void partialBeingWrittenByAnotherProcessIsLeftAlone() throws Exception {
		Path output = temp.resolve("out dir/slide.ome.tiff");
		var job = new ConversionJob(temp.resolve("slide.mrxs"), output, temp.resolve("slide.log"));
		Files.createDirectories(output.getParent());
		Files.writeString(job.partialOutput(), "being written");

		Path argFile = temp.resolve("cp.args");
		Files.writeString(argFile, "-cp \"" + System.getProperty("java.class.path").replace('\\', '/') + "\"",
				StandardCharsets.UTF_8);
		Process child = new ProcessBuilder(ProcessHandle.current().info().command().orElseThrow(), "@" + argFile,
				HoldOutputLockInChildProcess.class.getName(), output.toString())
				.redirectError(ProcessBuilder.Redirect.DISCARD).start();
		try {
			var out = new BufferedReader(new InputStreamReader(child.getInputStream(), StandardCharsets.UTF_8));
			assertEquals("locked", out.readLine());

			var result = convert(job);
			assertEquals(Status.FAILED, result.status());
			assertTrue(result.reason().startsWith("Another conversion"), result.reason());
			assertEquals("being written", Files.readString(job.partialOutput()));
			assertFalse(Files.exists(temp.resolve("work")));

			// That QuPath crashes: the next attempt cleans up
			child.destroyForcibly();
			assertTrue(child.waitFor(30, TimeUnit.SECONDS));
			convert(job);
			assertFalse(Files.exists(job.partialOutput()));
		} finally {
			child.destroyForcibly();
		}
	}

	@Test
	void sameOutputTwiceInThisJvmIsBusy() throws IOException {
		var job = new ConversionJob(temp.resolve("slide.mrxs"), temp.resolve("slide.ome.tiff"), temp.resolve("slide.log"));
		try (OutputLock held = OutputLock.tryAcquire(job)) {
			assertTrue(held != null);
			assertEquals(null, OutputLock.tryAcquire(job));
			assertTrue(convert(job).reason().startsWith("Another conversion"));
		}
		assertFalse(Files.exists(OutputLock.lockFile(job)));
	}

	private ConversionResult convert(ConversionJob job) {
		return new ConversionRunner(temp.resolve("work"))
				.convert(job, ConversionSettings.DEFAULTS, TOOLS, ProgressListener.NONE, new CancellationToken());
	}

	@Test
	void cancelledBeforeStartDoesNothing() throws IOException {
		var job = new ConversionJob(temp.resolve("slide.mrxs"), temp.resolve("slide.ome.tiff"), temp.resolve("slide.log"));
		var cancel = new CancellationToken();
		cancel.cancel();
		var result = new ConversionRunner(temp.resolve("work"))
				.convert(job, ConversionSettings.DEFAULTS, TOOLS, ProgressListener.NONE, cancel);
		assertEquals(Status.CANCELLED, result.status());
		assertFalse(Files.exists(temp.resolve("work")));
	}

	@Test
	void missingJavaFailsAndCleansUp() throws IOException {
		var job = new ConversionJob(temp.resolve("slide.mrxs"), temp.resolve("slide.ome.tiff"), temp.resolve("slide.log"));
		var result = new ConversionRunner(temp.resolve("work"))
				.convert(job, ConversionSettings.DEFAULTS, TOOLS, ProgressListener.NONE, new CancellationToken());
		assertEquals(Status.FAILED, result.status());
		try (var left = Files.list(temp.resolve("work"))) {
			assertEquals(0, left.count());
		}
	}
}
