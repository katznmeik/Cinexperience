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

// NetworkConfinementTest scans the sources, manifests, build files and compiled
// libraries of every module, so they are inputs of this module's tests: a change
// anywhere re-runs it instead of reusing an up-to-date or cached result.
// The sources of the test sets it skips (test, test<Variant>, androidTest…) are left
// out. It scans testFixtures; Ant patterns can't tell that from other sets starting
// with "testF", so those stay inputs.
val networkConfinementTestSets = listOf("**/src/test/**", "**/src/androidTest/**") +
    ('A'..'Z').map { "**/src/androidTest$it*/**" } +
    ('A'..'Z').filter { it != 'F' }.map { "**/src/test$it*/**" }
val networkConfinementSources = fileTree(rootDir) {
    include("**/src/*/kotlin/**", "**/src/*/java/**")
    exclude("**/build/**", "**/.*/**")
    exclude(networkConfinementTestSets)
}
val networkConfinementOtherFiles = fileTree(rootDir) {
    include("**/src/*/AndroidManifest.xml", "**/src/*/res/**/*.xml")
    include("**/*.gradle.kts", "**/*.gradle", "**/*.toml")
    include("**/*.jar", "**/*.aar", "**/*.so", "**/*.dex", "**/*.class")
    exclude("**/build/**", "**/.*/**")
}
tasks.withType<Test>().configureEach {
    inputs.files(networkConfinementSources, networkConfinementOtherFiles)
        .withPropertyName("networkConfinementInputs")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
