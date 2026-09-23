import java.io.File
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
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.Sync
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
version = "0.1.0-SNAPSHOT"

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
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.18.3")
    implementation("org.apache.commons:commons-compress:1.28.0")

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
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

tasks.named<Jar>("jar") {
    manifest {
        attributes(
            "Main-Class" to "org.megamek.launcher.DesktopLauncher",
            "Implementation-Title" to "MM Launcher",
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
    useJUnitPlatform {
        excludeTags("archive")
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
    outfile.set("MM Launcher.exe")
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
        "MM Launcher requires an external Java 21 or newer runtime. " +
            "Install Java, then start MM Launcher again."
    )
    messagesJreVersionError.set(
        "MM Launcher requires Java 21 or newer. The discovered Java runtime is too old."
    )
    messagesStartupError.set("MM Launcher could not start its Java application.")
    messagesLauncherError.set("MM Launcher encountered a launcher error.")
    productName.set("MM Launcher")
    fileDescription.set("MM Launcher graphical desktop launcher")
    internalName.set("MM Launcher")
    windowTitle.set("MM Launcher")
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
    from(dependencyLicenseDirectory) {
        into("third-party-licenses")
    }
}

val archiveStageDirectory =
    layout.buildDirectory.dir("packaging/stage/all-platform/MM Launcher")

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
        into("MM Launcher.app/Contents/app/lib")
    }
    from(layout.buildDirectory.file("launch4j/MM Launcher.exe"))
    from("src/distribution/linux/mm-launcher") {
        filter<FixCrLfFilter>(
            "eol" to FixCrLfFilter.CrLf.newInstance("lf"),
            "fixlast" to true
        )
    }
    from("src/distribution/macos/Info.plist") {
        into("MM Launcher.app/Contents")
        filter<ReplaceTokens>(
            "tokens" to mapOf(
                "BUNDLE_VERSION" to macBundleVersion
            )
        )
    }
    from("src/distribution/macos/MM Launcher") {
        into("MM Launcher.app/Contents/MacOS")
        filter<FixCrLfFilter>(
            "eol" to FixCrLfFilter.CrLf.newInstance("lf"),
            "fixlast" to true
        )
    }
    filesMatching(listOf(
        "mm-launcher",
        "MM Launcher.app/Contents/MacOS/MM Launcher"
    )) {
        permissions {
            unix("rwxr-xr-x")
        }
    }
}

val distributionsDirectory = layout.buildDirectory.dir("distributions")
val releaseArchiveFileName = "MM-Launcher-${project.version}.tar.gz"

val releaseArchive = tasks.register<Tar>("releaseArchive") {
    group = "distribution"
    description = "Builds the one versioned all-platform tar.gz distribution."
    dependsOn(stageArchive)
    destinationDirectory.set(distributionsDirectory)
    archiveFileName.set(releaseArchiveFileName)
    compression = Compression.GZIP
    from(archiveStageDirectory.map { it.asFile.parentFile })
    eachFile {
        permissions {
            unix(
                if (isDirectory || path == "MM Launcher/mm-launcher"
                    || path == "MM Launcher/MM Launcher.app/Contents/MacOS/MM Launcher"
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

// ---------------------------------------------------------------------------
// Windows MSI installer (jpackage). This is an additional, independently
// verified artifact alongside the portable tar.gz above; it does not replace
// it and does not participate in buildArchive/verifyDistributionConfiguration.
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
}

val windowsInstallerOutputDirectory = layout.buildDirectory.dir("packaging/windows-installer")
val windowsInstallerProductName = "MM Launcher"
// jpackage names its MSI output "<name>-<app-version>.msi" verbatim, spaces
// included; this must track --name/--app-version below exactly, or the
// checksum task will look for a file that was never produced.
val windowsInstallerFileName = "$windowsInstallerProductName-$macBundleVersion.msi"

val windowsInstallerMsi = tasks.register<Exec>("windowsInstallerMsi") {
    group = "distribution"
    description = "Builds the per-user Windows MSI installer with a bundled Java runtime via jpackage."
    dependsOn(stageWindowsInstallerInput)

    val wixBinaryDirectory = file(".tools/wix314")
    val toolchainLauncher = project.extensions
        .getByType(JavaToolchainService::class.java)
        .launcherFor(java.toolchain)
    val mainJarFileName = tasks.named<Jar>("jar").get().archiveFileName.get()
    val inputDirectory = windowsInstallerInputDirectory
    val outputDirectory = windowsInstallerOutputDirectory
    val appVersion = macBundleVersion
    val upgradeCode = windowsInstallerUpgradeCode
    val fileName = windowsInstallerFileName
    // Borrowed from the MegaMek project icon until a dedicated launcher icon
    // exists; MegaMek is the recognizable "face" of the project.
    val iconFile = file("src/distribution/windows/icon.ico")

    onlyIf {
        val supported = OperatingSystem.current().isWindows
        if (!supported) {
            logger.warn("windowsInstallerMsi skipped: Windows MSI packaging only runs on Windows.")
        }
        supported
    }

    inputs.dir(inputDirectory)
    inputs.file(iconFile)
    inputs.property("upgradeCode", upgradeCode)
    inputs.property("appVersion", appVersion)
    outputs.file(outputDirectory.map { it.file(fileName) })
    outputs.cacheIf { false }

    doFirst {
        check(wixBinaryDirectory.isDirectory) {
            "WiX Toolset 3.x binaries (candle.exe/light.exe) are required at " +
                "${wixBinaryDirectory.absolutePath}. Download wix314-binaries.zip from " +
                "https://github.com/wixtoolset/wix3/releases and extract it there."
        }
        check(iconFile.isFile) {
            "The Windows installer icon is missing at ${iconFile.absolutePath}."
        }
        val javaExecutable = toolchainLauncher.get().executablePath.asFile
        val jpackageExecutable = File(javaExecutable.parentFile, "jpackage.exe")
        check(jpackageExecutable.isFile) {
            "jpackage was not found next to the configured Java toolchain at $jpackageExecutable."
        }

        val destination = outputDirectory.get().asFile
        destination.deleteRecursively()
        destination.mkdirs()

        executable(jpackageExecutable)
        args(
            "--type", "msi",
            "--input", inputDirectory.get().asFile.absolutePath,
            "--dest", destination.absolutePath,
            "--name", windowsInstallerProductName,
            "--app-version", appVersion,
            "--vendor", "MegaMek",
            "--copyright", "MegaMek",
            "--description", "MM Launcher graphical desktop launcher",
            "--main-jar", mainJarFileName,
            "--main-class", "org.megamek.launcher.DesktopLauncher",
            "--icon", iconFile.absolutePath,
            "--win-per-user-install",
            "--win-menu",
            "--win-shortcut",
            "--win-upgrade-uuid", upgradeCode
        )
        environment(
            "PATH",
            wixBinaryDirectory.absolutePath + File.pathSeparator + (System.getenv("PATH") ?: "")
        )
    }
}

val windowsInstallerChecksum = tasks.register<Sha256File>("windowsInstallerChecksum") {
    group = "distribution"
    description = "Writes the exact SHA-256 checksum for the Windows MSI installer."
    dependsOn(windowsInstallerMsi)
    archiveFile.set(windowsInstallerOutputDirectory.map { it.file(windowsInstallerFileName) })
    checksumFile.set(
        windowsInstallerOutputDirectory.map { it.file("$windowsInstallerFileName.sha256") }
    )
}

val buildWindowsInstaller = tasks.register("buildWindowsInstaller") {
    group = "distribution"
    description = "Builds the Windows MSI installer and its exact SHA-256 file."
    dependsOn(windowsInstallerChecksum)
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
