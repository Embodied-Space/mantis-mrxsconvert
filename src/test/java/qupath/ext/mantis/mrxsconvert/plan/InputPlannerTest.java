package qupath.ext.mantis.mrxsconvert.plan;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qupath.ext.mantis.mrxsconvert.plan.PlannedJob.Status;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Spec 04 acceptance, with slides faked as empty files plus a data folder. */
class InputPlannerTest {

	@TempDir
	Path temp;

	/** A slide that passes validation: {@code name.mrxs} plus {@code name/Slidedat.ini}. */
	private Path slide(Path dir, String fileName) throws IOException {
		Files.createDirectories(dir);
		Path slide = Files.createFile(dir.resolve(fileName));
		Path data = Files.createDirectories(dir.resolve(InputPlanner.stem(slide)));
		Files.createFile(data.resolve("Slidedat.ini"));
		return slide;
	}

	private static Plan plan(Optional<Path> override, Selection... selections) {
		return InputPlanner.plan(List.of(selections), override);
	}

	private static List<Path> inputs(Plan plan) {
		return plan.jobs().stream().map(PlannedJob::input).toList();
	}

	@Test
	void findsNestedSlidesAndIgnoresOtherFiles() throws IOException {
		Path root = temp.resolve("Batch7");
		Path a = slide(root, "a.mrxs");
		Path b = slide(root.resolve("sub").resolve("deeper"), "b.MRXS");
		Files.createFile(root.resolve("notes.txt"));
		Files.createFile(root.resolve("sub").resolve("x.ome.tiff"));

		Plan plan = plan(Optional.empty(), new Selection.Folder(root));
		assertEquals(List.of(a, b), inputs(plan));
		assertTrue(plan.jobs().stream().allMatch(j -> j.status() == Status.READY));
		assertEquals(b.resolveSibling("b.ome.tiff"), plan.jobs().get(1).output());
		assertTrue(plan.canStart());
	}

	@Test
	void ignoresMacArtifacts() throws IOException {
		Path root = temp.resolve("scans");
		Path real = slide(root, "slide.mrxs");
		Files.createFile(root.resolve("._slide.mrxs"));
		Files.createFile(root.resolve(".DS_Store"));
		Path inMacFolder = slide(root.resolve("__MACOSX"), "x.mrxs");

		assertEquals(List.of(real), inputs(plan(Optional.empty(), new Selection.Folder(root))));
		// Explicit picks are dropped silently too: no job, not even an invalid one
		Plan picked = plan(Optional.empty(), new Selection.File(root.resolve("._slide.mrxs")),
				new Selection.File(inMacFolder), new Selection.File(root.resolve(".DS_Store")));
		assertEquals(List.of(), picked.jobs());
		assertFalse(picked.canStart());
	}

	@Test
	void referenceScansGiveExactlyTwoJobs() {
		Path scans = Path.of("reference", "original scans");
		assumeTrue(Files.isDirectory(scans), "reference/ samples not present");
		Plan plan = plan(Optional.of(temp), new Selection.Folder(scans));
		assertEquals(2, plan.jobs().size(), plan.toString());
		assertTrue(plan.jobs().stream().allMatch(j -> j.status() == Status.READY), plan.toString());
		assertTrue(plan.jobs().stream().noneMatch(j -> j.input().getFileName().toString().startsWith("._")));
	}

	@Test
	void sameSlideFromFolderAndPickIsOneJob() throws IOException {
		Path root = temp.resolve("scans");
		Path s = slide(root, "s.mrxs");
		Plan plan = plan(Optional.empty(), new Selection.Folder(root), new Selection.File(s),
				new Selection.File(Path.of(s.toString().toUpperCase())));
		assertEquals(List.of(s), inputs(plan));
		assertEquals(List.of(), plan.problems());
	}

	@Test
	void firstSelectionDecidesTheOutput() throws IOException {
		Path root = temp.resolve("Batch7");
		Path s = slide(root.resolve("sub"), "s.mrxs");
		Path out = temp.resolve("out");
		assertEquals(out.resolve("s.ome.tiff"),
				plan(Optional.of(out), new Selection.File(s), new Selection.Folder(root)).jobs().getFirst().output());
		assertEquals(out.resolve("Batch7").resolve("sub").resolve("s.ome.tiff"),
				plan(Optional.of(out), new Selection.Folder(root), new Selection.File(s)).jobs().getFirst().output());
	}

	@Test
	void missingDataFolderIsInvalidWithItsPath() throws IOException {
		Path dir = Files.createDirectories(temp.resolve("scans"));
		Path lonely = Files.createFile(dir.resolve("lonely.mrxs"));
		Path empty = Files.createFile(dir.resolve("empty.mrxs"));
		Files.createDirectory(dir.resolve("empty"));

		Plan plan = plan(Optional.empty(), new Selection.File(lonely), new Selection.File(empty));
		PlannedJob missing = plan.jobs().get(0);
		assertEquals(Status.INVALID, missing.status());
		assertTrue(missing.reason().contains(dir.resolve("lonely").toString()), missing.reason());
		PlannedJob incomplete = plan.jobs().get(1);
		assertEquals(Status.INVALID, incomplete.status());
		assertTrue(incomplete.reason().contains("Slidedat.ini"), incomplete.reason());
		assertFalse(plan.canStart());
	}

	@Test
	void pickedFilesThatAreMissingOrNotSlidesAreInvalid() throws IOException {
		Path txt = Files.createFile(temp.resolve("a.txt"));
		Plan plan = plan(Optional.empty(), new Selection.File(txt), new Selection.File(temp.resolve("gone.mrxs")));
		assertEquals(List.of(Status.INVALID, Status.INVALID), plan.jobs().stream().map(PlannedJob::status).toList());
		assertNull(plan.jobs().getFirst().output());
	}

	@Test
	void overrideMirrorsFoldersIncludingTheirNameAndFlattensPicks() throws IOException {
		Path root = temp.resolve("scans").resolve("Batch7");
		Path nested = slide(root.resolve("sub"), "x.mrxs");
		Path top = slide(root, "y.mrxs");
		Path picked = slide(temp.resolve("elsewhere").resolve("deep"), "z.mrxs");
		Path out = temp.resolve("out");

		Plan plan = plan(Optional.of(out), new Selection.Folder(root), new Selection.File(picked));
		assertEquals(List.of(out.resolve("Batch7").resolve("sub").resolve("x.ome.tiff"),
						out.resolve("Batch7").resolve("y.ome.tiff"),
						out.resolve("z.ome.tiff")),
				plan.jobs().stream().map(PlannedJob::output).toList());
		assertEquals(List.of(nested, top, picked), inputs(plan));
	}

	@Test
	void outputClashBlocksStartAndNamesBothSlides() throws IOException {
		Path a = slide(temp.resolve("one"), "same.mrxs");
		Path b = slide(temp.resolve("two"), "SAME.mrxs");
		Plan plan = plan(Optional.of(temp.resolve("out")), new Selection.File(a), new Selection.File(b));

		assertEquals(1, plan.problems().size());
		Plan.Problem clash = plan.problems().getFirst();
		assertEquals(List.of(a, b), clash.inputs());
		assertTrue(clash.message().contains(a.toString()) && clash.message().contains(b.toString()), clash.message());
		assertFalse(plan.canStart());
	}

	@Test
	void existingOutputIsSkippedButALeftoverPartialIsNot() throws IOException {
		Path done = slide(temp, "done.mrxs");
		Path half = slide(temp, "half.mrxs");
		Files.createFile(temp.resolve("done.ome.tiff"));
		Files.createFile(temp.resolve("half.ome.tiff.partial"));

		Plan plan = plan(Optional.empty(), new Selection.File(done), new Selection.File(half));
		assertEquals(List.of(Status.SKIP_EXISTS, Status.READY), plan.jobs().stream().map(PlannedJob::status).toList());
		assertTrue(plan.canStart());
	}

	@Test
	void onlySkippedJobsCannotStart() throws IOException {
		Path done = slide(temp, "done.mrxs");
		Files.createFile(temp.resolve("done.ome.tiff"));
		assertFalse(plan(Optional.empty(), new Selection.File(done)).canStart());
	}

	@Test
	void missingFolderIsABlockingProblem() {
		Path gone = temp.resolve("gone");
		Plan plan = plan(Optional.empty(), new Selection.Folder(gone));
		assertEquals(List.of(gone), plan.problems().getFirst().inputs());
		assertFalse(plan.canStart());
	}
}
