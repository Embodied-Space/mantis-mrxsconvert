plugins {
    // Shadow jar: will carry the tool payload (spec 02) as resources
    id("com.gradleup.shadow") version "8.3.5"
    id("qupath-conventions")
}

qupathExtension {
    name = "mantis-mrxsconvert"
    group = "com.embodiedspace"
    version = "0.1.0-SNAPSHOT"
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

// The -all jar is the one users install, so `gradlew build` must produce it
tasks.assemble {
    dependsOn(tasks.shadowJar)
}
