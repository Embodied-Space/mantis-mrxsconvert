package qupath.ext.mantis.mrxsconvert.temp;

import java.nio.file.Path;

/** Run by {@link TempWorkspaceTest} in a separate JVM to stand in for another QuPath instance. */
public final class HoldWorkspaceInChildProcess {

	private HoldWorkspaceInChildProcess() {}

	/** Args: workspace root. Prints the workspace dir, then holds it until stdin closes or the process is killed. */
	public static void main(String[] args) throws Exception {
		TempWorkspace workspace = TempWorkspace.create(Path.of(args[0]));
		System.out.println(workspace.dir());
		System.out.flush();
		System.in.read();
	}
}
