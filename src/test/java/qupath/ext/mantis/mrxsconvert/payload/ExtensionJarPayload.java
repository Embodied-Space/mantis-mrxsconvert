package qupath.ext.mantis.mrxsconvert.payload;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;

/** For tests in other packages: the real payload, extracted from the {@code -all} jar that Gradle builds before tests. */
public final class ExtensionJarPayload {

	private ExtensionJarPayload() {}

	/**
	 * Extracts the payload to {@code toolsDir}, or reuses an earlier extraction there.
	 *
	 * @return the tools, or null when not run through Gradle (no {@code mrxsconvert.extensionJar})
	 */
	public static ToolPaths extract(Path toolsDir) throws IOException, ClassNotFoundException {
		String jar = System.getProperty("mrxsconvert.extensionJar");
		if (jar == null)
			return null;
		try (var loader = new URLClassLoader(new URL[]{Path.of(jar).toUri().toURL()}, null)) {
			Class<?> anchor = loader.loadClass(ToolPaths.class.getName());
			return new ToolPayload(toolsDir, PayloadSource.classpath(anchor, ToolPayload.RESOURCE_ROOT)).extract();
		}
	}

	/** Lets go of the payload's in-use locks. */
	public static void release() throws IOException {
		ToolPayload.releaseInUseLocks();
	}
}
