/*
 * Copyright (C) 2026 The MegaMek Team. All Rights Reserved.
 *
 * This file is part of MegaMek Launcher.
 *
 * MegaMek Launcher is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License (GPL),
 * version 3 or (at your option) any later version,
 * as published by the Free Software Foundation.
 *
 * MegaMek Launcher is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty
 * of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * A copy of the GPL should have been included with this project;
 * if not, see <https://www.gnu.org/licenses/>.
 *
 * NOTICE: The MegaMek organization is a non-profit group of volunteers
 * creating free software for the BattleTech community.
 *
 * MechWarrior, BattleMech, `Mech and AeroTech are registered trademarks
 * of The Topps Company, Inc. All Rights Reserved.
 *
 * Catalyst Game Labs and the Catalyst Game Labs logo are trademarks of
 * InMediaRes Productions, LLC.
 *
 * MechWarrior Copyright Microsoft Corporation. MegaMek was created under
 * Microsoft's "Game Content Usage Rules"
 * <https://www.xbox.com/en-US/developers/rules> and it is not endorsed by or
 * affiliated with Microsoft.
 */

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.file.ArchiveOperations
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.WriteProperties
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.bundling.AbstractArchiveTask
import org.gradle.api.tasks.bundling.Compression
import org.gradle.api.tasks.bundling.Tar
import org.gradle.api.tasks.bundling.Zip
import org.gradle.api.tasks.testing.Test
import org.gradle.internal.os.OperatingSystem
import org.gradle.jvm.tasks.Jar
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.process.CommandLineArgumentProvider
import org.gradle.process.ExecOperations
import edu.sc.seis.launch4j.tasks.Launch4jLibraryTask
import org.apache.tools.ant.filters.FixCrLfFilter
import org.apache.tools.ant.filters.ReplaceTokens

abstract class ExtractDependencyLicenses : DefaultTask() {
    @get:Classpath
    abstract val dependencies: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @get:Inject
    abstract val archives: ArchiveOperations

    @get:Inject
    abstract val fileSystem: FileSystemOperations

    @TaskAction
    fun extract() {
        val destination = outputDirectory.get().asFile
        fileSystem.delete {
            delete(destination)
        }
        destination.mkdirs()
        dependencies.files
            .filter { it.extension.equals("jar", ignoreCase = true) }
            .sortedBy { it.name.lowercase() }
            .forEach { dependencyJar ->
                fileSystem.copy {
                    from(archives.zipTree(dependencyJar)) {
                        include(
                            "META-INF/LICENSE*",
                            "META-INF/NOTICE*",
                            "META-INF/DEPENDENCIES*"
                        )
                    }
                    into(destination.resolve(dependencyJar.name))
                    includeEmptyDirs = false
                }
            }
    }
}

abstract class Sha256File : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val archiveFile: RegularFileProperty

    @get:OutputFile
    abstract val checksumFile: RegularFileProperty

    @TaskAction
    fun hash() {
        val inputFile = archiveFile.get().asFile
        val digest = MessageDigest.getInstance("SHA-256")
        inputFile.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val hash = digest.digest().joinToString("") { "%02x".format(it) }
        val outputFile = checksumFile.get().asFile
        outputFile.parentFile.mkdirs()
        outputFile.writeText("$hash  ${inputFile.name}\n", Charsets.US_ASCII)
    }
}

abstract class LinkPortableRuntime : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val modules: DirectoryProperty

    @get:Internal
    abstract val jlink: RegularFileProperty

    @get:OutputDirectory
    abstract val runtime: DirectoryProperty

    @get:Inject
    abstract val execOperations: ExecOperations

    @get:Inject
    abstract val fileSystem: FileSystemOperations

    @TaskAction
    fun link() {
        val destination = runtime.get().asFile
        fileSystem.delete { delete(destination) }
        execOperations.exec {
            executable = jlink.get().asFile.absolutePath
            args("--module-path", modules.get().asFile.absolutePath,
                "--add-modules", "ALL-MODULE-PATH",
                "--strip-debug", "--no-header-files", "--no-man-pages",
                "--output", destination.absolutePath)
        }
        check(File(destination, "bin/java${if (OperatingSystem.current().isWindows) ".exe" else ""}").isFile) {
            "Portable runtime must contain bin/java."
        }
        check(File(destination, "legal").isDirectory) {
            "Portable runtime legal notices are missing."
        }
    }
}

abstract class WindowsInstallerMsi : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val inputDirectory: DirectoryProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val iconFile: RegularFileProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val wixResources: DirectoryProperty

    // Checked explicitly at execution time so missing local tools get actionable errors.
    @get:Internal
    abstract val wixBinaryDirectory: DirectoryProperty

    @get:Internal
    abstract val jpackageExecutable: RegularFileProperty

    @get:Input
    abstract val mainJarFileName: Property<String>

    @get:Input
    abstract val appVersion: Property<String>

    @get:Input
    abstract val upgradeCode: Property<String>

    @get:OutputFile
    abstract val installerFile: RegularFileProperty

    @get:Inject
    abstract val execOperations: ExecOperations

    @TaskAction
    fun packageInstaller() {
        check(OperatingSystem.current().isWindows) {
            "The Windows MSI can only be built on Windows."
        }
        val wix = wixBinaryDirectory.get().asFile
        check(wix.isDirectory && File(wix, "candle.exe").isFile && File(wix, "light.exe").isFile) {
            "WiX Toolset 3.x binaries (candle.exe/light.exe) are required at " +
                "${wix.absolutePath}. Download wix314-binaries.zip from " +
                "https://github.com/wixtoolset/wix3/releases and extract it there."
        }
        val icon = iconFile.get().asFile
        check(icon.isFile) { "The Windows installer icon is missing at ${icon.absolutePath}." }
        val jpackage = jpackageExecutable.get().asFile
        check(jpackage.isFile) {
            "jpackage was not found next to the configured Java toolchain at $jpackage."
        }

        val installer = installerFile.get().asFile
        val destination = inputDirectory.get().asFile.parentFile
        destination.mkdirs()
        // --input is a sibling within this directory; never delete the destination tree.
        // Remove only the previous MSI so jpackage cannot reuse or reject an existing output.
        check(!installer.exists() || installer.delete()) {
            "Could not remove previous Windows installer at ${installer.absolutePath}."
        }
        val generated = File(destination, "MegaMek Launcher-${appVersion.get()}.msi")
        check(!generated.exists() || generated.delete()) {
            "Could not remove previous jpackage output at ${generated.absolutePath}."
        }
        execOperations.exec {
            executable = jpackage.absolutePath
            args(
                // Surface WiX light diagnostics on CI failures instead of only its exit code.
                "--verbose",
                "--type", "msi",
                "--input", inputDirectory.get().asFile.absolutePath,
                "--dest", destination.absolutePath,
                "--name", "MegaMek Launcher",
                "--app-version", appVersion.get(),
                "--vendor", "MegaMek",
                "--copyright", "MegaMek",
                "--description", "MegaMek Launcher graphical desktop launcher",
                "--main-jar", mainJarFileName.get(),
                "--main-class", "org.megamek.launcher.DesktopLauncher",
                "--icon", icon.absolutePath,
                "--resource-dir", wixResources.get().asFile.absolutePath,
                // Games launched from the bundled JVM need modules beyond the launcher's own set.
                "--add-modules", "ALL-MODULE-PATH",
                // Keep bin/java.exe for the launcher to run games with the bundled runtime.
                "--jlink-options", "--strip-debug --no-header-files --no-man-pages",
                "--win-per-user-install",
                // Keep installer-owned files away from %LOCALAPPDATA%\MegaMek Launcher
                // (the mutable registry and its sidecars).
                "--install-dir", "Programs/MegaMek Launcher",
                "--win-menu",
                "--win-shortcut",
                "--win-upgrade-uuid", upgradeCode.get()
            )
            environment(
                "PATH",
                wix.absolutePath + File.pathSeparator + (System.getenv("PATH") ?: "")
            )
            environment("MM_LAUNCHER_ART_DIR", wixResources.get().asFile.absolutePath)
        }
        check(generated.isFile) { "jpackage did not produce ${generated.absolutePath}." }
        installer.parentFile.mkdirs()
        Files.move(generated.toPath(), installer.toPath())
    }
}

abstract class NativeInstaller : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val inputDirectory: DirectoryProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val runtimeDirectory: DirectoryProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val macIconFile: RegularFileProperty

    @get:Internal
    abstract val jpackageExecutable: RegularFileProperty

    @get:Input
    abstract val packageType: Property<String>

    @get:Input
    abstract val mainJarFileName: Property<String>

    @get:Input
    abstract val appVersion: Property<String>

    @get:OutputFile
    abstract val installerFile: RegularFileProperty

    @get:Inject
    abstract val execOperations: ExecOperations

    @TaskAction
    fun packageInstaller() {
        val type = packageType.get()
        check((type == "pkg" && OperatingSystem.current().isMacOsX) ||
            ((type == "deb" || type == "rpm") && OperatingSystem.current().isLinux)) {
            "$type installers can only be built on their native host."
        }
        val runtime = runtimeDirectory.get().asFile
        check(File(runtime, "bin/java").isFile &&
            File(runtime, "lib/modules").isFile && File(runtime, "legal").isDirectory) {
            "Native installer runtime must include bin/java, JDK modules and legal notices."
        }
        val jpackage = jpackageExecutable.get().asFile
        check(jpackage.isFile) { "jpackage is missing at ${jpackage.absolutePath}." }
        val installer = installerFile.get().asFile
        val destination = File(inputDirectory.get().asFile.parentFile, "output")
        destination.mkdirs()
        // Only remove old jpackage outputs of this type, not input or user data.
        destination.listFiles().orEmpty().filter { it.extension == type }.forEach {
            check(it.delete()) { "Could not remove previous jpackage output at $it." }
        }
        check(!installer.exists() || installer.delete()) {
            "Could not remove previous installer at ${installer.absolutePath}."
        }
        execOperations.exec {
            executable = jpackage.absolutePath
            args("--verbose", "--type", type,
                "--input", inputDirectory.get().asFile.absolutePath,
                "--dest", destination.absolutePath,
                "--name", "MegaMek Launcher",
                "--app-version", appVersion.get(),
                "--vendor", "MegaMek",
                "--copyright", "MegaMek",
                "--description", "MegaMek Launcher graphical desktop launcher",
                "--main-jar", mainJarFileName.get(),
                "--main-class", "org.megamek.launcher.DesktopLauncher",
                "--runtime-image", runtime.absolutePath)
            if (type == "pkg") {
                args("--mac-package-identifier", "org.megamek.launcher",
                    "--icon", macIconFile.get().asFile.absolutePath,
                    "--install-dir", "/Applications")
            } else {
                args("--linux-package-name", "megamek-launcher",
                    "--install-dir", "/opt")
            }
        }
        val generated = destination.listFiles().orEmpty().filter { it.extension == type }
        check(generated.size == 1) {
            "Expected exactly one $type output from jpackage, found ${generated.size}."
        }
        installer.parentFile.mkdirs()
        Files.move(generated.single().toPath(), installer.toPath())
    }
}

abstract class ValidateProvidedArchiveInputs : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val archiveFile: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val checksumFile: RegularFileProperty

    @get:Input
    abstract val expectedArchiveName: Property<String>

    @TaskAction
    fun validate() {
        val archive = archiveFile.get().asFile
        val checksum = checksumFile.get().asFile
        val expectedName = expectedArchiveName.get()
        check(archive.name == expectedName) {
            "Provided archive must be named $expectedName; found ${archive.name}."
        }
        check(checksum.name == "$expectedName.sha256") {
            "Provided checksum must be named $expectedName.sha256; found ${checksum.name}."
        }

        val checksumLine = checksum.readText(Charsets.US_ASCII).trim()
        val match = Regex("^([0-9a-f]{64})  ([^/\\\\]+)$").matchEntire(checksumLine)
        check(match != null) {
            "Provided checksum must use exact sha256sum format."
        }
        check(match.groupValues[2] == expectedName) {
            "Provided checksum names ${match.groupValues[2]}, not $expectedName."
        }

        val digest = MessageDigest.getInstance("SHA-256")
        archive.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        check(actual == match.groupValues[1]) {
            "Provided archive SHA-256 does not match its checksum."
        }
    }
}

abstract class ProvidedArchiveJvmArguments : CommandLineArgumentProvider {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val archiveFile: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val checksumFile: RegularFileProperty

    override fun asArguments(): Iterable<String> = listOf(
        "-Darchive.path=${archiveFile.get().asFile.absolutePath}",
        "-Darchive.checksum=${checksumFile.get().asFile.absolutePath}"
    )
}

abstract class VerifyDistributionConfiguration : DefaultTask() {
    @get:Input
    abstract val genericArchiveTasksEnabled: MapProperty<String, Boolean>

    @get:Input
    abstract val installDistEnabled: Property<Boolean>

    @get:Input
    abstract val applicationMainClass: Property<String>

    @get:Input
    abstract val aggregateDependencies: SetProperty<String>

    @get:Input
    abstract val expectedAggregateDependencies: SetProperty<String>

    @get:Input
    abstract val configuredArchiveName: Property<String>

    @get:Input
    abstract val expectedArchiveName: Property<String>

    @get:Input
    abstract val configuredChecksumName: Property<String>

    @get:Input
    abstract val providedVerificationDependencies: SetProperty<String>

    @get:Input
    abstract val expectedProvidedVerificationDependencies: SetProperty<String>

    @get:Input
    abstract val legacyTaskNamesPresent: SetProperty<String>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val genericOutputs: ConfigurableFileCollection

    @TaskAction
    fun verify() {
        val enabledGenericTasks = genericArchiveTasksEnabled.get()
            .filterValues { it }
            .keys
        check(enabledGenericTasks.isEmpty()) {
            "Generic distribution tasks must remain disabled: $enabledGenericTasks."
        }
        check(installDistEnabled.get()) {
            "installDist must remain enabled for developer CLI use."
        }
        check(applicationMainClass.get() == "org.megamek.launcher.Main") {
            "The Gradle application entry point must remain org.megamek.launcher.Main."
        }
        check(aggregateDependencies.get() == expectedAggregateDependencies.get()) {
            "buildArchive must depend only on the canonical checksum task; " +
                "found ${aggregateDependencies.get().sorted()}."
        }
        check(configuredArchiveName.get() == expectedArchiveName.get()) {
            "The configured all-platform archive output changed: " +
                "${configuredArchiveName.get()}."
        }

        val expectedChecksumName = "${expectedArchiveName.get()}.sha256"
        check(configuredChecksumName.get() == expectedChecksumName) {
            "The configured checksum output changed: ${configuredChecksumName.get()}."
        }
        check(
            providedVerificationDependencies.get() ==
                expectedProvidedVerificationDependencies.get()
        ) {
            "verifyProvidedArchive must remain read-only and independent of packaging tasks; " +
                "found ${providedVerificationDependencies.get().sorted()}."
        }
        check(legacyTaskNamesPresent.get().isEmpty()) {
            "Legacy per-OS archive tasks must not be registered: " +
                legacyTaskNamesPresent.get().sorted()
        }

        val existingGenericOutputs = genericOutputs.files.filter { it.exists() }
        check(existingGenericOutputs.isEmpty()) {
            "Generic distribution outputs must not exist: " +
                existingGenericOutputs.joinToString { it.name }
        }
    }
}

plugins {
    application
    id("edu.sc.seis.launch4j") version "4.0.0"
}

group = "org.megamek.launcher"
version = "0.14.12"

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

dependencies {
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.3")
    implementation("org.apache.commons:commons-compress:1.28.0")

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.18.3")
}

application {
    // installDist and run intentionally remain the developer/CLI entry point.
    mainClass = "org.megamek.launcher.Main"
}

val genericDistZip = tasks.named<Zip>("distZip") {
    enabled = false
}
val genericDistTar = tasks.named<Tar>("distTar") {
    enabled = false
}

val buildIdentifier = providers.gradleProperty("buildIdentifier")
    .orElse("local-${project.version}")
    .get()
require(buildIdentifier.matches(Regex("[A-Za-z0-9._-]{1,64}"))) {
    "buildIdentifier must be 1-64 ASCII letters, digits, dots, underscores, or hyphens"
}

val runtimeClasspath = configurations.runtimeClasspath

val launcherVersionDirectory = layout.buildDirectory.dir("generated/launcher-version")
val generateLauncherVersion = tasks.register<WriteProperties>("generateLauncherVersion") {
    destinationFile.set(launcherVersionDirectory.map {
        it.file("org/megamek/launcher/version.properties")
    })
    property("version", project.version.toString())
}
sourceSets["main"].resources.srcDir(launcherVersionDirectory)
tasks.named("processResources") {
    dependsOn(generateLauncherVersion)
}

tasks.named<Jar>("jar") {
    manifest {
        attributes(
            "Main-Class" to "org.megamek.launcher.DesktopLauncher",
            "Implementation-Title" to "MegaMek Launcher",
            "Implementation-Version" to project.version.toString(),
            "Build-Identifier" to buildIdentifier,
            "Class-Path" to runtimeClasspath.get().files
                .sortedBy { it.name.lowercase() }
                .joinToString(" ") { it.name }
        )
    }
}

val windowsBootstrapJar = tasks.register<Jar>("windowsBootstrapJar") {
    group = "distribution"
    description = "Builds the small Java bootstrap embedded by Launch4j."
    dependsOn(tasks.named("classes"))
    archiveFileName.set("mm-launcher-windows-bootstrap.jar")
    destinationDirectory.set(layout.buildDirectory.dir("launch4j-bootstrap"))
    from(sourceSets["main"].output) {
        include("org/megamek/launcher/WindowsBootstrap.class")
    }
    manifest {
        attributes("Main-Class" to "org.megamek.launcher.WindowsBootstrap")
    }
}

tasks.withType<AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

tasks.test {
    systemProperty("java.awt.headless", "true")
    useJUnitPlatform {
        excludeTags("archive", "native-gui")
    }
}

tasks.register<Test>("nativeGuiTest") {
    description = "Runs advisory desktop integration tests; requires a working display."
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    systemProperty("java.awt.headless", "false")
    useJUnitPlatform {
        includeTags("native-gui")
        excludeTags("archive")
    }
    val resultsDirectory = reports.junitXml.outputLocation
    doLast {
        val xml = javax.xml.parsers.DocumentBuilderFactory.newInstance()
        xml.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        val executed = resultsDirectory.get().asFile.listFiles()
            .orEmpty().filter { it.name.startsWith("TEST-") && it.extension == "xml" }
            .sumOf {
                val suite = xml.newDocumentBuilder().parse(it).documentElement
                suite.getAttribute("tests").toInt() - suite.getAttribute("skipped").toInt()
            }
        if (executed == 0) throw GradleException("Native GUI tests require a display; no tests executed.")
    }
}

val windowsFileVersion = project.version.toString()
    .substringBefore('-')
    .split('.')
    .map { it.toIntOrNull() ?: 0 }
    .let { (it + listOf(0, 0, 0, 0)).take(4).joinToString(".") }
val macBundleVersion = project.version.toString()
    .substringBefore('-')
    .split('.')
    .map { it.toIntOrNull() ?: 0 }
    .let { (it + listOf(0, 0, 0)).take(3).joinToString(".") }

launch4j {
    mainClassName.set("org.megamek.launcher.WindowsBootstrap")
    outfile.set("MegaMek Launcher.exe")
    outputDirectory.set(layout.buildDirectory.dir("launch4j"))
    setJarTask(windowsBootstrapJar.get())
    dontWrapJar.set(false)
    headerType.set("gui")
    classpath.set(emptyList())
    // Resolve the external lib payload from the extracted executable directory,
    // never from the caller's current working directory.
    chdir.set(".")
    stayAlive.set(true)
    restartOnCrash.set(false)
    requires64Bit.set(true)
    jreMinVersion.set("21")
    // Borrowed from the MegaMek project icon until a dedicated launcher icon
    // exists; MegaMek is the recognizable "face" of the project.
    icon.set(file("src/distribution/windows/icon.ico").absolutePath)
    manifest.set(file("src/distribution/windows/launcher.manifest").absolutePath)
    downloadUrl.set(
        "https://github.com/MegaMek/megamek/wiki/" +
            "Updating-to-Adoptium-(Eclipse-Temurin-Open-Source-Java)"
    )
    supportUrl.set("https://megamek.org")
    messagesJreNotFoundError.set(
        "MegaMek Launcher requires an external Java 21 or newer runtime. " +
            "Install Java, then start MegaMek Launcher again."
    )
    messagesJreVersionError.set(
        "MegaMek Launcher requires Java 21 or newer. The discovered Java runtime is too old."
    )
    messagesStartupError.set("MegaMek Launcher could not start its Java application.")
    messagesLauncherError.set("MegaMek Launcher encountered a launcher error.")
    productName.set("MegaMek Launcher")
    fileDescription.set("MegaMek Launcher graphical desktop launcher")
    internalName.set("MegaMek Launcher")
    windowTitle.set("MegaMek Launcher")
    version.set(windowsFileVersion)
    textVersion.set(project.version.toString())
}

val createPortableExe = tasks.register<Launch4jLibraryTask>("createPortableExe") {
    group = "distribution"
    description = "Builds the Windows bootstrap locked to the portable bundled runtime."
    setJarTask(windowsBootstrapJar.get())
    mainClassName.set("org.megamek.launcher.WindowsBootstrap")
    outfile.set("MegaMek Launcher.exe")
    outputDirectory.set(layout.buildDirectory.dir("launch4j-portable"))
    dontWrapJar.set(false)
    headerType.set("gui")
    classpath.set(emptyList())
    chdir.set(".")
    stayAlive.set(true)
    restartOnCrash.set(false)
    requires64Bit.set(true)
    jreMinVersion.set("21")
    bundledJrePath.set("runtime")
    icon.set(file("src/distribution/windows/icon.ico").absolutePath)
    manifest.set(file("src/distribution/windows/launcher.manifest").absolutePath)
    productName.set("MegaMek Launcher")
    fileDescription.set("MegaMek Launcher graphical desktop launcher")
    internalName.set("MegaMek Launcher")
    windowTitle.set("MegaMek Launcher")
    version.set(windowsFileVersion)
    textVersion.set(project.version.toString())
}

val commonPayloadDirectory = layout.buildDirectory.dir("packaging/common")
val dependencyLicenseDirectory = layout.buildDirectory.dir("packaging/dependency-licenses")

val extractDependencyLicenses = tasks.register<ExtractDependencyLicenses>(
    "extractDependencyLicenses"
) {
    group = "distribution"
    description = "Extracts dependency license and notice material without modifying dependency JARs."
    dependencies.from(runtimeClasspath)
    outputDirectory.set(dependencyLicenseDirectory)
}

val stageCommonPayload = tasks.register<Sync>("stageCommonPayload") {
    group = "distribution"
    description = "Stages the shared Java application and dependency payload."
    dependsOn(tasks.named("jar"), extractDependencyLicenses)
    into(commonPayloadDirectory)
    duplicatesStrategy = DuplicatesStrategy.FAIL

    from(tasks.named("jar")) {
        into("lib")
    }
    from(runtimeClasspath) {
        into("lib")
    }
    from("src/distribution/README.txt")
    from("src/distribution/THIRD-PARTY-NOTICES.txt")
    from("LICENSE.code")
    from(dependencyLicenseDirectory) {
        into("third-party-licenses")
    }
}

val archiveStageDirectory =
    layout.buildDirectory.dir("packaging/stage/all-platform/MegaMek Launcher")

val stageArchive = tasks.register<Sync>("stageArchive") {
    group = "distribution"
    description = "Stages the one fixed-root all-platform portable distribution."
    dependsOn(stageCommonPayload, tasks.named("createExe"))
    into(archiveStageDirectory)
    duplicatesStrategy = DuplicatesStrategy.FAIL

    // The app bundle owns the one shared runtime payload. Keeping the payload
    // here lets the macOS app remain independently runnable while the sibling
    // Windows and Linux entry points resolve exactly the same JARs.
    from(commonPayloadDirectory) {
        exclude("lib/**")
    }
    from(commonPayloadDirectory.map { it.dir("lib") }) {
        into("MegaMek Launcher.app/Contents/app/lib")
    }
    from(layout.buildDirectory.file("launch4j/MegaMek Launcher.exe"))
    from("src/distribution/linux/mm-launcher") {
        filter<FixCrLfFilter>(
            "eol" to FixCrLfFilter.CrLf.newInstance("lf"),
            "fixlast" to true
        )
    }
    from("src/distribution/macos/Info.plist") {
        into("MegaMek Launcher.app/Contents")
        filter<ReplaceTokens>(
            "tokens" to mapOf(
                "BUNDLE_VERSION" to macBundleVersion
            )
        )
    }
    from("src/distribution/macos/MegaMekLauncher.icns") {
        into("MegaMek Launcher.app/Contents/Resources")
    }
    from("src/distribution/macos/MegaMek Launcher") {
        into("MegaMek Launcher.app/Contents/MacOS")
        filter<FixCrLfFilter>(
            "eol" to FixCrLfFilter.CrLf.newInstance("lf"),
            "fixlast" to true
        )
    }
    filesMatching(listOf(
        "mm-launcher",
        "MegaMek Launcher.app/Contents/MacOS/MegaMek Launcher"
    )) {
        permissions {
            unix("rwxr-xr-x")
        }
    }
}

val distributionsDirectory = layout.buildDirectory.dir("distributions")
val releaseArchiveFileName = "MegaMek-Launcher-${project.version}.tar.gz"

val releaseArchive = tasks.register<Tar>("releaseArchive") {
    group = "distribution"
    description = "Builds the one versioned all-platform tar.gz distribution."
    dependsOn(stageArchive)
    destinationDirectory.set(distributionsDirectory)
    archiveFileName.set(releaseArchiveFileName)
    compression = Compression.GZIP
    from(archiveStageDirectory) {
        into("MegaMek Launcher")
    }
    eachFile {
        permissions {
            unix(
                if (isDirectory || path == "MegaMek Launcher/mm-launcher"
                    || path == "MegaMek Launcher/MegaMek Launcher.app/Contents/MacOS/MegaMek Launcher"
                ) {
                    "rwxr-xr-x"
                } else {
                    "rw-r--r--"
                }
            )
        }
    }
}

val archiveChecksum = tasks.register<Sha256File>("archiveChecksum") {
    group = "distribution"
    description = "Writes the exact SHA-256 checksum for the all-platform archive."
    dependsOn(releaseArchive)
    archiveFile.set(releaseArchive.flatMap { it.archiveFile })
    checksumFile.set(distributionsDirectory.map { it.file("$releaseArchiveFileName.sha256") })
}

val buildArchive = tasks.register("buildArchive") {
    group = "distribution"
    description = "Builds the all-platform archive and its exact SHA-256 file."
    dependsOn(archiveChecksum)
}

// Native runtimes are linked on the target runner, never copied across OSes.
// Include all JDK modules: games started by the launcher use the same bin/java
// and require modules that the launcher itself does not reference.
val nativePlatform = when {
    OperatingSystem.current().isWindows &&
        System.getProperty("os.arch") in setOf("amd64", "x86_64") -> "windows-x64"
    OperatingSystem.current().isLinux &&
        System.getProperty("os.arch") in setOf("amd64", "x86_64") -> "linux-x64"
    OperatingSystem.current().isMacOsX && System.getProperty("os.arch") == "aarch64" ->
        "macos-apple-silicon"
    OperatingSystem.current().isMacOsX &&
        System.getProperty("os.arch") in setOf("amd64", "x86_64") ->
        "macos-intel"
    else -> error("Unsupported portable runtime host")
}
val javaHome = extensions.getByType(JavaToolchainService::class.java)
    .launcherFor(java.toolchain).map { it.metadata.installationPath.asFile }
val nativeRuntimeDirectory = layout.buildDirectory.dir("packaging/native-runtime")
val linkPortableRuntime = tasks.register<LinkPortableRuntime>("linkPortableRuntime") {
    group = "distribution"
    description = "Links a complete host-native Java 21 runtime including bin/java and legal notices."
    modules.set(layout.dir(javaHome.map { File(it, "jmods") }))
    jlink.set(layout.file(javaHome.map {
        File(it, "bin/jlink${if (OperatingSystem.current().isWindows) ".exe" else ""}")
    }))
    runtime.set(nativeRuntimeDirectory)
    // A native runtime must never be restored from a different architecture's cache.
    outputs.cacheIf { false }
}

val portableStageDirectory = layout.buildDirectory.dir("packaging/stage/$nativePlatform/MegaMek Launcher")
val stagePortableArchive = tasks.register<Sync>("stagePortableArchive") {
    group = "distribution"
    dependsOn(linkPortableRuntime)
    into(portableStageDirectory)
    if (OperatingSystem.current().isWindows) {
        dependsOn(stageCommonPayload, createPortableExe)
        from(commonPayloadDirectory) {
            exclude("lib/**")
        }
        // WindowsBootstrap resolves the shared JARs relative to the EXE;
        // retain that location without shipping the other OS entry points.
        from(commonPayloadDirectory.map { it.dir("lib") }) {
            into("MegaMek Launcher.app/Contents/app/lib")
        }
        from(layout.buildDirectory.file("launch4j-portable/MegaMek Launcher.exe"))
    } else {
        // Non-Windows runners do not invoke Launch4j or cross-package Windows
        // binaries: each native archive contains only its own entry point.
        dependsOn(stageCommonPayload)
        from(commonPayloadDirectory) { exclude("lib/**") }
        from(commonPayloadDirectory.map { it.dir("lib") }) {
            into("MegaMek Launcher.app/Contents/app/lib")
        }
        if (OperatingSystem.current().isLinux) {
            from("src/distribution/linux/mm-launcher") {
                filter<FixCrLfFilter>("eol" to FixCrLfFilter.CrLf.newInstance("lf"),
                    "fixlast" to true)
            }
        } else {
            from("src/distribution/macos/Info.plist") {
                into("MegaMek Launcher.app/Contents")
                filter<ReplaceTokens>("tokens" to mapOf("BUNDLE_VERSION" to macBundleVersion))
            }
            from("src/distribution/macos/MegaMekLauncher.icns") {
                into("MegaMek Launcher.app/Contents/Resources")
            }
            from("src/distribution/macos/MegaMek Launcher") {
                into("MegaMek Launcher.app/Contents/MacOS")
                filter<FixCrLfFilter>("eol" to FixCrLfFilter.CrLf.newInstance("lf"),
                    "fixlast" to true)
            }
        }
    }
    from(nativeRuntimeDirectory) {
        into(if (nativePlatform.startsWith("macos-"))
            "MegaMek Launcher.app/Contents/runtime" else "runtime")
    }
    eachFile {
        val runtimePath = path.substringAfter("runtime/", "")
        if (path.contains("runtime/") &&
            (runtimePath.startsWith("bin/") ||
                runtimePath == "lib/jspawnhelper" || runtimePath == "lib/jexec")) {
            permissions { unix("rwxr-xr-x") }
        }
    }
}

val portableArchiveFileName = "MegaMek-Launcher-${project.version}-$nativePlatform-portable.tar.gz"
val portableArchive = tasks.register<Tar>("portableArchive") {
    group = "distribution"
    description = "Builds the host-native Java-bundled portable tar.gz."
    dependsOn(stagePortableArchive)
    destinationDirectory.set(distributionsDirectory)
    archiveFileName.set(portableArchiveFileName)
    compression = Compression.GZIP
    from(portableStageDirectory) {
        into("MegaMek Launcher")
    }
    eachFile {
        val runtimePath = path.substringAfter("runtime/", "")
        permissions {
            unix(if (isDirectory || path == "MegaMek Launcher/mm-launcher" ||
                    path == "MegaMek Launcher/MegaMek Launcher.app/Contents/MacOS/MegaMek Launcher" ||
                    (path.contains("runtime/") &&
                        (runtimePath.startsWith("bin/") ||
                            runtimePath == "lib/jspawnhelper" ||
                            runtimePath == "lib/jexec"))) "rwxr-xr-x" else "rw-r--r--")
        }
    }
}
val portableArchiveChecksum = tasks.register<Sha256File>("portableArchiveChecksum") {
    group = "distribution"
    dependsOn(portableArchive)
    archiveFile.set(portableArchive.flatMap { it.archiveFile })
    checksumFile.set(distributionsDirectory.map { it.file("$portableArchiveFileName.sha256") })
}
tasks.register("buildPortableArchive") {
    group = "distribution"
    dependsOn(portableArchiveChecksum)
}

tasks.register<Test>("verifyPortableArchive") {
    group = "verification"
    description = "Extracts and starts the host-native archive with external Java unavailable."
    dependsOn(portableArchiveChecksum, tasks.named("testClasses"))
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform()
    filter { includeTestsMatching("org.megamek.launcher.distribution.PortableDistributionTest") }
    systemProperty("portable.path", distributionsDirectory.get()
        .file(portableArchiveFileName).asFile.absolutePath)
    systemProperty("portable.checksum", distributionsDirectory.get()
        .file("$portableArchiveFileName.sha256").asFile.absolutePath)
    systemProperty("portable.name", portableArchiveFileName)
    systemProperty("portable.platform", nativePlatform)
}

// ---------------------------------------------------------------------------
// Windows MSI installer (jpackage). Preserve the existing per-user upgrade
// identity and unattended self-update behavior independently of other packages.
//
// It bundles a jlink-trimmed Java runtime (via jpackage's automatic runtime
// detection) so installed users never need a system Java. It always installs
// per-user (no admin/UAC prompt), so the launcher's self-updater can run
// `msiexec /i ... /qn` unattended after downloading a new version.
//
// windowsInstallerUpgradeCode MUST NEVER CHANGE. Windows Installer uses this
// GUID to recognize "this is the same product, install it in place" during a
// silent upgrade; changing it turns every future update into a side-by-side
// install instead of an in-place replacement.
// ---------------------------------------------------------------------------
val windowsInstallerUpgradeCode = "616FFD64-0D7F-4FBE-9BB9-63FD6D9D32FA"

val windowsInstallerInputDirectory = layout.buildDirectory.dir("packaging/windows-installer/input")
val stageWindowsInstallerInput = tasks.register<Sync>("stageWindowsInstallerInput") {
    group = "distribution"
    description = "Stages the exact jpackage --input payload for the Windows MSI installer."
    dependsOn(stageCommonPayload)
    into(windowsInstallerInputDirectory)
    duplicatesStrategy = DuplicatesStrategy.FAIL
    from(commonPayloadDirectory.map { it.dir("lib") })
    from("LICENSE.code")
}

// jpackage first writes "MegaMek Launcher-<app-version>.msi" into the packaging
// directory; WindowsInstallerMsi moves it to this versioned CI artifact name.
val windowsInstallerFileName = "MegaMek-Launcher-${project.version}-windows-x64.msi"
// ProductVersion must be numeric even when the Gradle artifact is a SNAPSHOT.
val windowsInstallerAppVersion = project.version.toString().substringBefore('-')
// macOS requires a positive first component; offsetting the major preserves
// upgrade order when the launcher advances from 0.x to 1.x.
val macInstallerAppVersion = windowsInstallerAppVersion.split(".").let { components ->
    check(components.size == 3 && components.all { it.toIntOrNull() != null && it.toInt() >= 0 }) {
        "macOS installer requires a numeric major.minor.patch version."
    }
    "${Math.addExact(components[0].toInt(), 1)}.${components[1]}.${components[2]}"
}

val windowsInstallerMsi = tasks.register<WindowsInstallerMsi>("windowsInstallerMsi") {
    group = "distribution"
    description = "Builds the per-user Windows MSI installer with a bundled Java runtime via jpackage."
    dependsOn(stageWindowsInstallerInput)

    val toolchainLauncher = project.extensions
        .getByType(JavaToolchainService::class.java)
        .launcherFor(java.toolchain)
    wixBinaryDirectory.set(layout.projectDirectory.dir(".tools/wix314"))
    jpackageExecutable.set(layout.file(toolchainLauncher.map {
        File(it.executablePath.asFile.parentFile, "jpackage.exe")
    }))
    mainJarFileName.set(tasks.named<Jar>("jar").flatMap { it.archiveFileName })
    inputDirectory.set(windowsInstallerInputDirectory)
    installerFile.set(distributionsDirectory.map { it.file(windowsInstallerFileName) })
    appVersion.set(windowsInstallerAppVersion)
    upgradeCode.set(windowsInstallerUpgradeCode)
    // Borrowed from the MegaMek project icon until a dedicated launcher icon exists.
    iconFile.set(layout.projectDirectory.file("src/distribution/windows/icon.ico"))
    wixResources.set(layout.projectDirectory.dir("src/distribution/windows/msi"))
    outputs.cacheIf { false }
}

val windowsInstallerChecksum = tasks.register<Sha256File>("windowsInstallerChecksum") {
    group = "distribution"
    description = "Writes the exact SHA-256 checksum for the Windows MSI installer."
    dependsOn(windowsInstallerMsi)
    archiveFile.set(distributionsDirectory.map { it.file(windowsInstallerFileName) })
    checksumFile.set(
        distributionsDirectory.map { it.file("$windowsInstallerFileName.sha256") }
    )
}

val buildWindowsInstaller = tasks.register("buildWindowsInstaller") {
    group = "distribution"
    description = "Builds the Windows MSI installer and its exact SHA-256 file."
    dependsOn(windowsInstallerChecksum)
}

// macOS and Linux installers contain only installer-owned application binaries.
// The jlink image includes every JDK module (including bin/java for launched
// games); registry, logs and game installations remain in separate user paths.
val nativeInstallerInputDirectory = layout.buildDirectory.dir("packaging/native-installer/input")
val stageNativeInstallerInput = tasks.register<Sync>("stageNativeInstallerInput") {
    group = "distribution"
    dependsOn(stageCommonPayload)
    into(nativeInstallerInputDirectory)
    duplicatesStrategy = DuplicatesStrategy.FAIL
    from(commonPayloadDirectory) {
        exclude("lib/**")
    }
    from(commonPayloadDirectory.map { it.dir("lib") })
}

if (OperatingSystem.current().isLinux || OperatingSystem.current().isMacOsX) {
    val types = if (OperatingSystem.current().isLinux) listOf("deb", "rpm") else listOf("pkg")
    types.forEach { type ->
        val name = type.replaceFirstChar { it.uppercase() }
        val fileName = "MegaMek-Launcher-${project.version}-$nativePlatform.$type"
        val installer = tasks.register<NativeInstaller>("native${name}Installer") {
            group = "distribution"
            description = "Builds the host-native $type installer with bundled Java."
            dependsOn(stageNativeInstallerInput, linkPortableRuntime)
            inputDirectory.set(nativeInstallerInputDirectory)
            runtimeDirectory.set(nativeRuntimeDirectory)
            macIconFile.set(layout.projectDirectory.file(
                "src/distribution/macos/MegaMekLauncher.icns"))
            jpackageExecutable.set(layout.file(javaHome.map { File(it, "bin/jpackage") }))
            packageType.set(type)
            mainJarFileName.set(tasks.named<Jar>("jar").flatMap { it.archiveFileName })
            appVersion.set(if (type == "pkg") macInstallerAppVersion else windowsInstallerAppVersion)
            installerFile.set(distributionsDirectory.map { it.file(fileName) })
            outputs.cacheIf { false }
        }
        val checksum = tasks.register<Sha256File>("native${name}InstallerChecksum") {
            group = "distribution"
            dependsOn(installer)
            archiveFile.set(installer.flatMap { it.installerFile })
            checksumFile.set(distributionsDirectory.map { it.file("$fileName.sha256") })
        }
        tasks.register("build${name}Installer") {
            group = "distribution"
            dependsOn(checksum)
        }
    }
}

val verifyDistributionConfiguration = tasks.register<VerifyDistributionConfiguration>(
    "verifyDistributionConfiguration"
) {
    group = "verification"
    description = "Checks the single-archive and read-only verification task model."
    mustRunAfter(tasks.named("assemble"))

    genericArchiveTasksEnabled.set(
        mapOf(
            genericDistZip.name to genericDistZip.get().enabled,
            genericDistTar.name to genericDistTar.get().enabled
        )
    )
    installDistEnabled.set(tasks.named("installDist").get().enabled)
    applicationMainClass.set(application.mainClass)

    val aggregateTask = buildArchive.get()
    aggregateDependencies.set(
        aggregateTask.taskDependencies
            .getDependencies(aggregateTask)
            .map { it.name }
            .toSet()
    )
    expectedAggregateDependencies.set(
        setOf(archiveChecksum.name)
    )

    expectedArchiveName.set(releaseArchiveFileName)
    configuredArchiveName.set(releaseArchive.get().archiveFile.get().asFile.name)
    configuredChecksumName.set(archiveChecksum.get().checksumFile.get().asFile.name)
    genericOutputs.from(
        genericDistZip.get().archiveFile.get().asFile,
        genericDistTar.get().archiveFile.get().asFile
    )
}

tasks.named("check") {
    dependsOn(verifyDistributionConfiguration)
}

val expectedRuntimeJars = buildList {
    add(tasks.named<Jar>("jar").get().archiveFile.get().asFile.name)
    addAll(runtimeClasspath.get().files.map { it.name })
}.sorted()

val defaultVerificationPlatform = System.getProperty("os.name", "")
    .lowercase()
    .let { os ->
        when {
            os.contains("win") -> "windows"
            os.contains("mac") -> "mac"
            os.contains("linux") -> "linux"
            else -> "unsupported"
        }
    }
val verificationPlatform = providers.gradleProperty("verificationPlatform")
    .orElse(defaultVerificationPlatform)

fun Test.configureArchiveVerification() {
    group = "verification"
    shouldRunAfter(tasks.named("test"))
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform {
        includeTags("archive")
    }
    filter {
        includeTestsMatching("org.megamek.launcher.distribution.ArchiveDistributionTest")
    }
    systemProperty("archive.expectedName", releaseArchiveFileName)
    systemProperty("archive.expectedJars", expectedRuntimeJars.joinToString("|"))
    systemProperty("archive.expectedBuildIdentifier", buildIdentifier)
    systemProperty("archive.platform", verificationPlatform.get())
}

val verifyArchive = tasks.register<Test>("verifyArchive") {
    description = "Checks the built archive and runs the current host's native entry point."
    dependsOn(archiveChecksum, tasks.named("testClasses"), verifyDistributionConfiguration)
    configureArchiveVerification()
    systemProperty(
        "archive.path",
        distributionsDirectory.get().file(releaseArchiveFileName).asFile.absolutePath
    )
    systemProperty(
        "archive.checksum",
        distributionsDirectory.get().file("$releaseArchiveFileName.sha256").asFile.absolutePath
    )
}

val providedArchive = providers.gradleProperty("providedArchive")
val providedChecksum = providers.gradleProperty("providedChecksum")
val providedArchiveFile = layout.file(providedArchive.map { File(it) })
val providedChecksumFile = layout.file(providedChecksum.map { File(it) })

val validateProvidedArchiveInputs = tasks.register<ValidateProvidedArchiveInputs>(
    "validateProvidedArchiveInputs"
) {
    group = "verification"
    description = "Validates the exact supplied archive/checksum names and SHA-256."
    archiveFile.set(providedArchiveFile)
    checksumFile.set(providedChecksumFile)
    expectedArchiveName.set(releaseArchiveFileName)
}

val verifyProvidedArchive = tasks.register<Test>("verifyProvidedArchive") {
    description = "Read-only verification of an explicitly supplied archive and checksum."
    dependsOn(
        tasks.named("testClasses"),
        verifyDistributionConfiguration,
        validateProvidedArchiveInputs
    )
    configureArchiveVerification()
    outputs.upToDateWhen { false }
    jvmArgumentProviders.add(
        objects.newInstance<ProvidedArchiveJvmArguments>().apply {
            archiveFile.set(providedArchiveFile)
            checksumFile.set(providedChecksumFile)
        }
    )
}

verifyDistributionConfiguration.configure {
    val providedTask = verifyProvidedArchive.get()
    providedVerificationDependencies.set(
        providedTask.taskDependencies
            .getDependencies(providedTask)
            .map { it.name }
            .toSet()
    )
    expectedProvidedVerificationDependencies.set(
        setOf(
            "classes",
            "compileJava",
            "compileTestJava",
            "testClasses",
            validateProvidedArchiveInputs.name,
            verifyDistributionConfiguration.name
        )
    )
    val legacyNames = setOf(
        "stageWindowsArchive",
        "stageLinuxArchive",
        "stageMacArchive",
        "windowsArchive",
        "macArchive",
        "linuxArchive",
        "windowsArchiveChecksum",
        "macArchiveChecksum",
        "linuxArchiveChecksum",
        "buildAllArchives",
        "verifyWindowsArchive",
        "verifyMacArchive",
        "verifyLinuxArchive",
        "verifyArchives"
    )
    legacyTaskNamesPresent.set(tasks.names.intersect(legacyNames))
}
