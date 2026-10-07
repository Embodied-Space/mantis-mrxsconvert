package qupath.ext.mantis.mrxsconvert.temp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TempWorkspaceTest {

	@TempDir
	Path temp;

	private Path root() {
		return temp.resolve("mantis-mrxsconvert");
	}

	@Test
	void createsLockedDirAndDeletesItOnClose() throws IOException {
		Path dir;
		try (TempWorkspace workspace = TempWorkspace.create(root())) {
			dir = workspace.dir();
			assertEquals(root().toAbsolutePath(), dir.getParent());
			assertTrue(Files.isRegularFile(dir.resolve(TempWorkspace.LOCK_FILE)));
			Files.createDirectories(dir.resolve("pyramid/0/0"));
			Files.writeString(dir.resolve("pyramid/0/0/chunk"), "data");
		}
		assertFalse(Files.exists(dir));
	}

	@Test
	void defaultRootIsUnderSystemTemp() {
		assertEquals(Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().resolve("mantis-mrxsconvert"),
				TempWorkspace.defaultRoot());
	}

	@Test
	void cleanupKeepsThisJvmsOpenWorkspace() throws IOException {
		try (TempWorkspace workspace = TempWorkspace.create(root())) {
			assertEquals(0, TempWorkspace.cleanupOrphans(root()));
			assertTrue(Files.isDirectory(workspace.dir()));
		}
	}

	@Test
	void cleanupOfMissingRootDoesNothing() {
		assertEquals(0, TempWorkspace.cleanupOrphans(temp.resolve("missing")));
	}

	/** Spec 05: another instance's live workspace is kept; once that instance is killed, cleanup removes it. */
	@Test
	void keepsOtherProcessWorkspaceUntilThatProcessIsKilled() throws Exception {
		Path argFile = temp.resolve("cp.args");
		String classpath = System.getProperty("java.class.path").replace('\\', '/');
		Files.writeString(argFile, "-cp \"" + classpath + "\"", StandardCharsets.UTF_8);
		String java = ProcessHandle.current().info().command().orElseThrow();
		Process child = new ProcessBuilder(java, "@" + argFile, HoldWorkspaceInChildProcess.class.getName(),
				root().toString()).redirectError(ProcessBuilder.Redirect.DISCARD).start();
		try {
			var out = new BufferedReader(new InputStreamReader(child.getInputStream(), StandardCharsets.UTF_8));
			Path dir = Path.of(out.readLine());
			Files.writeString(dir.resolve("data.zarr"), "x");

			assertEquals(0, TempWorkspace.cleanupOrphans(root()));
			assertTrue(Files.exists(dir.resolve("data.zarr")));

			// Like QuPath being killed mid-conversion
			child.destroyForcibly();
			assertTrue(child.waitFor(30, TimeUnit.SECONDS));
			assertEquals(1, TempWorkspace.cleanupOrphans(root()));
			assertFalse(Files.exists(dir));
		} finally {
			child.destroyForcibly();
		}
	}

	@Test
	void dirWithoutLockFileIsRemovedOnlyWhenOld() throws IOException {
		Path young = Files.createDirectories(root().resolve("young"));
		Path old = Files.createDirectories(root().resolve("old"));
		Files.writeString(old.resolve("chunk"), "x");
		Files.setLastModifiedTime(old, FileTime.from(Instant.now().minus(Duration.ofHours(25))));

		assertEquals(1, TempWorkspace.cleanupOrphans(root()));
		assertTrue(Files.exists(young));
		assertFalse(Files.exists(old));
	}

	@Test
	void unlockedEmptyLockFileMeansBeingCreated() throws IOException {
		Path dir = Files.createDirectories(root().resolve("creating"));
		Files.createFile(dir.resolve(TempWorkspace.LOCK_FILE));
		assertEquals(0, TempWorkspace.cleanupOrphans(root()));
		assertTrue(Files.exists(dir));
	}

	@Test
	void fileHeldOpenIsLoggedNotThrown() throws IOException {
		Path dir = Files.createDirectories(root().resolve("orphan"));
		Files.writeString(dir.resolve(TempWorkspace.LOCK_FILE), "pid=1\n");
		Path held = dir.resolve("held.dat");
		Files.writeString(held, "x");
		// java.io streams don't share delete access on Windows, so the file can't be deleted while open
		try (var ignored = new FileInputStream(held.toFile())) {
			assertEquals(0, TempWorkspace.cleanupOrphans(root()));
		}
		assertEquals(1, TempWorkspace.cleanupOrphans(root()));
		assertFalse(Files.exists(dir));
	}
}
