import com.android.build.api.artifact.SingleArtifact
import io.github.katznmeik.cinexperience.buildlogic.CheckDependencyGroupsTask
import io.github.katznmeik.cinexperience.buildlogic.GitBuildIdValueSource
import io.github.katznmeik.cinexperience.buildlogic.resolvedModuleGroups

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
// checkout, it is "unknown" (GitBuildIdValueSource in build-logic, which also works
// with the configuration cache).
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
// by name as well. The task type (CheckDependencyGroupsTask) is in build-logic; the
// lists stay here.
val checkReleaseDependencyGroups = tasks.register<CheckDependencyGroupsTask>("checkReleaseDependencyGroups") {
    group = "verification"
    description = "Fails if the release runtime classpath has a module group that is not on the allowlist."
    groups.set(resolvedModuleGroups("releaseRuntimeClasspath"))
    allowedGroupPrefixes.set(listOf("androidx."))
    allowedGroups.set(setOf("org.jetbrains", "org.jetbrains.kotlin", "org.jetbrains.kotlinx", "org.jspecify", "com.google.guava"))
    // Inside androidx: WebView and media players can load remote content. (The marker
    // lets NetworkConfinementTest accept this one denylist line.)
    forbiddenGroups.set(setOf("androidx.webkit", "androidx.media3", "androidx.browser")) // network-scan: denylist
    report.set(layout.buildDirectory.file("reports/release-dependency-groups.txt"))
}
tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(checkReleaseDependencyGroups) }

// The merged manifests of both build types, for AppPermissionsTest and
// NetworkSecurityConfigTest: CI runs only the debug unit tests, and the release
// APK's permissions and network config must be checked too.
abstract class MergedManifestsArgument : CommandLineArgumentProvider {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val debug: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val release: RegularFileProperty

    override fun asArguments(): List<String> = listOf(
        "-Dcinex.mergedManifest.debug=${debug.get().asFile.absolutePath}",
        "-Dcinex.mergedManifest.release=${release.get().asFile.absolutePath}",
    )
}

val mergedManifests = objects.newInstance<MergedManifestsArgument>()
androidComponents {
    onVariants { variant ->
        val manifest = variant.artifacts.get(SingleArtifact.MERGED_MANIFEST)
        when (variant.buildType) {
            "debug" -> mergedManifests.debug.set(manifest)
            "release" -> mergedManifests.release.set(manifest)
        }
    }
}
tasks.withType<Test>().configureEach { jvmArgumentProviders.add(mergedManifests) }

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
    implementation(projects.feature.program)
    implementation(projects.feature.tickets)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)

    testImplementation(projects.core.testing)
}
