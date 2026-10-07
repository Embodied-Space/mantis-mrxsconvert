package qupath.ext.mantis.mrxsconvert.payload;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * {@code manifest.json}, written by the build's {@code assemblePayload} task.
 *
 * @param id       16 hex chars derived from every file's path + SHA-256; names the extraction directory
 * @param versions tool and JRE versions, e.g. {@code "jre": "25.0.4.1+1"}
 * @param files    every payload file
 */
record PayloadManifest(String id, Map<String, String> versions, List<Entry> files) {

	record Entry(String path, long size, String sha256) {}

	private static final Pattern ID = Pattern.compile("[0-9a-f]{16}");
	private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

	static PayloadManifest read(PayloadSource source) throws IOException {
		PayloadManifest manifest;
		try (Reader reader = new InputStreamReader(source.open("manifest.json"), StandardCharsets.UTF_8)) {
			manifest = new Gson().fromJson(reader, PayloadManifest.class);
		} catch (JsonParseException e) {
			throw new IOException("Payload manifest is unreadable", e);
		}
		if (manifest == null || manifest.id() == null || !ID.matcher(manifest.id()).matches()
				|| manifest.files() == null || manifest.files().isEmpty())
			throw new IOException("Payload manifest is invalid");
		for (Entry entry : manifest.files()) {
			if (entry == null || entry.path() == null || entry.sha256() == null || !SHA256.matcher(entry.sha256()).matches())
				throw new IOException("Payload manifest has an invalid entry: " + entry);
			resolveInside(Path.of(""), entry.path());
		}
		return manifest;
	}

	/** Resolves a manifest path under {@code dir}, rejecting absolute paths and anything that escapes it. */
	static Path resolveInside(Path dir, String relativePath) throws IOException {
		Path base = dir.toAbsolutePath().normalize();
		Path resolved = base.resolve(relativePath).normalize();
		if (Path.of(relativePath).isAbsolute() || !resolved.startsWith(base) || resolved.equals(base))
			throw new IOException("Unsafe payload path: " + relativePath);
		return resolved;
	}
}
