package qupath.ext.mantis.mrxsconvert.batch;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ZarrSizeTest {

	@TempDir
	Path temp;

	private void zarray(String dir, String shape, String chunks, String dtype) throws IOException {
		Path d = Files.createDirectories(temp.resolve(dir));
		Files.writeString(d.resolve(".zarray"), """
				{"zarr_format": 2, "shape": %s, "chunks": %s, "dtype": "%s", "fill_value": 0,
				 "compressor": {"id": "blosc"}, "dimension_separator": "/"}""".formatted(shape, chunks, dtype));
	}

	@Test
	void sumsEveryArrayInWholeTiles() throws IOException {
		// As written by bioformats2raw 0.12.1 (TCZYX): a full-resolution level, a small level and a 16-bit label
		zarray("0/0", "[1, 4, 1, 2048, 3000]", "[1, 1, 1, 1024, 1024]", "|u1");
		zarray("0/1", "[1, 4, 1, 100, 137]", "[1, 1, 1, 1024, 1024]", "|u1");
		zarray("1/0", "[1, 3, 1, 429, 549]", "[1, 1, 1, 1024, 1024]", "<u2");
		Files.writeString(temp.resolve("0").resolve(".zattrs"), "{}");

		long tile = 1024 * 1024;
		assertEquals(4 * 2 * 3 * tile + 4 * tile + 3 * tile * 2, ZarrSize.uncompressedTiffBytes(temp));
	}

	@Test
	void noArraysIsAnError() {
		assertThrows(IOException.class, () -> ZarrSize.uncompressedTiffBytes(temp));
	}

	@Test
	void unknownDtypeIsAnError() throws IOException {
		zarray("0/0", "[10]", "[10]", "object");
		assertThrows(IOException.class, () -> ZarrSize.uncompressedTiffBytes(temp));
	}

	@Test
	void referenceZarrMatchesTheMeasuredUncompressedTiff() throws IOException {
		// Spike 01: APOL1 uncompressed TIFF was 1.3 GB; this Zarr is from the same slide
		Path zarr = Path.of("reference", "outputs", "4colortest");
		assumeTrue(Files.isDirectory(zarr), "reference/ outputs not present");
		long bytes = ZarrSize.uncompressedTiffBytes(zarr);
		assertTrue(bytes >= 1_300_000_000L && bytes < 1_500_000_000L, Long.toString(bytes));
	}
}
