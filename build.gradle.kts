import java.security.MessageDigest
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.Task
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
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.bundling.AbstractArchiveTask
import org.gradle.api.tasks.bundling.Compression
import org.gradle.api.tasks.bundling.Tar
import org.gradle.api.tasks.bundling.Zip
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.tasks.Jar
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
    abstract val configuredArchiveNames: SetProperty<String>

    @get:Input
    abstract val expectedArchiveNames: SetProperty<String>

    @get:Input
    abstract val configuredChecksumNames: SetProperty<String>

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
            "buildAllArchives must depend only on the three OS-specific checksum tasks; " +
                "found ${aggregateDependencies.get().sorted()}."
        }
        check(configuredArchiveNames.get() == expectedArchiveNames.get()) {
            "The configured OS archive outputs changed: ${configuredArchiveNames.get().sorted()}."
        }

        val expectedChecksumNames = expectedArchiveNames.get().map { "$it.sha256" }.toSet()
        check(configuredChecksumNames.get() == expectedChecksumNames) {
            "The configured OS checksum outputs changed: " +
                "${configuredChecksumNames.get().sorted()}."
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

val windowsStageDirectory = layout.buildDirectory.dir("packaging/stage/windows/MM Launcher")
val linuxStageDirectory = layout.buildDirectory.dir("packaging/stage/linux/MM Launcher")
val macStageDirectory = layout.buildDirectory.dir("packaging/stage/mac/MM Launcher.app")

val stageWindowsArchive = tasks.register<Sync>("stageWindowsArchive") {
    group = "distribution"
    description = "Stages the Windows archive root and Launch4j desktop executable."
    dependsOn(stageCommonPayload, tasks.named("createExe"))
    into(windowsStageDirectory)
    duplicatesStrategy = DuplicatesStrategy.FAIL
    from(commonPayloadDirectory)
    from(layout.buildDirectory.file("launch4j/MM Launcher.exe"))
}

val stageLinuxArchive = tasks.register<Sync>("stageLinuxArchive") {
    group = "distribution"
    description = "Stages the Linux archive root and executable launcher."
    dependsOn(stageCommonPayload)
    into(linuxStageDirectory)
    duplicatesStrategy = DuplicatesStrategy.FAIL
    from(commonPayloadDirectory)
    from("src/distribution/linux/mm-launcher")
    filter<FixCrLfFilter>(
        "eol" to FixCrLfFilter.CrLf.newInstance("lf"),
        "fixlast" to true
    )
    filesMatching("mm-launcher") {
        permissions {
            unix("rwxr-xr-x")
        }
    }
}

val stageMacArchive = tasks.register<Sync>("stageMacArchive") {
    group = "distribution"
    description = "Stages the macOS application bundle."
    dependsOn(stageCommonPayload)
    into(macStageDirectory)
    duplicatesStrategy = DuplicatesStrategy.FAIL

    from(commonPayloadDirectory) {
        into("Contents/app")
    }
    from("src/distribution/macos/Info.plist") {
        into("Contents")
        filter<ReplaceTokens>(
            "tokens" to mapOf(
                "BUNDLE_VERSION" to macBundleVersion
            )
        )
    }
    from("src/distribution/macos/MM Launcher") {
        into("Contents/MacOS")
        filter<FixCrLfFilter>(
            "eol" to FixCrLfFilter.CrLf.newInstance("lf"),
            "fixlast" to true
        )
    }
    filesMatching("Contents/MacOS/MM Launcher") {
        permissions {
            unix("rwxr-xr-x")
        }
    }
}

val distributionsDirectory = layout.buildDirectory.dir("distributions")

val windowsArchive = tasks.register<Zip>("windowsArchive") {
    group = "distribution"
    description = "Builds the versioned Windows x64 ZIP distribution."
    dependsOn(stageWindowsArchive)
    destinationDirectory.set(distributionsDirectory)
    archiveFileName.set("MM-Launcher-${project.version}-Windows-x64.zip")
    from(windowsStageDirectory.map { it.asFile.parentFile })
    eachFile {
        permissions {
            unix("rw-r--r--")
        }
    }
}

val macArchive = tasks.register<Zip>("macArchive") {
    group = "distribution"
    description = "Builds the architecture-neutral external-Java macOS app ZIP."
    dependsOn(stageMacArchive)
    destinationDirectory.set(distributionsDirectory)
    archiveFileName.set("MM-Launcher-${project.version}-macOS.zip")
    from(macStageDirectory.map { it.asFile.parentFile })
    eachFile {
        permissions {
            unix(if (path == "MM Launcher.app/Contents/MacOS/MM Launcher")
                "rwxr-xr-x" else "rw-r--r--")
        }
    }
}

val linuxArchive = tasks.register<Tar>("linuxArchive") {
    group = "distribution"
    description = "Builds the versioned Linux x64 tar.gz distribution."
    dependsOn(stageLinuxArchive)
    destinationDirectory.set(distributionsDirectory)
    archiveFileName.set("MM-Launcher-${project.version}-Linux-x64.tar.gz")
    compression = Compression.GZIP
    from(linuxStageDirectory.map { it.asFile.parentFile })
    eachFile {
        permissions {
            unix(if (path == "MM Launcher/mm-launcher") "rwxr-xr-x" else "rw-r--r--")
        }
    }
}

fun registerChecksum(
    taskName: String,
    archive: TaskProvider<out AbstractArchiveTask>,
    archiveFileName: String
): TaskProvider<Sha256File> = tasks.register<Sha256File>(taskName) {
    group = "distribution"
    description = "Writes the SHA-256 checksum for ${archive.name}."
    dependsOn(archive)
    this.archiveFile.set(distributionsDirectory.map { it.file(archiveFileName) })
    checksumFile.set(distributionsDirectory.map { it.file("$archiveFileName.sha256") })
}

val windowsArchiveChecksum = registerChecksum(
    "windowsArchiveChecksum", windowsArchive,
    "MM-Launcher-${project.version}-Windows-x64.zip"
)
val macArchiveChecksum = registerChecksum(
    "macArchiveChecksum", macArchive,
    "MM-Launcher-${project.version}-macOS.zip"
)
val linuxArchiveChecksum = registerChecksum(
    "linuxArchiveChecksum", linuxArchive,
    "MM-Launcher-${project.version}-Linux-x64.tar.gz"
)

val buildAllArchives = tasks.register("buildAllArchives") {
    group = "distribution"
    description = "Builds all three separate OS archives and their exact SHA-256 files."
    dependsOn(windowsArchiveChecksum, macArchiveChecksum, linuxArchiveChecksum)
}

val verifyDistributionConfiguration = tasks.register<VerifyDistributionConfiguration>(
    "verifyDistributionConfiguration"
) {
    group = "verification"
    description = "Checks that only separate OS archives are configured as release outputs."
    mustRunAfter(tasks.named("assemble"))

    genericArchiveTasksEnabled.set(
        mapOf(
            genericDistZip.name to genericDistZip.get().enabled,
            genericDistTar.name to genericDistTar.get().enabled
        )
    )
    installDistEnabled.set(tasks.named("installDist").get().enabled)
    applicationMainClass.set(application.mainClass)

    val aggregateTask = buildAllArchives.get()
    aggregateDependencies.set(
        aggregateTask.taskDependencies
            .getDependencies(aggregateTask)
            .map { it.name }
            .toSet()
    )
    expectedAggregateDependencies.set(
        setOf(
            windowsArchiveChecksum.name,
            macArchiveChecksum.name,
            linuxArchiveChecksum.name
        )
    )

    expectedArchiveNames.set(
        setOf(
            "MM-Launcher-${project.version}-Windows-x64.zip",
            "MM-Launcher-${project.version}-macOS.zip",
            "MM-Launcher-${project.version}-Linux-x64.tar.gz"
        )
    )
    configuredArchiveNames.set(
        setOf(
            windowsArchive.get().archiveFile.get().asFile.name,
            macArchive.get().archiveFile.get().asFile.name,
            linuxArchive.get().archiveFile.get().asFile.name
        )
    )
    configuredChecksumNames.set(
        setOf(
            windowsArchiveChecksum.get().checksumFile.get().asFile.name,
            macArchiveChecksum.get().checksumFile.get().asFile.name,
            linuxArchiveChecksum.get().checksumFile.get().asFile.name
        )
    )
    genericOutputs.from(
        genericDistZip.get().archiveFile.get().asFile,
        genericDistTar.get().archiveFile.get().asFile
    )
}

tasks.named("check") {
    dependsOn(verifyDistributionConfiguration)
}

fun registerArchiveVerification(
    taskName: String,
    kind: String,
    archiveFileName: String,
    checksum: TaskProvider<Sha256File>
): TaskProvider<Test> = tasks.register<Test>(taskName) {
    group = "verification"
    description = "Checks the $kind archive contract and runs its native smoke test on $kind."
    dependsOn(checksum, tasks.named("testClasses"), verifyDistributionConfiguration)
    shouldRunAfter(tasks.named("test"))
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform {
        includeTags("archive")
    }
    filter {
        includeTestsMatching("org.megamek.launcher.distribution.ArchiveDistributionTest")
    }
    systemProperty("archive.kind", kind)
    systemProperty(
        "archive.path",
        distributionsDirectory.get().file(archiveFileName).asFile.absolutePath
    )
    systemProperty(
        "archive.checksum",
        distributionsDirectory.get().file("$archiveFileName.sha256").asFile.absolutePath
    )
    val expectedJars = buildList {
        add(tasks.named<Jar>("jar").get().archiveFile.get().asFile.name)
        addAll(runtimeClasspath.get().files.map { it.name })
    }.sorted()
    systemProperty("archive.expectedJars", expectedJars.joinToString("|"))
}

val verifyWindowsArchive = registerArchiveVerification(
    "verifyWindowsArchive", "windows",
    "MM-Launcher-${project.version}-Windows-x64.zip", windowsArchiveChecksum
)
val verifyMacArchive = registerArchiveVerification(
    "verifyMacArchive", "mac",
    "MM-Launcher-${project.version}-macOS.zip", macArchiveChecksum
)
val verifyLinuxArchive = registerArchiveVerification(
    "verifyLinuxArchive", "linux",
    "MM-Launcher-${project.version}-Linux-x64.tar.gz", linuxArchiveChecksum
)

tasks.register("verifyArchives") {
    group = "verification"
    description = "Checks every archive; native execution runs only for the current host OS."
    dependsOn(verifyWindowsArchive, verifyMacArchive, verifyLinuxArchive)
}
