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
val releaseKeystorePath: String? = providers.environmentVariable("CINEX_KEYSTORE_PATH").orNull
val releaseKeystorePassword: String? = providers.environmentVariable("CINEX_KEYSTORE_PASSWORD").orNull
val releaseKeyAlias: String? = providers.environmentVariable("CINEX_KEY_ALIAS").orNull
val releaseKeyPassword: String? = providers.environmentVariable("CINEX_KEY_PASSWORD").orNull
val hasReleaseSigning = listOf(releaseKeystorePath, releaseKeystorePassword, releaseKeyAlias, releaseKeyPassword)
    .all { !it.isNullOrBlank() } && file(releaseKeystorePath!!).isFile

android {
    defaultConfig {
        versionCode = 1
        versionName = "0.1.0"
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
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
}
