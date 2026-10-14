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

android {
    defaultConfig {
        versionCode = appVersionCode
        versionName = appVersionName
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

dependencies {
    implementation(projects.core.model)
    implementation(projects.core.data)
    implementation(projects.core.ui)
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
