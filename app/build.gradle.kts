import java.io.ByteArrayOutputStream
import javax.inject.Inject

plugins {
    alias(libs.plugins.cinex.android.application)
    alias(libs.plugins.cinex.android.compose)
    alias(libs.plugins.cinex.android.roborazzi)
    alias(libs.plugins.kotlin.serialization)
}

// Release signing (spec §14.4). One fixed key, kept out of the repository and
// provided through environment variables (GitHub Actions secrets in CI):
//   CINEX_KEYSTORE_PATH      path to the .jks file (CI decodes CINEX_KEYSTORE_BASE64 into it)
//   CINEX_KEYSTORE_PASSWORD  keystore password
//   CINEX_KEY_ALIAS          key alias
//   CINEX_KEY_PASSWORD       key password
// Without them, release builds fall back to the debug key so they still build locally.
// CI sets CINEX_REQUIRE_RELEASE_SIGNING=true: then any missing value fails the build
// instead, so a debug-signed APK can never be published as the release.
val releaseKeystorePath: String? = providers.environmentVariable("CINEX_KEYSTORE_PATH").orNull
val releaseKeystorePassword: String? = providers.environmentVariable("CINEX_KEYSTORE_PASSWORD").orNull
val releaseKeyAlias: String? = providers.environmentVariable("CINEX_KEY_ALIAS").orNull
val releaseKeyPassword: String? = providers.environmentVariable("CINEX_KEY_PASSWORD").orNull
val requireReleaseSigning =
    providers.environmentVariable("CINEX_REQUIRE_RELEASE_SIGNING").orNull.equals("true", ignoreCase = true)

val missingSigningProblems: List<String> = buildList {
    mapOf(
        "CINEX_KEYSTORE_PATH" to releaseKeystorePath,
        "CINEX_KEYSTORE_PASSWORD" to releaseKeystorePassword,
        "CINEX_KEY_ALIAS" to releaseKeyAlias,
        "CINEX_KEY_PASSWORD" to releaseKeyPassword,
    ).forEach { (name, value) -> if (value.isNullOrBlank()) add("$name is not set") }
    if (!releaseKeystorePath.isNullOrBlank() && !file(releaseKeystorePath).isFile) {
        add("CINEX_KEYSTORE_PATH does not point to an existing file")
    }
}
val hasReleaseSigning = missingSigningProblems.isEmpty()

if (requireReleaseSigning && !hasReleaseSigning) {
    throw GradleException(
        "CINEX_REQUIRE_RELEASE_SIGNING=true but release signing is incomplete: " +
            missingSigningProblems.joinToString("; ") +
            ". Check the CINEX_* repository secrets.",
    )
}

// Versioning: `cinexVersionName` in gradle.properties is the one place to change
// (MAJOR.MINOR.PATCH). versionCode is derived from it, MAJOR * 10000 + MINOR * 100 + PATCH
// (1.0.0 → 10000, 1.2.3 → 10203), so every new version installs as an update.
val appVersionName: String = providers.gradleProperty("cinexVersionName").get()
val appVersionCode: Int = appVersionName.split(".").let { parts ->
    require(parts.size == 3 && parts.all { part -> part.toIntOrNull() in 0..99 }) {
        "cinexVersionName must be MAJOR.MINOR.PATCH with parts 0–99, was '$appVersionName'"
    }
    parts[0].toInt() * 10_000 + parts[1].toInt() * 100 + parts[2].toInt()
}

// Build ID: the first 7 characters of the commit the APK is built from, shown in
// Settings → About. CI checks out the pushed commit, so it equals ${GITHUB_SHA::7}
// in the APK file name (cinexperience-<id>.apk). Without git, or outside a git
// checkout, it is "unknown". As a ValueSource it works with the configuration cache:
// Gradle runs it again on every build and reconfigures when HEAD has moved.
abstract class GitBuildIdValueSource : ValueSource<String, ValueSourceParameters.None> {
    @get:Inject
    abstract val execOperations: ExecOperations

    override fun obtain(): String = try {
        val output = ByteArrayOutputStream()
        val result = execOperations.exec {
            commandLine("git", "rev-parse", "HEAD")
            standardOutput = output
            errorOutput = ByteArrayOutputStream()
            isIgnoreExitValue = true
        }
        val hash = output.toString(Charsets.UTF_8).trim()
        if (result.exitValue == 0 && hash.matches(Regex("[0-9a-f]{40}"))) hash.take(7) else "unknown"
    } catch (e: Exception) {
        "unknown" // git is not installed
    }
}

val appBuildId: String = providers.of(GitBuildIdValueSource::class) {}.get()

android {
    defaultConfig {
        versionCode = appVersionCode
        versionName = appVersionName
        buildConfigField("String", "BUILD_ID", "\"$appBuildId\"")
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseKeystorePath!!)
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (hasReleaseSigning) {
                signingConfigs.getByName("release")
            } else {
                val buildsRelease = gradle.startParameter.taskNames.any { it.contains("release", ignoreCase = true) }
                if (buildsRelease) logger.warn(
                    "WARNING: CINEX_KEYSTORE_PATH / CINEX_KEYSTORE_PASSWORD / CINEX_KEY_ALIAS / " +
                        "CINEX_KEY_PASSWORD not set (or keystore missing) - release build is signed " +
                        "with the DEBUG key. It will not install as an update over the real app.",
                )
                signingConfigs.getByName("debug")
            }
        }
    }
}

// Release runtime dependencies (spec §11.1 rule 4: no third-party network, HTML or
// image libraries, Android's own HTTPS client only): every module group in the
// resolved release runtime classpath must be on this allowlist. A new library
// from another group fails every build (preBuild depends on this task) until it
// has been reviewed and added here. NetworkConfinementTest checks the build files
// by name as well.
abstract class CheckDependencyGroupsTask : DefaultTask() {
    @get:Input
    abstract val groups: SetProperty<String>

    @get:Input
    abstract val allowedGroups: SetProperty<String>

    @get:Input
    abstract val allowedGroupPrefixes: ListProperty<String>

    @get:Input
    abstract val forbiddenGroups: SetProperty<String>

    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun check() {
        val forbidden = forbiddenGroups.get()
        val unexpected = groups.get().filter { group ->
            group in forbidden || (group !in allowedGroups.get() && allowedGroupPrefixes.get().none { group.startsWith(it) })
        }
        if (unexpected.isNotEmpty()) {
            throw GradleException(
                "Release dependencies from groups that are not allowed: ${unexpected.sorted().joinToString()}. " +
                    "Review them (spec §11.1: no third-party network, HTML or image libraries) and update " +
                    "checkReleaseDependencyGroups in app/build.gradle.kts.",
            )
        }
        report.get().asFile.writeText(groups.get().sorted().joinToString("\n", postfix = "\n"))
    }
}

val checkReleaseDependencyGroups = tasks.register<CheckDependencyGroupsTask>("checkReleaseDependencyGroups") {
    group = "verification"
    description = "Fails if the release runtime classpath has a module group that is not on the allowlist."
    groups.set(
        provider { configurations.getByName("releaseRuntimeClasspath") }
            .flatMap { it.incoming.resolutionResult.rootComponent }
            .map { root ->
                val seen = HashSet<org.gradle.api.artifacts.result.ResolvedComponentResult>()
                val queue = ArrayDeque(listOf(root))
                val found = sortedSetOf<String>()
                while (queue.isNotEmpty()) {
                    val component = queue.removeFirst()
                    if (!seen.add(component)) continue
                    (component.id as? org.gradle.api.artifacts.component.ModuleComponentIdentifier)?.let { found += it.group }
                    component.dependencies
                        .filterIsInstance<org.gradle.api.artifacts.result.ResolvedDependencyResult>()
                        .forEach { queue += it.selected }
                }
                found
            },
    )
    allowedGroupPrefixes.set(listOf("androidx."))
    allowedGroups.set(setOf("org.jetbrains", "org.jetbrains.kotlin", "org.jetbrains.kotlinx", "org.jspecify", "com.google.guava"))
    // Inside androidx: WebView and media players can load remote content. (The marker
    // lets NetworkConfinementTest accept this one denylist line.)
    forbiddenGroups.set(setOf("androidx.webkit", "androidx.media3", "androidx.browser")) // network-scan: denylist
    report.set(layout.buildDirectory.file("reports/release-dependency-groups.txt"))
}
tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(checkReleaseDependencyGroups) }

dependencies {
    implementation(projects.core.model)
    implementation(projects.core.data)
    implementation(projects.core.ui)
    // Yorck's program (v1.1): the only module with network code and the INTERNET permission.
    implementation(projects.core.program)
    implementation(projects.feature.cinemas)
    implementation(projects.feature.seatmap)
    implementation(projects.feature.visits)
    implementation(projects.feature.rankings)
    implementation(projects.feature.backup)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)

    testImplementation(projects.core.testing)
}
