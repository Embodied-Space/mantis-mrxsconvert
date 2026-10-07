package qupath.ext.mantis.mrxsconvert.payload;

import java.nio.file.Path;

/** Run by {@link ToolPayloadTest} in separate JVMs to stand in for several QuPath instances. */
public final class ExtractInChildProcess {

	private ExtractInChildProcess() {}

	/** Args: tools dir, payload source dir. Prints the extracted root. */
	public static void main(String[] args) throws Exception {
		var payload = new ToolPayload(Path.of(args[0]), PayloadSource.directory(Path.of(args[1])));
		System.out.println(payload.extract().root());
	}
}
