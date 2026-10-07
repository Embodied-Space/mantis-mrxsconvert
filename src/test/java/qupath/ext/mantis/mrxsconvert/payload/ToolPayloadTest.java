package qupath.ext.mantis.mrxsconvert.payload;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.READ;
import static java.nio.file.StandardOpenOption.WRITE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolPayloadTest {

	@TempDir
	Path temp;

	@AfterEach
	void releaseLocks() throws IOException {
		ToolPayload.releaseInUseLocks();
	}

	private Path source() throws IOException {
		return FakePayload.write(temp.resolve("source"), FakePayload.sampleFiles());
	}

	@Test
	void extractsVerifiedFilesAndReturnsPaths() throws IOException {
		Path tools = temp.resolve("tools");
		ToolPaths paths = new ToolPayload(tools, PayloadSource.directory(source())).extract();

		Path root = tools.resolve(FakePayload.ID);
		assertEquals(root, paths.root());
		assertTrue(Files.isRegularFile(root.resolve(ToolPayload.MARKER)));
		for (var file : FakePayload.sampleFiles().entrySet())
			assertArrayEquals(file.getValue(), Files.readAllBytes(root.resolve(file.getKey())));
		assertEquals(root.resolve("jre\\bin\\java.exe"), paths.javaExe());
		assertEquals(root + "\\common\\*;" + root + "\\b2r\\*", paths.classpathB2R());
		assertEquals(root + "\\common\\*;" + root + "\\r2o\\*", paths.classpathR2O());
		assertTrue(Files.isRegularFile(paths.logbackConfigB2R()));
		assertTrue(Files.isRegularFile(paths.logbackConfigR2O()));
		assertFalse(Files.exists(tools.resolve(FakePayload.ID + ".extracting")));
	}

	@Test
	void secondCallReusesWithoutReadingTheSource() throws IOException {
		Path source = source();
		AtomicInteger opens = new AtomicInteger();
		PayloadSource counting = path -> {
			opens.incrementAndGet();
			return PayloadSource.directory(source).open(path);
		};
		var payload = new ToolPayload(temp.resolve("tools"), counting);
		ToolPaths first = payload.extract();
		int afterFirst = opens.get();
		assertSame(first, payload.extract());
		assertEquals(afterFirst, opens.get());

		// A new instance (e.g. after QuPath restarts) only reads the manifest
		opens.set(0);
		assertEquals(first, new ToolPayload(temp.resolve("tools"), counting).extract());
		assertEquals(1, opens.get());
	}

	@Test
	void reExtractsWhenMarkerIsMissing() throws IOException {
		Path source = source();
		Path tools = temp.resolve("tools");
		ToolPaths paths = new ToolPayload(tools, PayloadSource.directory(source)).extract();
		Path jar = paths.root().resolve("b2r/b2r.jar");
		Files.writeString(jar, "corrupt");
		Files.delete(paths.root().resolve(ToolPayload.MARKER));

		new ToolPayload(tools, PayloadSource.directory(source)).extract();
		assertArrayEquals(FakePayload.bytes("b2r"), Files.readAllBytes(jar));
		assertTrue(Files.isRegularFile(paths.root().resolve(ToolPayload.MARKER)));
	}

	@Test
	void checksumMismatchFailsAndLeavesNothingBehind() throws IOException {
		Path source = source();
		Files.writeString(source.resolve("r2o/r2o.jar"), "tampered");
		Path tools = temp.resolve("tools");

		var e = assertThrows(IOException.class, () -> new ToolPayload(tools, PayloadSource.directory(source)).extract());
		assertTrue(e.getMessage().contains("r2o/r2o.jar"), e.getMessage());
		assertFalse(Files.exists(tools.resolve(FakePayload.ID)));
		assertFalse(Files.exists(tools.resolve(FakePayload.ID + ".extracting")));
	}

	@Test
	void rejectsManifestPathsOutsideThePayload() throws IOException {
		Path source = temp.resolve("source");
		Files.createDirectories(source);
		FakePayload.writeManifest(source, new PayloadManifest(FakePayload.ID, Map.of(),
				List.of(new PayloadManifest.Entry("../escape.txt", 1, "0".repeat(64)))));
		assertThrows(IOException.class, () -> new ToolPayload(temp.resolve("tools"), PayloadSource.directory(source)).extract());
		assertFalse(Files.exists(temp.resolve("escape.txt")));
	}

	@Test
	void deletesUnusedOldPayloadsButKeepsOnesInUse() throws IOException {
		Path tools = temp.resolve("tools");
		Path unused = oldPayload(tools, "aaaaaaaaaaaaaaaa");
		Path unusedStaging = Files.createDirectories(tools.resolve("cccccccccccccccc.extracting"));
		Path inUse = oldPayload(tools, "bbbbbbbbbbbbbbbb");
		Path unrelated = Files.createDirectories(tools.resolve("not-a-payload"));

		try (FileChannel channel = FileChannel.open(tools.resolve("bbbbbbbbbbbbbbbb.inuse"), CREATE, READ, WRITE)) {
			channel.lock(0, Long.MAX_VALUE, true);
			new ToolPayload(tools, PayloadSource.directory(source())).extract();
		}
		assertFalse(Files.exists(unused));
		assertFalse(Files.exists(unusedStaging));
		assertTrue(Files.isRegularFile(inUse.resolve(ToolPayload.MARKER)));
		assertTrue(Files.exists(unrelated));
	}

	private static Path oldPayload(Path tools, String id) throws IOException {
		Path dir = Files.createDirectories(tools.resolve(id).resolve("common"));
		Files.writeString(dir.resolve("old.jar"), "old");
		Files.writeString(tools.resolve(id).resolve(ToolPayload.MARKER), id);
		return tools.resolve(id);
	}

	/** Several JVMs (standing in for QuPath instances) extract at once: one valid dir, no errors. */
	@Test
	void concurrentProcessesShareOneExtraction() throws Exception {
		Map<String, byte[]> files = new HashMap<>(FakePayload.sampleFiles());
		Random random = new Random(42);
		for (int i = 0; i < 30; i++) {
			byte[] data = new byte[1 << 20];
			random.nextBytes(data);
			files.put("common/big" + i + ".jar", data);
		}
		Path source = FakePayload.write(temp.resolve("source"), files);
		Path tools = temp.resolve("tools");

		// The test classpath can exceed Windows' command-line limit, so pass it in an argfile
		Path argFile = temp.resolve("cp.args");
		String classpath = System.getProperty("java.class.path").replace('\\', '/');
		Files.writeString(argFile, "-cp \"" + classpath + "\"", StandardCharsets.UTF_8);
		String java = ProcessHandle.current().info().command().orElseThrow();

		List<Process> processes = new ArrayList<>();
		for (int i = 0; i < 4; i++) {
			processes.add(new ProcessBuilder(java, "@" + argFile, ExtractInChildProcess.class.getName(),
					tools.toString(), source.toString())
					.redirectErrorStream(true)
					.redirectOutput(temp.resolve("child" + i + ".log").toFile())
					.start());
		}
		for (int i = 0; i < processes.size(); i++) {
			Process p = processes.get(i);
			assertTrue(p.waitFor(60, TimeUnit.SECONDS), "child " + i + " timed out");
			String log = Files.readString(temp.resolve("child" + i + ".log"));
			assertEquals(0, p.exitValue(), log);
			assertTrue(log.contains(tools.resolve(FakePayload.ID).toString()), log);
		}

		Path root = tools.resolve(FakePayload.ID);
		for (var file : files.entrySet())
			assertArrayEquals(file.getValue(), Files.readAllBytes(root.resolve(file.getKey())), file.getKey());
		try (Stream<Path> entries = Files.list(tools)) {
			List<String> dirs = entries.filter(Files::isDirectory).map(p -> p.getFileName().toString()).toList();
			assertEquals(List.of(FakePayload.ID), dirs);
		}
	}

	@Test
	void defaultToolsDirIsUnderLocalAppData() {
		Path dir = ToolPayload.defaultToolsDir();
		assertTrue(dir.endsWith(Path.of("mantis-mrxsconvert", "tools")), dir.toString());
		assertTrue(dir.toString().contains("AppData" + File.separator + "Local"), dir.toString());
	}
}
