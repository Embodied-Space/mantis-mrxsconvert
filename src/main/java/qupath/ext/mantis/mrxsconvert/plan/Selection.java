package qupath.ext.mantis.mrxsconvert.plan;

import java.nio.file.Path;
import java.util.Objects;

/** Something the user added to the dialog: one slide, or a folder scanned recursively (B2). */
public sealed interface Selection {

	Path path();

	/** A file the user picked. */
	record File(Path path) implements Selection {
		public File {
			path = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
		}
	}

	/** A folder the user picked; every {@code .mrxs} below it is converted. */
	record Folder(Path path) implements Selection {
		public Folder {
			path = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
		}
	}
}
