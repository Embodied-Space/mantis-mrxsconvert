package qupath.ext.mantis.mrxsconvert.payload;

import com.google.gson.Gson;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Writes a small payload tree + manifest.json in the layout the build produces. */
final class FakePayload {

	static final String ID = "0123456789abcdef";

	private FakePayload() {}

	static Path write(Path dir, Map<String, byte[]> files) throws IOException {
		List<PayloadManifest.Entry> entries = new ArrayList<>();
		for (var file : new TreeMap<>(files).entrySet()) {
			Path p = dir.resolve(file.getKey());
			Files.createDirectories(p.getParent());
			Files.write(p, file.getValue());
			entries.add(new PayloadManifest.Entry(file.getKey(), file.getValue().length, sha256(file.getValue())));
		}
		writeManifest(dir, new PayloadManifest(ID, Map.of("jre", "test"), entries));
		return dir;
	}

	static void writeManifest(Path dir, PayloadManifest manifest) throws IOException {
		Files.writeString(dir.resolve("manifest.json"), new Gson().toJson(manifest));
	}

	static Map<String, byte[]> sampleFiles() {
		return Map.of(
				"jre/bin/java.exe", bytes("java"),
				"common/shared.jar", bytes("shared"),
				"b2r/b2r.jar", bytes("b2r"),
				"b2r/config/logback.xml", bytes("<configuration/>"),
				"r2o/r2o.jar", bytes("r2o"),
				"r2o/config/logback.xml", bytes("<configuration/>"),
				"LICENSES/NOTICE.txt", bytes("notice"));
	}

	static byte[] bytes(String s) {
		return s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
	}

	static String sha256(byte[] data) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}
}
