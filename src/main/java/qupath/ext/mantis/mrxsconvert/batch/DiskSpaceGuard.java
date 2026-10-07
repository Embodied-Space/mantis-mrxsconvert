package qupath.ext.mantis.mrxsconvert.batch;

import qupath.ext.mantis.mrxsconvert.batch.DiskSpace.Volume;
import qupath.ext.mantis.mrxsconvert.settings.Compression;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.stream.Stream;

/**
 * Pre-flight disk-space check (D3, spec 07 rule 3). Each running job reserves what it may need; a new job must fit
 * in what's free minus the others' reservations, and leave {@link #HEADROOM} on every volume.
 * The factors are calibrated in spec 07; change them there first.
 */
final class DiskSpaceGuard {

	private static final ResourceBundle resources = ResourceBundle.getBundle("qupath.ext.mantis.mrxsconvert.strings");

	/** Zarr in temp: max observed 5.89× the input, +~25 %. */
	static final double TEMP_FACTOR = 7.5;
	/** LZW TIFF: max observed 4.41×, +~25 %. */
	static final double OUTPUT_FACTOR = 5.5;
	/** JPEG TIFF: max observed 1.47×. */
	static final double OUTPUT_FACTOR_JPEG = 2.0;
	/** Uncompressed: a cheap first gate only; the exact need is checked after step 1 ({@link ZarrSize}). */
	static final double OUTPUT_FACTOR_UNCOMPRESSED = 80;
	/** Never fill a drive to zero. */
	static final long HEADROOM = 1L << 30;

	private final DiskSpace disk;
	private final Path tempRoot;
	private final List<Reservation> active = new ArrayList<>();

	DiskSpaceGuard(DiskSpace disk, Path tempRoot) {
		this.disk = disk;
		this.tempRoot = tempRoot;
	}

	static double outputFactor(Compression compression) {
		return switch (compression) {
			case LZW -> OUTPUT_FACTOR;
			case JPEG -> OUTPUT_FACTOR_JPEG;
			case UNCOMPRESSED -> OUTPUT_FACTOR_UNCOMPRESSED;
		};
	}

	/**
	 * Reserves space for one job until the reservation is closed.
	 *
	 * @param inputBytes the slide's size ({@link #inputBytes})
	 * @throws NotEnoughSpace with the user-facing reason, and the figures behind it for the log
	 */
	Reservation reserve(long inputBytes, Path outputDir, Compression compression) throws IOException, NotEnoughSpace {
		Volume temp = disk.volumeOf(tempRoot);
		Volume output = disk.volumeOf(outputDir);
		var reservation = new Reservation(outputDir, temp.id(), (long) Math.ceil(TEMP_FACTOR * inputBytes),
				output.id(), (long) Math.ceil(outputFactor(compression) * inputBytes));
		synchronized (this) {
			// Same volume: Zarr and TIFF coexist during step 2, so the needs add up
			Map<Object, Long> needs = new LinkedHashMap<>();
			needs.merge(temp.id(), reservation.tempBytes, Long::sum);
			needs.merge(output.id(), reservation.outputBytes, Long::sum);
			List<String> details = new ArrayList<>();
			details.add("Input size " + formatBytes(inputBytes) + " (slide and its data folder)");
			if (output.id().equals(temp.id())) {
				details.add(describe("Temp folder " + tempRoot + " and output folder " + outputDir, output,
						needs.get(output.id()), null));
			} else {
				details.add(describe("Temp folder " + tempRoot, temp, needs.get(temp.id()), null));
				details.add(describe("Output folder " + outputDir, output, needs.get(output.id()), null));
			}
			check(temp, needs.get(temp.id()), null, details);
			if (!output.id().equals(temp.id()))
				check(output, needs.get(output.id()), null, details);
			active.add(reservation);
		}
		return reservation;
	}

	private void check(Volume volume, long need, Reservation exclude, List<String> details) throws NotEnoughSpace {
		long available = volume.usableBytes() - reservedOn(volume.id(), exclude);
		if (available < need + HEADROOM)
			throw new NotEnoughSpace(MessageFormat.format(resources.getString("batch.failed.space"),
					formatBytes(need + HEADROOM), formatBytes(Math.max(0, available)), volume.name()), details);
	}

	/** One log line: what a volume must hold and what it has. */
	private String describe(String what, Volume volume, long need, Reservation exclude) {
		return String.format(Locale.ROOT, "%s on %s: needs %s + %s headroom; %s free, %s of it reserved by running "
						+ "conversions", what, volume.name(), formatBytes(need), formatBytes(HEADROOM),
				formatBytes(volume.usableBytes()), formatBytes(reservedOn(volume.id(), exclude)));
	}

	private long reservedOn(Object volumeId, Reservation exclude) {
		long sum = 0;
		for (Reservation r : active) {
			if (r == exclude)
				continue;
			if (r.tempVolume.equals(volumeId))
				sum += r.tempBytes;
			if (r.outputVolume.equals(volumeId))
				sum += r.outputBytes;
		}
		return sum;
	}

	/** The {@code .mrxs} plus its data folder. */
	static long inputBytes(Path slide) throws IOException {
		long total = Files.size(slide);
		String name = slide.getFileName().toString();
		Path data = slide.resolveSibling(name.substring(0, name.lastIndexOf('.')));
		if (Files.isDirectory(data)) {
			try (Stream<Path> files = Files.walk(data)) {
				for (Path p : files.toList()) {
					BasicFileAttributes attrs = Files.readAttributes(p, BasicFileAttributes.class);
					if (attrs.isRegularFile())
						total += attrs.size();
				}
			}
		}
		return total;
	}

	/** Binary units with Windows Explorer's labels, e.g. {@code 1.5 GB}. */
	static String formatBytes(long bytes) {
		String[] units = {"bytes", "KB", "MB", "GB", "TB"};
		double value = bytes;
		int unit = 0;
		while (value >= 1024 && unit < units.length - 1) {
			value /= 1024;
			unit++;
		}
		return unit == 0 ? bytes + " bytes" : String.format(Locale.ROOT, "%.1f %s", value, units[unit]);
	}

	/** A job's claim on disk space. */
	final class Reservation implements AutoCloseable {

		private final Path outputDir;
		private final Object tempVolume;
		private final long tempBytes;
		private final Object outputVolume;
		private long outputBytes;

		private Reservation(Path outputDir, Object tempVolume, long tempBytes, Object outputVolume, long outputBytes) {
			this.outputDir = outputDir;
			this.tempVolume = tempVolume;
			this.tempBytes = tempBytes;
			this.outputVolume = outputVolume;
			this.outputBytes = outputBytes;
		}

		/**
		 * Re-checks the output volume once the exact output size is known. The Zarr is on disk by now, so free space
		 * already accounts for it, and only other jobs' reservations are subtracted.
		 *
		 * @return null if it fits (the reservation becomes the exact size), else the user-facing reason
		 */
		String recheckOutput(long exactOutputBytes) throws IOException {
			Volume output = disk.volumeOf(outputDir);
			synchronized (DiskSpaceGuard.this) {
				try {
					check(output, exactOutputBytes, this, List.of());
				} catch (NotEnoughSpace e) {
					return e.getMessage();
				}
				outputBytes = exactOutputBytes;
				return null;
			}
		}

		@Override
		public void close() {
			synchronized (DiskSpaceGuard.this) {
				active.remove(this);
			}
		}
	}

	static final class NotEnoughSpace extends Exception {

		private final List<String> details;

		NotEnoughSpace(String reason, List<String> details) {
			super(reason, null, false, false);
			this.details = List.copyOf(details);
		}

		/** The figures behind the refusal, for the job's log. */
		List<String> details() {
			return details;
		}
	}
}
