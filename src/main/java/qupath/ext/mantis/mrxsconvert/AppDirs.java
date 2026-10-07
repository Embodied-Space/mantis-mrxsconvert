package qupath.ext.mantis.mrxsconvert;

import java.nio.file.Path;

/** The extension's own folder, {@code %LOCALAPPDATA%\mantis-mrxsconvert} (E4). */
public final class AppDirs {

	private AppDirs() {
	}

	public static Path root() {
		String localAppData = System.getenv("LOCALAPPDATA");
		Path base = localAppData == null || localAppData.isBlank()
				? Path.of(System.getProperty("user.home"), "AppData", "Local")
				: Path.of(localAppData);
		return base.resolve("mantis-mrxsconvert");
	}

	/** One log file per conversion (D9). */
	public static Path logs() {
		return root().resolve("logs");
	}
}
