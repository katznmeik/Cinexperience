plugins {
    alias(libs.plugins.cinex.android.feature)
    alias(libs.plugins.cinex.android.roborazzi)
}

dependencies {
    // File pickers (Storage Access Framework) for export and import.
    implementation(libs.androidx.activity.compose)
    // Settings → Program: the download switch, the last download and "Delete downloaded program" (spec §6.7).
    implementation(projects.core.program)
}
