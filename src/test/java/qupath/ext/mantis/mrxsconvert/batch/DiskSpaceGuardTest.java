package qupath.ext.mantis.mrxsconvert.batch;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qupath.ext.mantis.mrxsconvert.batch.DiskSpace.Volume;
import qupath.ext.mantis.mrxsconvert.batch.DiskSpaceGuard.NotEnoughSpace;
import qupath.ext.mantis.mrxsconvert.batch.DiskSpaceGuard.Reservation;
import qupath.ext.mantis.mrxsconvert.settings.Compression;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static qupath.ext.mantis.mrxsconvert.batch.DiskSpaceGuard.HEADROOM;

/** Spec 07 rule 3 space math, with volumes faked by path: {@code T:} is temp, {@code O:} output. */
class DiskSpaceGuardTest {

	private static final long MB = 1_000_000;
	private static final long INPUT = 100 * MB;
	private static final Path TEMP = Path.of("T:\\temp");
	private static final Path OUT = Path.of("O:\\out");
	private static final Path TEMP_VOLUME_OUT = Path.of("T:\\out");

	private final Map<String, Long> free = new HashMap<>();
	private final DiskSpace disk = path -> {
		String root = path.getRoot().toString();
		return new Volume(root, "Disk (" + root.substring(0, 2) + ")", free.getOrDefault(root, 0L));
	};

	private DiskSpaceGuard guard() {
		return new DiskSpaceGuard(disk, TEMP);
	}

	@Test
	void separateVolumesEachNeedTheirShareAndHeadroom() throws Exception {
		// Needs 750 MB + 1 GiB on temp, 550 MB + 1 GiB on output
		free.put("T:\\", 750 * MB + HEADROOM);
		free.put("O:\\", 550 * MB + HEADROOM);
		guard().reserve(INPUT, OUT, Compression.LZW).close();

		free.put("T:\\", 750 * MB + HEADROOM - 1);
		assertThrows(NotEnoughSpace.class, () -> guard().reserve(INPUT, OUT, Compression.LZW));
		free.put("T:\\", 750 * MB + HEADROOM);
		free.put("O:\\", 550 * MB + HEADROOM - 1);
		assertThrows(NotEnoughSpace.class, () -> guard().reserve(INPUT, OUT, Compression.LZW));
	}

	@Test
	void sameVolumeNeedsTheSum() throws Exception {
		free.put("T:\\", 1300 * MB + HEADROOM);
		guard().reserve(INPUT, TEMP_VOLUME_OUT, Compression.LZW).close();
		free.put("T:\\", 1300 * MB + HEADROOM - 1);
		assertThrows(NotEnoughSpace.class, () -> guard().reserve(INPUT, TEMP_VOLUME_OUT, Compression.LZW));
	}

	@Test
	void runningJobsReservationsAreSubtracted() throws Exception {
		free.put("T:\\", 3 * 1300 * MB + HEADROOM);
		DiskSpaceGuard guard = guard();
		Reservation first = guard.reserve(INPUT, TEMP_VOLUME_OUT, Compression.LZW);
		Reservation second = guard.reserve(INPUT, TEMP_VOLUME_OUT, Compression.LZW);
		// Room for a third, but not a third with one more byte of input
		assertThrows(NotEnoughSpace.class, () -> guard.reserve(INPUT + 1, TEMP_VOLUME_OUT, Compression.LZW));
		Reservation third = guard.reserve(INPUT, TEMP_VOLUME_OUT, Compression.LZW);
		third.close();
		second.close();
		first.close();
		guard.reserve(3 * INPUT, TEMP_VOLUME_OUT, Compression.LZW).close();
	}

	@Test
	void messageSaysWhatIsNeededWhatIsFreeAndWhere() {
		free.put("T:\\", 10L << 30);
		free.put("O:\\", 1L << 30);
		var e = assertThrows(NotEnoughSpace.class, () -> guard().reserve(INPUT, OUT, Compression.LZW));
		// 550 MB + 1 GiB = 1.51 GiB
		assertEquals("Not enough disk space: need 1.5 GB, have 1.0 GB on Disk (O:).", e.getMessage());
	}

	@Test
	void outputFactorDependsOnCompression() throws Exception {
		free.put("T:\\", 750 * MB + HEADROOM);
		free.put("O:\\", 200 * MB + HEADROOM);
		guard().reserve(INPUT, OUT, Compression.JPEG).close();
		assertThrows(NotEnoughSpace.class, () -> guard().reserve(INPUT, OUT, Compression.LZW));
		free.put("O:\\", 8000 * MB + HEADROOM - 1);
		assertThrows(NotEnoughSpace.class, () -> guard().reserve(INPUT, OUT, Compression.UNCOMPRESSED));
	}

	@Test
	void recheckReplacesTheEstimateWithTheExactSize() throws Exception {
		free.put("T:\\", 750 * MB + HEADROOM);
		free.put("O:\\", 8000 * MB + HEADROOM);
		DiskSpaceGuard guard = guard();
		Reservation r = guard.reserve(INPUT, OUT, Compression.UNCOMPRESSED);
		// Step 1 done; another job starts and reserves 200 MB on the output volume
		free.put("T:\\", 10_000 * MB);
		free.put("O:\\", 9000 * MB + HEADROOM);
		Reservation other = guard.reserve(INPUT, OUT, Compression.JPEG);
		// Our own 80× estimate no longer counts, the other job's 200 MB does
		assertTrue(r.recheckOutput(9000 * MB).startsWith("Not enough disk space"));
		assertNull(r.recheckOutput(8800 * MB));
		assertNull(r.recheckOutput(5000 * MB));
		other.close();
		r.close();
	}

	@Test
	void inputIsTheSlidePlusItsDataFolder(@TempDir Path temp) throws IOException {
		Path slide = Files.write(temp.resolve("s.mrxs"), new byte[10]);
		Path data = Files.createDirectories(temp.resolve("s").resolve("nested"));
		Files.write(data.resolveSibling("Data0000.dat"), new byte[100]);
		Files.write(data.resolve("x"), new byte[1000]);
		Files.write(temp.resolve("other.dat"), new byte[5]);
		assertEquals(1110, DiskSpaceGuard.inputBytes(slide));
	}

	@Test
	void formatsLikeExplorer() {
		assertEquals("512 bytes", DiskSpaceGuard.formatBytes(512));
		assertEquals("1.5 KB", DiskSpaceGuard.formatBytes(1536));
		assertEquals("2.0 GB", DiskSpaceGuard.formatBytes(2L << 30));
	}

	@Test
	void systemDiskSpaceWorksForFoldersThatDontExistYet(@TempDir Path temp) throws IOException {
		Volume existing = DiskSpace.SYSTEM.volumeOf(temp);
		Volume future = DiskSpace.SYSTEM.volumeOf(temp.resolve("not").resolve("yet"));
		assertEquals(existing.id(), future.id());
		assertTrue(future.usableBytes() > 0);
	}
}
