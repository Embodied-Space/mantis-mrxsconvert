package qupath.ext.mantis.mrxsconvert.runner;

import java.nio.file.Path;

/** Run by {@link ConversionRunnerTest} in a separate JVM to stand in for another QuPath converting the same output. */
public final class HoldOutputLockInChildProcess {

	private HoldOutputLockInChildProcess() {}

	/** Args: output path. Prints "locked", then holds the lock until stdin closes or the process is killed. */
	public static void main(String[] args) throws Exception {
		Path output = Path.of(args[0]);
		var job = new ConversionJob(output.resolveSibling("in.mrxs"), output, output.resolveSibling("child.log"));
		try (OutputLock ignored = OutputLock.tryAcquire(job)) {
			System.out.println(ignored == null ? "busy" : "locked");
			System.out.flush();
			System.in.read();
		}
	}
}
