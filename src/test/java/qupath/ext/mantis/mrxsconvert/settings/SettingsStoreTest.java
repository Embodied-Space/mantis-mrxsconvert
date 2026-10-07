package qupath.ext.mantis.mrxsconvert.settings;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettingsStoreTest {

	private static final MachineLimits LIMITS = new MachineLimits(8, 16L * 1024 * 1024 * 1024);
	private static final ConversionSettings CHANGED = new ConversionSettings(3, 4096, Compression.JPEG, 0.75,
			512, 256, 5, 2, Path.of("E:\\out folder"));

	private final MemoryStorage prefs = new MemoryStorage();

	@Test
	void emptyPreferencesGiveDefaults() {
		assertEquals(ConversionSettings.DEFAULTS, new SettingsStore(prefs, LIMITS).load());
	}

	@Test
	void valuesSurviveARestart() {
		new SettingsStore(prefs, LIMITS).save(CHANGED);
		// A new store on the same preferences, as after restarting QuPath
		assertEquals(CHANGED, new SettingsStore(prefs, LIMITS).load());
		assertTrue(prefs.flushed);
	}

	@Test
	void autoAndNextToSourceAreStoredAsAbsentKeys() {
		var store = new SettingsStore(prefs, LIMITS);
		store.save(CHANGED);
		store.save(ConversionSettings.DEFAULTS);
		assertEquals(ConversionSettings.DEFAULTS, store.load());
		assertTrue(!prefs.values.containsKey(SettingsStore.HEAP_MB)
				&& !prefs.values.containsKey(SettingsStore.LAST_OUTPUT_FOLDER), prefs.values.toString());
	}

	@Test
	void resetRestoresDefaults() {
		var store = new SettingsStore(prefs, LIMITS);
		store.save(CHANGED);
		store.reset();
		assertEquals(ConversionSettings.DEFAULTS, store.load());
		assertEquals(Map.of(), prefs.values);
	}

	@Test
	void invalidValuesFallBackPerField() {
		var store = new SettingsStore(prefs, LIMITS);
		store.save(CHANGED);
		prefs.put(SettingsStore.HEAP_MB, "999999");          // more than this machine's RAM
		prefs.put(SettingsStore.PARALLEL_FILES, "9");        // more than its cores
		prefs.put(SettingsStore.COMPRESSION, "JPEG2000");    // removed option (E6)
		prefs.put(SettingsStore.TILE_WIDTH, "wide");
		prefs.put(SettingsStore.COMPRESSION_QUALITY, "1.5");
		prefs.put(SettingsStore.LAST_OUTPUT_FOLDER, "relative");

		ConversionSettings d = ConversionSettings.DEFAULTS;
		assertEquals(new ConversionSettings(d.parallelFiles(), d.heapMb(), d.compression(), d.compressionQuality(),
				d.tileWidth(), CHANGED.tileHeight(), CHANGED.resolutions(), CHANGED.maxWorkers(),
				d.lastOutputFolder()), store.load());
	}

	@Test
	void heapLimitIsTheMachinesRam() {
		var small = new MachineLimits(2, 1024L * 1024 * 1024);
		assertEquals(1024, small.maxHeapMb());
		prefs.put(SettingsStore.HEAP_MB, "1024");
		assertEquals(1024, new SettingsStore(prefs, small).load().heapMb());
		prefs.put(SettingsStore.HEAP_MB, "1025");
		assertEquals(null, new SettingsStore(prefs, small).load().heapMb());
	}

	@Test
	void unknownRamDoesNotLimitTheHeap() {
		assertEquals(Integer.MAX_VALUE, new MachineLimits(4, 0).maxHeapMb());
	}

	@Test
	void ramIsReadTheSameWayWithoutJdkManagement() throws Throwable {
		// QuPath's runtime lacks com.sun.management, so the Windows call is what runs there
		long expected = MachineLimits.mxBeanPhysicalMemory();
		assertTrue(expected > 0);
		assertEquals(expected, MachineLimits.windowsPhysicalMemory());
		assertEquals(expected, MachineLimits.current().physicalMemoryBytes());
	}

	/** Storage in memory. */
	private static final class MemoryStorage implements SettingsStore.Storage {

		final Map<String, String> values = new HashMap<>();
		boolean flushed;

		@Override
		public String get(String key) {
			return values.get(key);
		}

		@Override
		public void put(String key, String value) {
			if (value == null)
				values.remove(key);
			else
				values.put(key, value);
		}

		@Override
		public void flush() {
			flushed = true;
		}
	}
}
