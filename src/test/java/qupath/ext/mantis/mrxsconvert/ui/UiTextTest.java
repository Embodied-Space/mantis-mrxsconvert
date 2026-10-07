package qupath.ext.mantis.mrxsconvert.ui;

import org.junit.jupiter.api.Test;
import qupath.ext.mantis.mrxsconvert.batch.BatchReport;
import qupath.ext.mantis.mrxsconvert.batch.BatchReport.JobOutcome;
import qupath.ext.mantis.mrxsconvert.batch.JobState;
import qupath.ext.mantis.mrxsconvert.plan.Plan;
import qupath.ext.mantis.mrxsconvert.plan.PlannedJob;
import qupath.ext.mantis.mrxsconvert.plan.PlannedJob.Status;
import qupath.ext.mantis.mrxsconvert.runner.ProgressListener.Phase;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.ResourceBundle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UiTextTest {

	private static final Path IN = Path.of("C:\\scans\\a.mrxs");
	private static final Path OUT = Path.of("C:\\scans\\a.ome.tiff");

	private static PlannedJob job(Status status) {
		return new PlannedJob(IN, OUT, status, status == Status.INVALID ? "The slide's data folder is missing" : null);
	}

	@Test
	void everyUiStringFormats() {
		// Catches unbalanced quotes or braces in strings.properties
		var bundle = ResourceBundle.getBundle("qupath.ext.mantis.mrxsconvert.strings");
		for (String key : bundle.keySet())
			if (key.startsWith("ui."))
				assertFalse(UiText.get(key, 2, "x", "y", "z").contains("''"), key);
	}

	@Test
	void apostrophesAreSingle() {
		assertEquals("Nothing to convert: every slide is already converted or can't be converted.",
				UiText.startHint(new Plan(List.of(job(Status.SKIP_EXISTS)), List.of()), false, false, false));
	}

	@Test
	void plannedStatusNamesTheReason() {
		assertEquals("Ready", UiText.plannedStatus(job(Status.READY)));
		assertEquals("Will skip: already converted", UiText.plannedStatus(job(Status.SKIP_EXISTS)));
		assertTrue(UiText.plannedStatus(job(Status.INVALID)).contains("data folder is missing"));
	}

	@Test
	void runningStateNamesTheStep() {
		assertTrue(UiText.state(new JobState.Running(Phase.EXTRACTING, 0)).startsWith("Step 1 of 2"));
		assertTrue(UiText.state(new JobState.Running(Phase.WRITING, 0.5)).startsWith("Step 2 of 2"));
		assertEquals("Waiting", UiText.state(JobState.QUEUED));
	}

	@Test
	void startHintExplainsWhyStartIsDisabled() {
		var ready = new Plan(List.of(job(Status.READY), job(Status.READY)), List.of());
		var clash = new Plan(List.of(job(Status.READY)), List.of(new Plan.Problem("clash", List.of(IN))));
		assertEquals("Add slides or folders to begin.", UiText.startHint(null, true, false, false));
		assertEquals("Checking slides…", UiText.startHint(ready, false, true, false));
		assertTrue(UiText.startHint(ready, false, false, true).startsWith("Choose an output folder"));
		assertEquals("Fix the problems above to start.", UiText.startHint(clash, false, false, false));
		assertEquals("2 slides will be converted.", UiText.startHint(ready, false, false, false));
		assertEquals("1 slide will be converted.",
				UiText.startHint(new Plan(List.of(job(Status.READY)), List.of()), false, false, false));
	}

	@Test
	void summaryCountsEachOutcome() {
		var report = new BatchReport(List.of(
				new JobOutcome(IN, OUT, new JobState.Succeeded(null), null, Duration.ZERO),
				new JobOutcome(IN, OUT, new JobState.Succeeded(null), null, Duration.ZERO),
				new JobOutcome(IN, OUT, new JobState.Skipped(), null, Duration.ZERO),
				new JobOutcome(IN, OUT, new JobState.Failed("x", null), null, Duration.ZERO),
				new JobOutcome(IN, OUT, JobState.CANCELLED, null, Duration.ZERO)), List.of());
		assertEquals("2 converted, 1 skipped, 1 failed, 1 cancelled.", UiText.summary(report));
	}
}
