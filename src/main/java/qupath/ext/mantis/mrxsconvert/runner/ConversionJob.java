package qupath.ext.mantis.mrxsconvert.runner;

import java.nio.file.Path;
import java.util.Objects;

/**
 * One conversion. Paths are made absolute, because the tools run with the temp workspace as working directory.
 *
 * @param input   the {@code .mrxs} file (its data folder sits beside it)
 * @param output  the final {@code .ome.tiff}; the tool writes {@code <output>.partial} first (D5)
 * @param logFile the job's log (D9); appended to
 */
public record ConversionJob(Path input, Path output, Path logFile) {

	public ConversionJob {
		input = Objects.requireNonNull(input, "input").toAbsolutePath().normalize();
		output = Objects.requireNonNull(output, "output").toAbsolutePath().normalize();
		logFile = Objects.requireNonNull(logFile, "logFile").toAbsolutePath().normalize();
	}

	/** {@code <output>.partial}, e.g. {@code slide.ome.tiff.partial}. */
	public Path partialOutput() {
		return output.resolveSibling(output.getFileName() + ".partial");
	}
}
