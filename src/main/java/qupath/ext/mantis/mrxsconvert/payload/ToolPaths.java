package qupath.ext.mantis.mrxsconvert.payload;

import java.io.File;
import java.nio.file.Path;

/**
 * Locations inside an extracted payload (spec 02). Classpaths use JVM {@code dir\*} wildcards and are passed to
 * {@code java.exe -cp} as a single argument.
 *
 * @param root             the extracted payload directory
 * @param javaExe          the bundled JRE's {@code java.exe}
 * @param classpathB2R     {@code common\*;b2r\*}
 * @param classpathR2O     {@code common\*;r2o\*}
 * @param logbackConfigB2R bioformats2raw's {@code logback.xml}
 * @param logbackConfigR2O raw2ometiff's {@code logback.xml}
 */
public record ToolPaths(Path root, Path javaExe, String classpathB2R, String classpathR2O,
                        Path logbackConfigB2R, Path logbackConfigR2O) {

	static ToolPaths of(Path root) {
		return new ToolPaths(
				root,
				root.resolve("jre").resolve("bin").resolve("java.exe"),
				classpath(root, "b2r"),
				classpath(root, "r2o"),
				root.resolve("b2r").resolve("config").resolve("logback.xml"),
				root.resolve("r2o").resolve("config").resolve("logback.xml"));
	}

	private static String classpath(Path root, String toolDir) {
		return root.resolve("common") + File.separator + "*" + File.pathSeparator
				+ root.resolve(toolDir) + File.separator + "*";
	}
}
