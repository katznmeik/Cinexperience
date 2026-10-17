plugins {
    alias(libs.plugins.cinex.android.library)
    alias(libs.plugins.cinex.android.compose)
}

// Font licences (SIL OFL 1.1) for the bundled TTFs live in core/ui/fonts/.

dependencies {
    implementation(libs.androidx.lifecycle.runtime.compose)
    // NavController and the back stack's saved state, for the result message (navigation/ResultMessage.kt).
    implementation(libs.androidx.navigation.compose)

    testImplementation(projects.core.testing)
}
