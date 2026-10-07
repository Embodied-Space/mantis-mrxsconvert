package qupath.ext.mantis.mrxsconvert.runner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.mantis.mrxsconvert.payload.ToolPaths;
import qupath.ext.mantis.mrxsconvert.runner.ConversionResult.Status;
import qupath.ext.mantis.mrxsconvert.runner.ProgressListener.Phase;
import qupath.ext.mantis.mrxsconvert.settings.ConversionSettings;
import qupath.ext.mantis.mrxsconvert.temp.TempWorkspace;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.InterruptedIOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Converts one {@code .mrxs} to one {@code .ome.tiff} (spec 03): bioformats2raw writes a Zarr into a temp workspace,
 * then raw2ometiff writes {@code <output>.partial}, which is renamed on success. Each tool runs in a child JVM on the
 * bundled JRE (A4). Blocking; run it off the JavaFX thread.
 */
public final class ConversionRunner {

	private static final Logger logger = LoggerFactory.getLogger(ConversionRunner.class);
	private static final ResourceBundle resources = ResourceBundle.getBundle("qupath.ext.mantis.mrxsconvert.strings");

	static final String B2R_MAIN = "com.glencoesoftware.bioformats2raw.Converter";
	static final String R2O_MAIN = "com.glencoesoftware.pyramid.PyramidFromDirectoryWriter";
	static final String ZARR_DIR = "pyramid";
	/** Removed from the child environment so a user's machine settings can't leak into the tools (spike 01). */
	static final List<String> SCRUBBED_ENV = List.of("JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS",
			"JAVA_HOME", "CLASSPATH");
	private static final long POLL_MILLIS = 200;
	private static final long KILL_WAIT_SECONDS = 30;
	private static final String LOG_PREFIX = "[mrxsconvert] ";

	private final Path tempRoot;

	public ConversionRunner() {
		this(TempWorkspace.defaultRoot());
	}

	/** @param tempRoot where workspaces are created; tests use their own */
	public ConversionRunner(Path tempRoot) {
		this.tempRoot = tempRoot;
	}

	/** Never throws for a failed conversion; the result says what happened. */
	public ConversionResult convert(ConversionJob job, ConversionSettings settings, ToolPaths tools,
	                                ProgressListener progress, CancellationToken cancel) {
		return convert(job, settings, tools, progress, cancel, StepGate.NONE);
	}

	/** As above, with {@code gate} consulted between the two steps. */
	public ConversionResult convert(ConversionJob job, ConversionSettings settings, ToolPaths tools,
	                                ProgressListener progress, CancellationToken cancel, StepGate gate) {
		long start = System.nanoTime();
		JobLog log;
		try {
			log = JobLog.open(job.logFile());
		} catch (IOException e) {
			logger.warn("Could not open log file {}", job.logFile(), e);
			return result(Status.FAILED, message("runner.failed.log", job.logFile()), start, job);
		}
		try (log) {
			log.note("Converting " + job.input());
			log.note("Output " + job.output());
			log.note("Settings " + settings);
			ConversionResult result;
			try {
				run(job, settings, tools, progress, cancel, gate, log);
				result = result(Status.SUCCEEDED, null, start, job);
			} catch (Failure f) {
				result = result(Status.FAILED, f.getMessage(), start, job);
			} catch (Cancelled c) {
				result = result(Status.CANCELLED, null, start, job);
			} catch (IOException | RuntimeException e) {
				logger.warn("Conversion of {} failed", job.input(), e);
				log.note("Error: " + e);
				result = result(Status.FAILED, message("runner.failed.io", e.getMessage()), start, job);
			}
			log.note("Result " + result.status() + (result.reason() == null ? "" : ": " + result.reason())
					+ " after " + formatDuration(result.duration()));
			return result;
		}
	}

	private void run(ConversionJob job, ConversionSettings settings, ToolPaths tools, ProgressListener progress,
	                 CancellationToken cancel, StepGate gate, JobLog log) throws Failure, Cancelled, IOException {
		Path partial = job.partialOutput();
		// Fail fast; the rename re-checks the output (B4)
		if (Files.exists(job.output()))
			throw new Failure(message("runner.failed.output-exists", job.output()));
		checkCancelled(cancel);
		// Mirrored output trees (E1) may not exist yet; the lock file goes there too
		Files.createDirectories(partial.getParent());

		OutputLock outputLock = OutputLock.tryAcquire(job);
		if (outputLock == null)
			throw new Failure(message("runner.failed.busy", job.output()));
		try (outputLock) {
			// Nobody holds the lock, so a .partial is a leftover from a crashed conversion
			if (Files.exists(partial)) {
				log.note("Deleting unfinished output left by an earlier conversion: " + partial);
				if (!TempWorkspace.deleteWithRetries(partial))
					throw new Failure(message("runner.failed.stale-partial", partial));
			}
			TempWorkspace workspace;
			try {
				workspace = TempWorkspace.create(tempRoot);
			} catch (IOException e) {
				throw new Failure(message("runner.failed.workspace", e.getMessage()));
			}
			try {
				runSteps(job, settings, tools, workspace.dir(), progress, cancel, gate, log);
			} finally {
				workspace.close();
				// Still under the output lock, so any .partial is this run's
				if (Files.exists(partial) && !TempWorkspace.deleteWithRetries(partial)) {
					logger.warn("Could not delete unfinished output {}", partial);
					log.note("Could not delete unfinished output " + partial);
				}
			}
		}
	}

	private static void runSteps(ConversionJob job, ConversionSettings settings, ToolPaths tools, Path workspace,
	                             ProgressListener progress, CancellationToken cancel, StepGate gate, JobLog log)
			throws Failure, Cancelled, IOException {
		log.note("Workspace " + workspace);
		progress.update(Phase.EXTRACTING, 0);
		runTool(bioformats2rawCommand(job, settings, tools, workspace), workspace, log, cancel, "runner.failed.extract");
		checkCancelled(cancel);
		String refusal = gate.beforeWriting(workspace.resolve(ZARR_DIR));
		if (refusal != null) {
			log.note("Not writing the OME-TIFF: " + refusal);
			throw new Failure(refusal);
		}

		progress.update(Phase.WRITING, 0.5);
		runTool(raw2ometiffCommand(job, settings, tools, workspace), workspace, log, cancel, "runner.failed.write");
		checkCancelled(cancel);

		if (!Files.isRegularFile(job.partialOutput()))
			throw new Failure(message("runner.failed.no-output"));
		moveIntoPlace(job.partialOutput(), job.output());
		progress.update(Phase.WRITING, 1);
	}

	static List<String> bioformats2rawCommand(ConversionJob job, ConversionSettings settings, ToolPaths tools,
	                                          Path workspace) {
		List<String> command = new ArrayList<>();
		command.add(tools.javaExe().toString());
		command.addAll(settings.jvmOptions());
		command.addAll(List.of("--add-opens", "java.base/java.lang=ALL-UNNAMED"));
		command.addAll(commonJvmOptions(workspace, tools.logbackConfigB2R()));
		command.addAll(List.of("-cp", tools.classpathB2R(), B2R_MAIN,
				job.input().toString(), workspace.resolve(ZARR_DIR).toString()));
		command.addAll(settings.bioformats2rawArgs());
		return command;
	}

	static List<String> raw2ometiffCommand(ConversionJob job, ConversionSettings settings, ToolPaths tools,
	                                       Path workspace) {
		List<String> command = new ArrayList<>();
		command.add(tools.javaExe().toString());
		command.addAll(settings.jvmOptions());
		command.addAll(commonJvmOptions(workspace, tools.logbackConfigR2O()));
		command.addAll(List.of("-cp", tools.classpathR2O(), R2O_MAIN,
				workspace.resolve(ZARR_DIR).toString(), job.partialOutput().toString()));
		command.addAll(settings.raw2ometiffArgs());
		return command;
	}

	/** JVM flags only, never tool arguments, so D1 holds. */
	private static List<String> commonJvmOptions(Path workspace, Path logbackConfig) {
		return List.of(
				// Silences Java 25's restricted-method warnings (OpenCV, blosc)
				"--enable-native-access=ALL-UNNAMED",
				// So non-ASCII paths in tool output reach the log intact
				"-Dstdout.encoding=UTF-8",
				"-Dstderr.encoding=UTF-8",
				"-Djava.io.tmpdir=" + workspace,
				"-Dlogback.configurationFile=" + logbackConfig);
	}

	static void scrubEnvironment(Map<String, String> environment) {
		// On Windows this map ignores case, as the OS does
		SCRUBBED_ENV.forEach(environment::remove);
	}

	/** Runs a tool to completion. The working dir is the workspace, so a crash's hs_err file is cleaned up with it. */
	private static void runTool(List<String> command, Path workDir, JobLog log, CancellationToken cancel,
	                            String failureKey) throws Failure, Cancelled {
		checkCancelled(cancel);
		log.note("Command " + quote(command));
		var builder = new ProcessBuilder(command).directory(workDir.toFile()).redirectErrorStream(true);
		scrubEnvironment(builder.environment());
		long start = System.nanoTime();
		Process process;
		try {
			process = builder.start();
		} catch (IOException e) {
			log.note("Could not start: " + e);
			throw new Failure(message("runner.failed.start", e.getMessage()));
		}
		Thread pump = Thread.ofPlatform().daemon().name("mrxsconvert-tool-output")
				.start(() -> copyLines(process.getInputStream(), log));
		try {
			process.getOutputStream().close();
		} catch (IOException e) {
			logger.debug("Could not close the tool's stdin", e);
		}
		boolean cancelled = false;
		try {
			while (!process.waitFor(POLL_MILLIS, TimeUnit.MILLISECONDS)) {
				if (cancel.isCancelled()) {
					cancelled = true;
					break;
				}
			}
		} catch (InterruptedException e) {
			// e.g. the batch's executor shutting down: treat as cancel
			Thread.currentThread().interrupt();
			cancelled = true;
		} finally {
			if (cancelled || process.isAlive())
				killTree(process);
			joinQuietly(pump);
		}
		String elapsed = formatDuration(Duration.ofNanos(System.nanoTime() - start));
		if (cancelled) {
			log.note("Cancelled; stopped the tool after " + elapsed);
			throw new Cancelled();
		}
		int exit = process.exitValue();
		log.note("Exit code " + exit + " after " + elapsed);
		// Any non-zero exit is a failure: too little heap can crash the JVM natively instead of throwing (spike 01)
		if (exit != 0)
			throw new Failure(message(failureKey, Integer.toString(exit)));
	}

	/** Kills the process and everything it started (D4), then waits for them to exit. */
	static void killTree(Process process) {
		List<ProcessHandle> descendants = process.descendants().toList();
		descendants.forEach(ProcessHandle::destroyForcibly);
		process.destroyForcibly();
		boolean interrupted = Thread.interrupted();
		try {
			if (!process.waitFor(KILL_WAIT_SECONDS, TimeUnit.SECONDS))
				logger.warn("Conversion tool (pid {}) did not exit after being killed", process.pid());
			for (ProcessHandle handle : descendants)
				handle.onExit().get(KILL_WAIT_SECONDS, TimeUnit.SECONDS);
		} catch (Exception e) {
			logger.warn("Could not confirm that the conversion tool exited", e);
		} finally {
			if (interrupted)
				Thread.currentThread().interrupt();
		}
	}

	private static void copyLines(InputStream in, JobLog log) {
		try (var reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
			String line;
			while ((line = reader.readLine()) != null)
				log.toolLine(line);
		} catch (IOException e) {
			// The stream closes when the process is killed
			logger.debug("Tool output ended", e);
		}
	}

	private static void joinQuietly(Thread thread) {
		boolean interrupted = Thread.interrupted();
		try {
			thread.join(TimeUnit.SECONDS.toMillis(KILL_WAIT_SECONDS));
		} catch (InterruptedException e) {
			interrupted = true;
		} finally {
			if (interrupted)
				Thread.currentThread().interrupt();
		}
	}

	/**
	 * Renames without replacing (B4). A plain move on one volume is an atomic rename; {@code ATOMIC_MOVE} is avoided
	 * because on Windows it replaces an existing target.
	 */
	private static void moveIntoPlace(Path partial, Path output) throws Failure, IOException {
		for (int attempt = 1; ; attempt++) {
			try {
				Files.move(partial, output);
				return;
			} catch (FileAlreadyExistsException e) {
				throw new Failure(message("runner.failed.output-appeared", output));
			} catch (AccessDeniedException e) {
				// Virus scanners briefly hold freshly written files open
				if (attempt == 20)
					throw new Failure(message("runner.failed.rename", output, e.getMessage()));
				try {
					Thread.sleep(250);
				} catch (InterruptedException ie) {
					Thread.currentThread().interrupt();
					throw new InterruptedIOException("Interrupted while renaming " + partial);
				}
			}
		}
	}

	private static void checkCancelled(CancellationToken cancel) throws Cancelled {
		if (cancel.isCancelled() || Thread.currentThread().isInterrupted())
			throw new Cancelled();
	}

	private static ConversionResult result(Status status, String reason, long startNanos, ConversionJob job) {
		return new ConversionResult(status, reason, Duration.ofNanos(System.nanoTime() - startNanos), job.logFile());
	}

	static String quote(List<String> command) {
		return command.stream()
				.map(arg -> arg.isEmpty() || arg.contains(" ") ? "\"" + arg + "\"" : arg)
				.collect(Collectors.joining(" "));
	}

	private static String formatDuration(Duration d) {
		return String.format("%.1f s", d.toMillis() / 1000.0);
	}

	private static String message(String key, Object... args) {
		return java.text.MessageFormat.format(resources.getString(key), args);
	}

	/** A failed conversion; the message is the user-facing reason. */
	private static final class Failure extends Exception {
		Failure(String reason) {
			super(reason, null, false, false);
		}
	}

	private static final class Cancelled extends Exception {
		Cancelled() {
			super(null, null, false, false);
		}
	}

	/** The job's log file. Written by the converting thread and the tool-output thread. */
	private static final class JobLog implements Closeable {

		private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

		private final BufferedWriter writer;
		private boolean broken;

		private JobLog(BufferedWriter writer) {
			this.writer = writer;
		}

		static JobLog open(Path file) throws IOException {
			Files.createDirectories(file.getParent());
			// Not an NIO channel: those close when the thread is interrupted, and cancelling must still be logged
			return new JobLog(new BufferedWriter(new OutputStreamWriter(
					new FileOutputStream(file.toFile(), true), StandardCharsets.UTF_8)));
		}

		/** A line from the runner itself. */
		void note(String text) {
			write(LocalDateTime.now().format(TIME) + " " + LOG_PREFIX + text);
		}

		/** A line of tool output, unchanged. */
		void toolLine(String text) {
			write(text);
		}

		private synchronized void write(String line) {
			if (broken)
				return;
			try {
				writer.write(line);
				writer.newLine();
				writer.flush();
			} catch (IOException e) {
				// A full disk shouldn't turn into a failed conversion by itself; the tools will report that
				broken = true;
				logger.warn("Could not write to the conversion log", e);
			}
		}

		@Override
		public synchronized void close() {
			try {
				writer.close();
			} catch (IOException e) {
				logger.debug("Could not close the conversion log", e);
			}
		}
	}
}
