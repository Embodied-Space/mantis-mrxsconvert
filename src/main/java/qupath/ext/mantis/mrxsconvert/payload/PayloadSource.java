package qupath.ext.mantis.mrxsconvert.payload;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Where payload files are read from: the extension jar in production, a directory in tests.
 * Paths are relative, '/'-separated, exactly as listed in {@code manifest.json}.
 */
interface PayloadSource {

	InputStream open(String path) throws IOException;

	/** Resources under {@code resourceRoot} (absolute, ending in '/') in the jar that holds {@code anchor}. */
	static PayloadSource classpath(Class<?> anchor, String resourceRoot) {
		return path -> {
			InputStream in = anchor.getResourceAsStream(resourceRoot + path);
			if (in == null)
				throw new FileNotFoundException("Missing from the extension jar: " + resourceRoot + path);
			return in;
		};
	}

	static PayloadSource directory(Path dir) {
		return path -> Files.newInputStream(PayloadManifest.resolveInside(dir, path));
	}
}
