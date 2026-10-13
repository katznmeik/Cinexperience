plugins {
    alias(libs.plugins.cinex.android.library)
}

// Test helpers shared by the feature and app modules (testImplementation only):
// in-memory fake repositories, illustrative fixtures and the main-dispatcher rule.
dependencies {
    api(projects.core.data)
    api(libs.kotlinx.coroutines.test)
    api(libs.junit4)
}
