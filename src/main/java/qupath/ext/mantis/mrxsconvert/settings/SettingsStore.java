package qupath.ext.mantis.mrxsconvert.settings;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.gui.prefs.PathPrefs;

import javafx.beans.property.StringProperty;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Saves the Advanced settings and last output folder in QuPath's preferences (spec 06), under keys
 * {@code mantisSlideconvert.*}. Load on dialog open, save on Start. A missing key means the default; an invalid
 * stored value (e.g. a heap larger than this machine's RAM) falls back to the default with a log warning.
 */
public final class SettingsStore {

	private static final Logger logger = LoggerFactory.getLogger(SettingsStore.class);

	static final String PREFIX = "mantisSlideconvert.";
	static final String PARALLEL_FILES = PREFIX + "parallelFiles";
	static final String HEAP_MB = PREFIX + "heapMb";
	static final String COMPRESSION = PREFIX + "compression";
	static final String COMPRESSION_QUALITY = PREFIX + "compressionQuality";
	static final String TILE_WIDTH = PREFIX + "tileWidth";
	static final String TILE_HEIGHT = PREFIX + "tileHeight";
	static final String RESOLUTIONS = PREFIX + "resolutions";
	static final String MAX_WORKERS = PREFIX + "maxWorkers";
	static final String LAST_OUTPUT_FOLDER = PREFIX + "lastOutputFolder";

	/** Where values live; null means not stored. */
	interface Storage {
		String get(String key);

		/** @param value null removes the value */
		void put(String key, String value);

		void flush();
	}

	private final Storage storage;
	private final MachineLimits limits;

	/** @param storage tests pass an in-memory one */
	SettingsStore(Storage storage, MachineLimits limits) {
		this.storage = storage;
		this.limits = limits;
	}

	/** The store in QuPath's user preferences. Use it on the JavaFX thread. */
	public static SettingsStore qupath() {
		return new SettingsStore(QuPathStorage.INSTANCE, MachineLimits.current());
	}

	public ConversionSettings load() {
		ConversionSettings d = ConversionSettings.DEFAULTS;
		return new ConversionSettings(
				read(PARALLEL_FILES, d.parallelFiles(), Integer::valueOf, v -> v >= 1 && v <= limits.maxParallelFiles()),
				read(HEAP_MB, d.heapMb(), Integer::valueOf,
						v -> v >= ConversionSettings.MIN_HEAP_MB && v <= limits.maxHeapMb()),
				read(COMPRESSION, d.compression(), Compression::valueOf, v -> true),
				read(COMPRESSION_QUALITY, d.compressionQuality(), Double::valueOf, v -> v > 0 && v <= 1),
				read(TILE_WIDTH, d.tileWidth(), Integer::valueOf, v -> v >= 1),
				read(TILE_HEIGHT, d.tileHeight(), Integer::valueOf, v -> v >= 1),
				read(RESOLUTIONS, d.resolutions(), Integer::valueOf, v -> v >= 1),
				read(MAX_WORKERS, d.maxWorkers(), Integer::valueOf, v -> v >= 1),
				read(LAST_OUTPUT_FOLDER, d.lastOutputFolder(), Path::of, Path::isAbsolute));
	}

	/** Null values (Auto, next to source) are stored as absent keys. */
	public void save(ConversionSettings s) {
		write(PARALLEL_FILES, s.parallelFiles());
		write(HEAP_MB, s.heapMb());
		write(COMPRESSION, s.compression().name());
		write(COMPRESSION_QUALITY, s.compressionQuality());
		write(TILE_WIDTH, s.tileWidth());
		write(TILE_HEIGHT, s.tileHeight());
		write(RESOLUTIONS, s.resolutions());
		write(MAX_WORKERS, s.maxWorkers());
		write(LAST_OUTPUT_FOLDER, s.lastOutputFolder());
		storage.flush();
	}

	/** Forgets every stored value, so {@link #load()} returns {@link ConversionSettings#DEFAULTS}. */
	public void reset() {
		for (String key : new String[]{PARALLEL_FILES, HEAP_MB, COMPRESSION, COMPRESSION_QUALITY, TILE_WIDTH,
				TILE_HEIGHT, RESOLUTIONS, MAX_WORKERS, LAST_OUTPUT_FOLDER})
			storage.put(key, null);
		storage.flush();
	}

	private <T> T read(String key, T fallback, Function<String, T> parse, Predicate<T> valid) {
		String stored = storage.get(key);
		if (stored == null)
			return fallback;
		try {
			T value = parse.apply(stored.strip());
			if (valid.test(value))
				return value;
		} catch (RuntimeException e) {
			// Unparsable: fall through
		}
		logger.warn("Ignoring invalid stored setting {}={}; using the default ({})", key, stored,
				fallback == null ? "auto" : fallback);
		return fallback;
	}

	private void write(String key, Object value) {
		storage.put(key, value == null ? null : value.toString());
	}

	/** QuPath's persistent preferences, one string property per key; an empty string means not stored. */
	private enum QuPathStorage implements Storage {
		INSTANCE;

		// Created once per key: each persistent property writes the preference when it changes
		private final Map<String, StringProperty> properties = new ConcurrentHashMap<>();

		private StringProperty property(String key) {
			return properties.computeIfAbsent(key, k -> PathPrefs.createPersistentPreference(k, ""));
		}

		@Override
		public String get(String key) {
			String value = property(key).get();
			return value == null || value.isEmpty() ? null : value;
		}

		@Override
		public void put(String key, String value) {
			property(key).set(value == null ? "" : value);
		}

		@Override
		public void flush() {
			if (!PathPrefs.savePreferences())
				logger.warn("Could not save conversion settings; QuPath saves preferences again when it exits");
		}
	}
}
