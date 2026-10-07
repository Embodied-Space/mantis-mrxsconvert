package qupath.ext.mantis.mrxsconvert.batch;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;

/** Free space per volume, for the pre-flight check (D3). Faked in tests. */
@FunctionalInterface
public interface DiskSpace {

	/**
	 * @param id          equal for paths on the same volume
	 * @param name        for messages, e.g. {@code Windows (C:)}
	 * @param usableBytes space this process may still write
	 */
	record Volume(Object id, String name, long usableBytes) {
	}

	/** The volume holding {@code path}, which need not exist yet (e.g. a mirrored output folder). */
	Volume volumeOf(Path path) throws IOException;

	DiskSpace SYSTEM = path -> {
		Path existing = path.toAbsolutePath();
		while (existing != null && !Files.exists(existing))
			existing = existing.getParent();
		if (existing == null)
			throw new NoSuchFileException(path.toString());
		FileStore store = Files.getFileStore(existing);
		// FileStores on Windows are equal when they are the same volume
		return new Volume(store, store.toString(), store.getUsableSpace());
	};
}
