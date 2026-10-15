plugins {
    alias(libs.plugins.cinex.android.library)
    alias(libs.plugins.cinex.android.compose)
    // Robolectric for the self-tests of the accessibility checks.
    alias(libs.plugins.cinex.android.roborazzi)
}

// Test helpers shared by the feature and app modules (testImplementation only):
// in-memory fake repositories (also FakeProgramRepository), illustrative fixtures, the main-dispatcher rule and
// the screenshot surface and accessibility checks for Compose tests.
dependencies {
    api(projects.core.data)
    api(projects.core.program)
    api(projects.core.ui)
    api(libs.kotlinx.coroutines.test)
    api(libs.junit4)
    api(platform(libs.compose.bom))
    api(libs.compose.ui.test.junit4)
}
