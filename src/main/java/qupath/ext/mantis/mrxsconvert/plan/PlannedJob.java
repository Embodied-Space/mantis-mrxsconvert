package qupath.ext.mantis.mrxsconvert.plan;

import java.nio.file.Path;

/**
 * One slide and where its output goes.
 *
 * @param input  the {@code .mrxs} file, absolute
 * @param output the {@code .ome.tiff} to write, absolute; null when the input is {@link Status#INVALID} and no output
 *               can be named
 * @param status whether it will run
 * @param reason a short, user-facing explanation when {@link Status#INVALID}; otherwise null
 */
public record PlannedJob(Path input, Path output, Status status, String reason) {

	public enum Status {
		/** Will be converted. */
		READY,
		/** The output already exists, so the slide is skipped (B4). */
		SKIP_EXISTS,
		/** Can't be converted, e.g. its data folder is missing (D8). */
		INVALID
	}
}
