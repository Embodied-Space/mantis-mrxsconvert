import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.HexFormat
import java.util.zip.ZipFile

plugins {
    // Shadow jar: the extension code; the payload is added by extensionJar (below)
    id("com.gradleup.shadow") version "8.3.5"
    id("qupath-conventions")
}

qupathExtension {
    name = "mantis-mrxsconvert"
    group = "com.embodiedspace"
    // Release builds (spec 10) pass -PreleaseVersion=<tag without the leading v>
    version = providers.gradleProperty("releaseVersion").orElse("0.1.0-SNAPSHOT")
    description = "Converts fluorescence .mrxs slides to OME-TIFF with bioformats2raw and raw2ometiff"
    automaticModule = "com.embodiedspace.mantis.mrxsconvert"
}

dependencies {
    // Provided by QuPath at runtime; never bundled
    shadow(libs.bundles.qupath)
    shadow(libs.bundles.logging)
    shadow(libs.qupath.fxtras)

    testImplementation(libs.bundles.qupath)
    testImplementation(libs.junit)
    // Gradle 9 no longer supplies the launcher; version comes from the junit-bom pulled in above
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// ---------------------------------------------------------------------------------------------------------------
// Tool payload (spec 02): pinned downloads (E2) → payload layout + manifest.json → resources in the -all jar.
// Change a version only together with its URL and SHA-256.
// ---------------------------------------------------------------------------------------------------------------

val payloadResourceRoot = "qupath/ext/mantis/mrxsconvert/payload"
// Survives `gradlew clean`, so the ~470 MB of downloads are fetched once
val payloadDownloadDir = layout.projectDirectory.dir(".gradle/payload-downloads")

abstract class PinnedDownload : DefaultTask() {
    @get:Input abstract val url: Property<String>
    @get:Input abstract val sha256: Property<String>
    @get:OutputFile abstract val destination: RegularFileProperty

    @TaskAction
    fun download() {
        val dest = destination.get().asFile.toPath()
        if (Files.isRegularFile(dest) && sha256Hex(dest) == sha256.get()) return
        val part = dest.resolveSibling(dest.fileName.toString() + ".part")
        Files.createDirectories(dest.parent)
        logger.lifecycle("Downloading ${url.get()}")
        val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).build()
        val response = client.send(HttpRequest.newBuilder(URI.create(url.get())).build(), HttpResponse.BodyHandlers.ofFile(part))
        if (response.statusCode() != 200)
            throw GradleException("Download failed (HTTP ${response.statusCode()}): ${url.get()}")
        val actual = sha256Hex(part)
        if (actual != sha256.get()) {
            Files.delete(part)
            throw GradleException("SHA-256 mismatch for ${url.get()}: expected ${sha256.get()}, got $actual")
        }
        Files.move(part, dest, StandardCopyOption.REPLACE_EXISTING)
    }

    private fun sha256Hex(file: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(file).use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return HexFormat.of().formatHex(digest.digest())
    }
}

/**
 * Builds the payload tree: jre/, common/ (byte-identical jars), b2r/, r2o/, LICENSES/, manifest.json.
 * Fails if a jar shared by name differs, or if the tool-specific jar sets drift from spec 02.
 */
abstract class AssemblePayload : DefaultTask() {
    @get:InputFile abstract val bioformats2rawZip: RegularFileProperty
    @get:InputFile abstract val raw2ometiffZip: RegularFileProperty
    @get:InputFile abstract val jreZip: RegularFileProperty
    @get:InputFile abstract val notice: RegularFileProperty
    @get:Input abstract val versions: MapProperty<String, String>
    @get:Input abstract val bioformats2rawOwnJars: SetProperty<String>
    @get:Input abstract val raw2ometiffOwnJars: SetProperty<String>
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun assemble() {
        val out = outputDir.get().asFile.toPath()
        val work = temporaryDir.toPath()
        out.toFile().listFiles()?.forEach { it.deleteRecursively() }
        work.toFile().listFiles()?.forEach { it.deleteRecursively() }

        val b2r = work.resolve("b2r")
        val r2o = work.resolve("r2o")
        unzipStrippingTopDir(bioformats2rawZip.get().asFile.toPath(), b2r)
        unzipStrippingTopDir(raw2ometiffZip.get().asFile.toPath(), r2o)
        unzipStrippingTopDir(jreZip.get().asFile.toPath(), out.resolve("jre"))
        if (!Files.isRegularFile(out.resolve("jre/bin/java.exe")))
            throw GradleException("JRE zip has no bin/java.exe")

        val b2rJars = jarNames(b2r.resolve("lib"))
        val r2oJars = jarNames(r2o.resolve("lib"))
        val common = b2rJars.intersect(r2oJars)
        for (name in common) {
            if (Files.mismatch(b2r.resolve("lib").resolve(name), r2o.resolve("lib").resolve(name)) != -1L)
                throw GradleException("$name has the same name in both tools but different bytes; it cannot go in common/")
        }
        checkOwnJars("bioformats2raw", b2rJars - common, bioformats2rawOwnJars.get())
        checkOwnJars("raw2ometiff", r2oJars - common, raw2ometiffOwnJars.get())

        copyJars(b2r.resolve("lib"), common, out.resolve("common"))
        copyJars(b2r.resolve("lib"), b2rJars - common, out.resolve("b2r"))
        copyJars(r2o.resolve("lib"), r2oJars - common, out.resolve("r2o"))
        copyFile(b2r.resolve("lib/config/logback.xml"), out.resolve("b2r/config/logback.xml"))
        copyFile(r2o.resolve("lib/config/logback.xml"), out.resolve("r2o/config/logback.xml"))
        copyFile(b2r.resolve("LICENSE.txt"), out.resolve("LICENSES/bioformats2raw-LICENSE.txt"))
        copyFile(r2o.resolve("LICENSE.txt"), out.resolve("LICENSES/raw2ometiff-LICENSE.txt"))
        copyFile(notice.get().asFile.toPath(), out.resolve("LICENSES/NOTICE.txt"))

        writeManifest(out)
        logger.lifecycle("Payload: ${common.size} common jars, ${(b2rJars - common).size} b2r, ${(r2oJars - common).size} r2o")
    }

    private fun checkOwnJars(tool: String, actual: Set<String>, expected: Set<String>) {
        if (actual != expected)
            throw GradleException("$tool's own jars changed. Expected $expected, found $actual. Update spec 02 and the build.")
    }

    private fun jarNames(dir: Path): Set<String> =
        Files.list(dir).use { s -> s.map { it.fileName.toString() }.filter { it.endsWith(".jar") }.toList().toSortedSet() }

    private fun copyJars(from: Path, names: Set<String>, to: Path) {
        for (name in names) copyFile(from.resolve(name), to.resolve(name))
    }

    private fun copyFile(from: Path, to: Path) {
        Files.createDirectories(to.parent)
        Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING)
    }

    private fun unzipStrippingTopDir(zip: Path, dest: Path) {
        val root = dest.toAbsolutePath().normalize()
        ZipFile(zip.toFile()).use { zf ->
            for (entry in zf.entries()) {
                val name = entry.name.substringAfter('/', "")
                if (name.isEmpty() || entry.isDirectory) continue
                val target = root.resolve(name).normalize()
                if (!target.startsWith(root)) throw GradleException("Unsafe zip entry in $zip: ${entry.name}")
                Files.createDirectories(target.parent)
                zf.getInputStream(entry).use { Files.copy(it, target, StandardCopyOption.REPLACE_EXISTING) }
            }
        }
    }

    /** manifest.json: every payload file with size + SHA-256; the id is derived from that list. */
    private fun writeManifest(out: Path) {
        val files = Files.walk(out).use { s ->
            s.filter { Files.isRegularFile(it) }.map { out.relativize(it).toString().replace('\\', '/') }.toList().sorted()
        }
        val entries = files.map { Triple(it, Files.size(out.resolve(it)), sha256Hex(out.resolve(it))) }
        val idDigest = MessageDigest.getInstance("SHA-256")
        for ((path, _, sha) in entries) idDigest.update("$path\t$sha\n".toByteArray(Charsets.UTF_8))
        val id = HexFormat.of().formatHex(idDigest.digest()).substring(0, 16)

        val json = StringBuilder()
        json.append("{\n  \"id\": ").append(quote(id)).append(",\n  \"versions\": {")
        json.append(versions.get().entries.joinToString(",") { "\n    ${quote(it.key)}: ${quote(it.value)}" })
        json.append("\n  },\n  \"files\": [")
        json.append(entries.joinToString(",") { (path, size, sha) ->
            "\n    {\"path\": ${quote(path)}, \"size\": $size, \"sha256\": ${quote(sha)}}"
        })
        json.append("\n  ]\n}\n")
        Files.writeString(out.resolve("manifest.json"), json)
    }

    private fun quote(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun sha256Hex(file: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(file).use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return HexFormat.of().formatHex(digest.digest())
    }
}

val downloadBioformats2raw by tasks.registering(PinnedDownload::class) {
    url = "https://github.com/glencoesoftware/bioformats2raw/releases/download/v0.12.1/bioformats2raw-0.12.1.zip"
    sha256 = "51fbbf04a83c2042b707fce016ad0c8260d37194ff8fe7d986d53f4ebee116a6"
    destination = payloadDownloadDir.file("bioformats2raw-0.12.1.zip")
}

val downloadRaw2ometiff by tasks.registering(PinnedDownload::class) {
    url = "https://github.com/glencoesoftware/raw2ometiff/releases/download/v0.10.1/raw2ometiff-0.10.1.zip"
    sha256 = "e78a22e60ee44eb462abecfc60b997f67c5ed399486a962a654fb30d9b54d9b8"
    destination = payloadDownloadDir.file("raw2ometiff-0.10.1.zip")
}

val downloadJre by tasks.registering(PinnedDownload::class) {
    url = "https://github.com/adoptium/temurin25-binaries/releases/download/jdk-25.0.4.1%2B1/OpenJDK25U-jre_x64_windows_hotspot_25.0.4.1_1.zip"
    sha256 = "4c95451cea98556def2c54f7782933f52a26d4a36bd85e1d59f0364464828b07"
    destination = payloadDownloadDir.file("OpenJDK25U-jre_x64_windows_hotspot_25.0.4.1_1.zip")
}

val assemblePayload by tasks.registering(AssemblePayload::class) {
    bioformats2rawZip = downloadBioformats2raw.flatMap { it.destination }
    raw2ometiffZip = downloadRaw2ometiff.flatMap { it.destination }
    jreZip = downloadJre.flatMap { it.destination }
    notice = layout.projectDirectory.file("src/payload/NOTICE.txt")
    versions = mapOf("bioformats2raw" to "0.12.1", "raw2ometiff" to "0.10.1", "jre" to "25.0.4.1+1")
    bioformats2rawOwnJars = setOf("bioformats2raw-0.12.1.jar", "zarr-java-0.1.3.jar", "blosc-java-0.3-1.21.6.jar", "picocli-4.7.6.jar")
    raw2ometiffOwnJars = setOf("raw2ometiff-0.10.1.jar", "bioformats2raw-0.12.0.jar", "zarr-java-0.1.0.jar",
        "blosc-java-0.1-1.21.4.jar", "picocli-4.7.5.jar")
    outputDir = layout.buildDirectory.dir("payload")
}

// Shadow unpacks every .jar it is given, which would merge the tools' classes into our jar and onto QuPath's
// classpath (A4). So shadowJar builds the code only, and a plain Jar task adds the payload as opaque resources.
tasks.shadowJar {
    archiveClassifier = "code"
}

val extensionJar by tasks.registering(Jar::class) {
    description = "Builds the installable -all jar: extension code + tool payload (spec 02)."
    group = "build"
    archiveClassifier = "all"
    manifest.from(tasks.jar.get().manifest)
    from(zipTree(tasks.shadowJar.flatMap { it.archiveFile })) {
        exclude("META-INF/MANIFEST.MF")
    }
    from(assemblePayload) {
        into(payloadResourceRoot)
    }
}

tasks.test {
    // ToolPayloadIntegrationTest extracts the payload from the real -all jar and runs the JRE + both tools
    dependsOn(extensionJar)
    systemProperty("mrxsconvert.extensionJar", extensionJar.get().archiveFile.get().asFile.absolutePath)
    // As QuPath's launcher does; MachineLimits reads the RAM size through FFM
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

// The -all jar is the one users install, so `gradlew build` must produce it
tasks.assemble {
    dependsOn(extensionJar)
}
