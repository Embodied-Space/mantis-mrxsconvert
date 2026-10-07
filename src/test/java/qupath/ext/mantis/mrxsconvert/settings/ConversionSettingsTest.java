package qupath.ext.mantis.mrxsconvert.settings;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConversionSettingsTest {

	private static final ConversionSettings D = ConversionSettings.DEFAULTS;

	@Test
	void defaultsProduceNoOptionalArgs() {
		assertTrue(D.jvmOptions().isEmpty());
		assertTrue(D.bioformats2rawArgs().isEmpty());
		assertTrue(D.raw2ometiffArgs().isEmpty());
	}

	@Test
	void settingsThatAreNotToolArgsProduceNone() {
		var s = new ConversionSettings(8, null, Compression.LZW, null, 1024, 1024, null, 4, Path.of("C:\\out"));
		assertTrue(s.jvmOptions().isEmpty());
		assertTrue(s.bioformats2rawArgs().isEmpty());
		assertTrue(s.raw2ometiffArgs().isEmpty());
	}

	@Test
	void heapGoesToBothJvms() {
		var s = new ConversionSettings(1, 2048, Compression.LZW, null, 1024, 1024, null, 4, null);
		assertEquals(List.of("-Xmx2048m"), s.jvmOptions());
		assertTrue(s.bioformats2rawArgs().isEmpty());
		assertTrue(s.raw2ometiffArgs().isEmpty());
	}

	@Test
	void tileSizeAndResolutionsGoToBioformats2rawOnly() {
		var s = new ConversionSettings(1, null, Compression.LZW, null, 512, 256, 5, 4, null);
		assertEquals(List.of("--tile-width", "512", "--tile-height", "256", "--resolutions", "5"), s.bioformats2rawArgs());
		assertTrue(s.raw2ometiffArgs().isEmpty());
	}

	@Test
	void maxWorkersGoesToBothTools() {
		var s = new ConversionSettings(1, null, Compression.LZW, null, 1024, 1024, null, 8, null);
		assertEquals(List.of("--max_workers", "8"), s.bioformats2rawArgs());
		assertEquals(List.of("--max_workers", "8"), s.raw2ometiffArgs());
	}

	@Test
	void compressionAndQualityGoToRaw2ometiffOnly() {
		var jpeg = new ConversionSettings(1, null, Compression.JPEG, 0.8, 1024, 1024, null, 4, null);
		assertEquals(List.of("--compression", "JPEG", "--quality", "0.8"), jpeg.raw2ometiffArgs());
		assertTrue(jpeg.bioformats2rawArgs().isEmpty());

		var uncompressed = new ConversionSettings(1, null, Compression.UNCOMPRESSED, null, 1024, 1024, null, 4, null);
		assertEquals(List.of("--compression", "UNCOMPRESSED"), uncompressed.raw2ometiffArgs());
	}

	@Test
	void qualityIsOnlyUsedWithJpeg() {
		var s = new ConversionSettings(1, null, Compression.LZW, 0.8, 1024, 1024, null, 4, null);
		assertTrue(s.raw2ometiffArgs().isEmpty());
	}

	@Test
	void rejectsInvalidValues() {
		assertThrows(IllegalArgumentException.class, () -> new ConversionSettings(0, null, Compression.LZW, null, 1024, 1024, null, 4, null));
		assertThrows(IllegalArgumentException.class, () -> new ConversionSettings(1, 128, Compression.LZW, null, 1024, 1024, null, 4, null));
		assertThrows(IllegalArgumentException.class, () -> new ConversionSettings(1, null, null, null, 1024, 1024, null, 4, null));
		assertThrows(IllegalArgumentException.class, () -> new ConversionSettings(1, null, Compression.JPEG, 1.5, 1024, 1024, null, 4, null));
		assertThrows(IllegalArgumentException.class, () -> new ConversionSettings(1, null, Compression.LZW, null, 0, 1024, null, 4, null));
		assertThrows(IllegalArgumentException.class, () -> new ConversionSettings(1, null, Compression.LZW, null, 1024, 1024, 0, 4, null));
		assertThrows(IllegalArgumentException.class, () -> new ConversionSettings(1, null, Compression.LZW, null, 1024, 1024, null, 0, null));
	}
}
