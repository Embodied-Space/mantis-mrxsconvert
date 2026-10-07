package qupath.ext.mantis.mrxsconvert.settings;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The Advanced settings (spec 06, B6) and their mapping to command-line arguments.
 * <p>
 * D1: an argument is emitted only when its value differs from the tool's own default, so {@link #DEFAULTS} produces
 * exactly the manual command lines and the output matches {@code reference/outputs} (C3).
 * Limits that depend on the machine (cores, RAM) are checked where settings are loaded, not here.
 *
 * @param parallelFiles      files converted at once (spec 07); not a tool argument
 * @param heapMb             {@code -Xmx} for both child JVMs in MiB, or null for the JVM default (E3)
 * @param compression        raw2ometiff {@code --compression}
 * @param compressionQuality raw2ometiff {@code --quality}, used with JPEG only; null for the tool default
 * @param tileWidth          bioformats2raw {@code --tile-width}
 * @param tileHeight         bioformats2raw {@code --tile-height}
 * @param resolutions        bioformats2raw {@code --resolutions}, or null for automatic
 * @param maxWorkers         {@code --max_workers} for both tools
 * @param lastOutputFolder   the output-folder override, or null to write next to each source (B3)
 */
public record ConversionSettings(int parallelFiles, Integer heapMb, Compression compression, Double compressionQuality,
                                 int tileWidth, int tileHeight, Integer resolutions, int maxWorkers,
                                 Path lastOutputFolder) {

	/** Below 128 MiB bioformats2raw can crash natively (spike 01); 256 leaves margin. */
	public static final int MIN_HEAP_MB = 256;
	static final int DEFAULT_TILE_SIZE = 1024;
	static final int DEFAULT_MAX_WORKERS = 4;

	public static final ConversionSettings DEFAULTS = new ConversionSettings(1, null, Compression.LZW, null,
			DEFAULT_TILE_SIZE, DEFAULT_TILE_SIZE, null, DEFAULT_MAX_WORKERS, null);

	public ConversionSettings {
		if (parallelFiles < 1)
			throw new IllegalArgumentException("parallelFiles must be at least 1: " + parallelFiles);
		if (heapMb != null && heapMb < MIN_HEAP_MB)
			throw new IllegalArgumentException("heapMb must be at least " + MIN_HEAP_MB + ": " + heapMb);
		if (compression == null)
			throw new IllegalArgumentException("compression is required");
		if (compressionQuality != null && !(compressionQuality > 0 && compressionQuality <= 1))
			throw new IllegalArgumentException("compressionQuality must be in (0, 1]: " + compressionQuality);
		if (tileWidth < 1 || tileHeight < 1)
			throw new IllegalArgumentException("tile size must be positive: " + tileWidth + "x" + tileHeight);
		if (resolutions != null && resolutions < 1)
			throw new IllegalArgumentException("resolutions must be at least 1: " + resolutions);
		if (maxWorkers < 1)
			throw new IllegalArgumentException("maxWorkers must be at least 1: " + maxWorkers);
	}

	/** JVM options for both child JVMs. */
	public List<String> jvmOptions() {
		return heapMb == null ? List.of() : List.of("-Xmx" + heapMb + "m");
	}

	/** Optional bioformats2raw arguments, after the input and output paths. */
	public List<String> bioformats2rawArgs() {
		List<String> args = new ArrayList<>();
		if (tileWidth != DEFAULT_TILE_SIZE)
			args.addAll(List.of("--tile-width", Integer.toString(tileWidth)));
		if (tileHeight != DEFAULT_TILE_SIZE)
			args.addAll(List.of("--tile-height", Integer.toString(tileHeight)));
		if (resolutions != null)
			args.addAll(List.of("--resolutions", Integer.toString(resolutions)));
		if (maxWorkers != DEFAULT_MAX_WORKERS)
			args.addAll(List.of("--max_workers", Integer.toString(maxWorkers)));
		return List.copyOf(args);
	}

	/** Optional raw2ometiff arguments, after the input and output paths. */
	public List<String> raw2ometiffArgs() {
		List<String> args = new ArrayList<>();
		if (compression != Compression.LZW)
			args.addAll(List.of("--compression", compression.cliValue()));
		if (compression == Compression.JPEG && compressionQuality != null)
			args.addAll(List.of("--quality", Double.toString(compressionQuality)));
		if (maxWorkers != DEFAULT_MAX_WORKERS)
			args.addAll(List.of("--max_workers", Integer.toString(maxWorkers)));
		return List.copyOf(args);
	}
}
