package qupath.ext.mantis.mrxsconvert.payload;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Spec 02 acceptance on the real {@code -all} jar (built by Gradle before tests), read through its own class loader
 * as QuPath would: the payload extracts, and the bundled JRE and both tools run with no {@code java} on
 * {@code PATH} and no {@code JAVA_HOME}.
 */
class ToolPayloadIntegrationTest {

	@TempDir
	static Path tools;

	private static URLClassLoader extensionJar;
	private static PayloadManifest manifest;
	private static ToolPaths paths;

	@BeforeAll
	static void extract() throws Exception {
		String jar = System.getProperty("mrxsconvert.extensionJar");
		assumeTrue(jar != null, "mrxsconvert.extensionJar not set; run through Gradle");
		// No parent: only what is inside the jar is visible
		extensionJar = new URLClassLoader(new URL[]{Path.of(jar).toUri().toURL()}, null);
		Class<?> anchor = extensionJar.loadClass(ToolPaths.class.getName());
		var source = PayloadSource.classpath(anchor, ToolPayload.RESOURCE_ROOT);
		manifest = PayloadManifest.read(source);
		paths = new ToolPayload(tools, source).extract();
	}

	@AfterAll
	static void release() throws IOException {
		ToolPayload.releaseInUseLocks();
		if (extensionJar != null)
			extensionJar.close();
	}

	/** A4: the tools' classes must never be loadable from the extension jar itself. */
	@Test
	void toolClassesAreNotOnTheExtensionClasspath() {
		assertNotNull(extensionJar.getResource("META-INF/services/qupath.lib.gui.extensions.QuPathExtension"));
		for (String cls : List.of("loci/formats/ImageReader.class", "com/glencoesoftware/bioformats2raw/Converter.class",
				"com/glencoesoftware/pyramid/PyramidFromDirectoryWriter.class", "picocli/CommandLine.class"))
			assertNull(extensionJar.getResource(cls), cls);
	}

	@Test
	void javaReportsThePinnedVersion() throws Exception {
		String out = run(paths.javaExe().toString(), "-version");
		assertTrue(out.contains(manifest.versions().get("jre")), out);
	}

	@Test
	void bioformats2rawRuns() throws Exception {
		// bioformats2raw exits non-zero after --version, so check what it prints
		String out = run(paths.javaExe().toString(), "--add-opens", "java.base/java.lang=ALL-UNNAMED",
				"--enable-native-access=ALL-UNNAMED", "-cp", paths.classpathB2R(),
				"com.glencoesoftware.bioformats2raw.Converter", "--version");
		assertTrue(out.contains("Version = " + manifest.versions().get("bioformats2raw")), out);
		assertTrue(out.contains("Bio-Formats version = 8.5.0"), out);
	}

	@Test
	void raw2ometiffRuns() throws Exception {
		String out = run(paths.javaExe().toString(), "--enable-native-access=ALL-UNNAMED", "-cp", paths.classpathR2O(),
				"com.glencoesoftware.pyramid.PyramidFromDirectoryWriter", "--version");
		assertTrue(out.contains("Version = " + manifest.versions().get("raw2ometiff")), out);
		assertTrue(out.contains("Bio-Formats version = 8.5.0"), out);
	}

	@Test
	void payloadHasLicenses() {
		List<String> files = manifest.files().stream().map(PayloadManifest.Entry::path).toList();
		for (String f : List.of("LICENSES/NOTICE.txt", "LICENSES/bioformats2raw-LICENSE.txt",
				"LICENSES/raw2ometiff-LICENSE.txt", "jre/legal/java.base/LICENSE"))
			assertTrue(files.contains(f), f);
	}

	/** Runs with a bare environment: no JAVA_HOME, no java on PATH, no inherited JVM options. */
	private static String run(String... command) throws Exception {
		var builder = new ProcessBuilder(new ArrayList<>(List.of(command))).redirectErrorStream(true);
		Map<String, String> env = builder.environment();
		for (String name : List.of("JAVA_HOME", "JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS", "CLASSPATH"))
			env.remove(name);
		env.put("PATH", System.getenv("SystemRoot") + "\\System32");
		Process p = builder.start();
		p.getOutputStream().close();
		String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		assertTrue(p.waitFor(60, TimeUnit.SECONDS));
		assertFalse(out.contains("Picked up"), out);
		return out;
	}
}
