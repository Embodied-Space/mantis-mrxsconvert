package qupath.ext.mantis.mrxsconvert.plan;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.mantis.mrxsconvert.plan.Plan.Problem;
import qupath.ext.mantis.mrxsconvert.plan.PlannedJob.Status;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.ResourceBundle;
import java.util.stream.Collectors;

/**
 * Turns the user's selections into jobs (spec 04): finds the slides, checks each has its data folder, names the
 * outputs and finds clashes. Reads the file system, so call it off the JavaFX thread for large folders.
 */
public final class InputPlanner {

	private static final Logger logger = LoggerFactory.getLogger(InputPlanner.class);
	private static final ResourceBundle resources = ResourceBundle.getBundle("qupath.ext.mantis.mrxsconvert.strings");

	static final String MRXS = ".mrxs";
	static final String OME_TIFF = ".ome.tiff";
	/** The file inside a slide's data folder that shows it is one (D8). */
	static final String SLIDEDAT = "Slidedat.ini";

	private InputPlanner() {
	}

	/**
	 * @param selections     in the order the user added them; a slide found by several selections is planned once,
	 *                       with the output given by the first
	 * @param outputOverride the output folder chosen by the user, or empty to write next to each slide (B3)
	 */
	public static Plan plan(List<Selection> selections, Optional<Path> outputOverride) {
		Path override = outputOverride.map(p -> p.toAbsolutePath().normalize()).orElse(null);
		// Keyed by the slide's real path, so different spellings of one file are one job
		Map<Path, PlannedJob> jobs = new LinkedHashMap<>();
		List<Problem> problems = new ArrayList<>();
		for (Selection selection : selections) {
			switch (selection) {
				case Selection.File file -> {
					if (!isIgnored(file.path()))
						addFile(file.path(), override, jobs);
				}
				case Selection.Folder folder -> {
					if (!Files.isDirectory(folder.path())) {
						problems.add(new Problem(message("plan.problem.folder-missing", folder.path()),
								List.of(folder.path())));
						continue;
					}
					for (Path slide : findSlides(folder.path()))
						jobs.putIfAbsent(key(slide), plan(slide, outputDir(folder.path(), slide, override)));
				}
			}
		}
		List<PlannedJob> planned = List.copyOf(jobs.values());
		problems.addAll(findClashes(planned));
		return new Plan(planned, problems);
	}

	private static void addFile(Path file, Path override, Map<Path, PlannedJob> jobs) {
		Path key = key(file);
		if (jobs.containsKey(key))
			return;
		if (!Files.isRegularFile(file))
			jobs.put(key, new PlannedJob(file, null, Status.INVALID, message("plan.invalid.not-found", file)));
		else if (!isMrxs(file))
			jobs.put(key, new PlannedJob(file, null, Status.INVALID, message("plan.invalid.not-mrxs", file)));
		else
			jobs.put(key, plan(file, override == null ? file.getParent() : override));
	}

	/** Flat under the override for picked files; mirrored, including the folder's own name, for folders (E1). */
	private static Path outputDir(Path root, Path slide, Path override) {
		if (override == null)
			return slide.getParent();
		Path rel = root.relativize(slide.getParent());
		// A drive root has no name to mirror
		Path base = root.getFileName() == null ? override : override.resolve(root.getFileName().toString());
		return base.resolve(rel).normalize();
	}

	private static PlannedJob plan(Path slide, Path outputDir) {
		Path output = outputDir.resolve(stem(slide) + OME_TIFF);
		Path dataFolder = slide.resolveSibling(stem(slide));
		if (!Files.isDirectory(dataFolder))
			return new PlannedJob(slide, output, Status.INVALID, message("plan.invalid.no-data-folder", dataFolder));
		if (!Files.isRegularFile(dataFolder.resolve(SLIDEDAT)))
			return new PlannedJob(slide, output, Status.INVALID,
					message("plan.invalid.incomplete-data-folder", dataFolder, SLIDEDAT));
		// A leftover .partial doesn't count (B4): the runner deletes it if no one is writing it
		if (Files.exists(output))
			return new PlannedJob(slide, output, Status.SKIP_EXISTS, null);
		return new PlannedJob(slide, output, Status.READY, null);
	}

	/** Recursive walk (B2), sorted so the list is stable. Unreadable sub-folders are logged and skipped. */
	static List<Path> findSlides(Path root) {
		List<Path> slides = new ArrayList<>();
		try {
			Files.walkFileTree(root, new SimpleFileVisitor<>() {
				@Override
				public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
					return isMacFolder(dir) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
				}

				@Override
				public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
					if (attrs.isRegularFile() && isMrxs(file) && !isIgnored(file))
						slides.add(file.toAbsolutePath().normalize());
					return FileVisitResult.CONTINUE;
				}

				@Override
				public FileVisitResult visitFileFailed(Path file, IOException e) {
					logger.warn("Could not read {}; skipping it", file, e);
					return FileVisitResult.CONTINUE;
				}
			});
		} catch (IOException e) {
			logger.warn("Could not scan {}", root, e);
		}
		slides.sort(null);
		return slides;
	}

	/** Two jobs writing one file block Start (D2). Invalid jobs write nothing, so they can't clash. */
	private static List<Problem> findClashes(List<PlannedJob> jobs) {
		Map<String, List<PlannedJob>> byOutput = jobs.stream()
				.filter(j -> j.status() != Status.INVALID)
				// Windows paths ignore case
				.collect(Collectors.groupingBy(j -> j.output().toString().toLowerCase(Locale.ROOT),
						LinkedHashMap::new, Collectors.toList()));
		List<Problem> problems = new ArrayList<>();
		for (List<PlannedJob> group : byOutput.values()) {
			if (group.size() < 2)
				continue;
			List<Path> inputs = group.stream().map(PlannedJob::input).toList();
			String names = inputs.stream().map(Path::toString).collect(Collectors.joining("\n"));
			problems.add(new Problem(message("plan.problem.clash", group.getFirst().output(), names), inputs));
		}
		return problems;
	}

	static boolean isMrxs(Path file) {
		return file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(MRXS);
	}

	/** macOS leftovers (E5): AppleDouble {@code ._*} files, {@code .DS_Store}, anything in {@code __MACOSX}. */
	static boolean isIgnored(Path file) {
		String name = file.getFileName() == null ? "" : file.getFileName().toString();
		if (name.startsWith("._") || name.equalsIgnoreCase(".DS_Store"))
			return true;
		for (Path part : file)
			if (isMacFolder(part))
				return true;
		return false;
	}

	private static boolean isMacFolder(Path dir) {
		return dir.getFileName() != null && dir.getFileName().toString().equalsIgnoreCase("__MACOSX");
	}

	/** The name without {@code .mrxs}: {@code x.mrxs} → {@code x}. */
	static String stem(Path slide) {
		String name = slide.getFileName().toString();
		return isMrxs(slide) ? name.substring(0, name.length() - MRXS.length()) : name;
	}

	private static Path key(Path file) {
		try {
			return file.toRealPath();
		} catch (IOException e) {
			return file.toAbsolutePath().normalize();
		}
	}

	private static String message(String key, Object... args) {
		return MessageFormat.format(resources.getString(key), args);
	}
}
