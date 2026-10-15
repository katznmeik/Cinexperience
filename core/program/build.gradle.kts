plugins {
    alias(libs.plugins.cinex.android.library)
    alias(libs.plugins.kotlin.serialization)
}

// Yorck's program (spec §11.1): download, parser, cache and repository. The ONLY
// module with network code and the INTERNET permission; NetworkConfinementTest
// fails the build if any other module uses network APIs. No third-party network
// or HTML library: Android's built-in HTTPS client and kotlinx.serialization.
dependencies {
    // ScreeningFormat for the visit pre-fill.
    api(projects.core.model)
    api(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.kotlinx.coroutines.test)
}

// NetworkConfinementTest scans the sources, manifests and build files of every
// module, so they are inputs of this module's tests: a change anywhere re-runs it
// instead of reusing an up-to-date or cached result.
val networkConfinementInputs = fileTree(rootDir) {
    include("**/src/*/AndroidManifest.xml", "**/src/*/kotlin/**", "**/src/*/java/**", "**/*.gradle.kts", "**/libs.versions.toml")
    exclude("**/build/**", "**/.*/**", "**/src/test*/**", "**/src/androidTest*/**")
}
tasks.withType<Test>().configureEach {
    inputs.files(networkConfinementInputs)
        .withPropertyName("networkConfinementInputs")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
