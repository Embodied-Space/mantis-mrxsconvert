package qupath.ext.mantis.mrxsconvert.batch;

import qupath.ext.mantis.mrxsconvert.payload.ToolPaths;
import qupath.ext.mantis.mrxsconvert.payload.ToolPayload;
import qupath.ext.mantis.mrxsconvert.runner.CancellationToken;
import qupath.ext.mantis.mrxsconvert.runner.ConversionJob;
import qupath.ext.mantis.mrxsconvert.runner.ConversionResult;
import qupath.ext.mantis.mrxsconvert.runner.ConversionRunner;
import qupath.ext.mantis.mrxsconvert.runner.ProgressListener;
import qupath.ext.mantis.mrxsconvert.runner.StepGate;
import qupath.ext.mantis.mrxsconvert.settings.ConversionSettings;

import java.io.IOException;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.time.Duration;
import java.util.List;
import java.util.ResourceBundle;

/** Converts one slide; the batch queue's view of the runner, so tests can fake it. Blocking, never throws. */
@FunctionalInterface
public interface JobConverter {

	ConversionResult convert(ConversionJob job, ConversionSettings settings, ProgressListener progress,
	                         CancellationToken cancel, StepGate gate);

	/** The real runner on the bundled tools, extracted on first use. */
	static JobConverter using(ConversionRunner runner) {
		return (job, settings, progress, cancel, gate) -> {
			ToolPaths tools;
			try {
				tools = ToolPayload.ensureExtracted();
			} catch (IOException e) {
				String reason = MessageFormat.format(ResourceBundle.getBundle("qupath.ext.mantis.mrxsconvert.strings")
						.getString("batch.failed.tools"), e.getMessage());
				Path log = FailureLog.write(job.logFile(), job.input(), job.output(), reason, List.of("Error: " + e));
				return new ConversionResult(ConversionResult.Status.FAILED, reason, Duration.ZERO, log);
			}
			return runner.convert(job, settings, tools, progress, cancel, gate);
		};
	}
}
