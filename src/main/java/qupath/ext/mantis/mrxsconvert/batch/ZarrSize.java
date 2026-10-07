package qupath.ext.mantis.mrxsconvert.batch;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The size of an uncompressed OME-TIFF written from a Zarr (spec 07 rule 3), from bioformats2raw's Zarr v2
 * {@code .zarray} files: every array of every series (pyramid levels, label, macro), in whole chunks, because the
 * TIFF stores whole tiles even at the edges. An upper bound by a few percent, never an underestimate.
 */
final class ZarrSize {

	private static final Gson GSON = new Gson();
	/** {@code |u1}, {@code <u2}, {@code >f4}: the trailing number is bytes per sample. */
	private static final Pattern DTYPE = Pattern.compile("[<>|]?[a-zA-Z](\\d+)");

	private ZarrSize() {
	}

	/** Fields read from {@code .zarray}. */
	private record ZArray(long[] shape, long[] chunks, String dtype) {
	}

	static long uncompressedTiffBytes(Path zarr) throws IOException {
		List<Path> arrays;
		try (Stream<Path> files = Files.walk(zarr)) {
			arrays = files.filter(p -> p.getFileName().toString().equals(".zarray")).toList();
		}
		if (arrays.isEmpty())
			throw new IOException("No .zarray found in " + zarr);
		long total = 0;
		for (Path file : arrays)
			total = Math.addExact(total, arrayBytes(file));
		return total;
	}

	private static long arrayBytes(Path file) throws IOException {
		ZArray array;
		try {
			array = GSON.fromJson(Files.readString(file), ZArray.class);
		} catch (JsonParseException e) {
			throw new IOException("Could not read " + file, e);
		}
		if (array == null || array.shape() == null || array.chunks() == null || array.dtype() == null
				|| array.shape().length != array.chunks().length)
			throw new IOException("Unexpected contents in " + file);
		Matcher m = DTYPE.matcher(array.dtype());
		if (!m.matches())
			throw new IOException("Unknown dtype " + array.dtype() + " in " + file);
		long bytes = Long.parseLong(m.group(1));
		for (int i = 0; i < array.shape().length; i++) {
			long chunk = array.chunks()[i];
			if (chunk < 1)
				throw new IOException("Bad chunk size in " + file);
			long chunks = (array.shape()[i] + chunk - 1) / chunk;
			bytes = Math.multiplyExact(bytes, Math.multiplyExact(chunks, chunk));
		}
		return bytes;
	}
}
