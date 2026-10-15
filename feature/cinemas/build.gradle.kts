plugins {
    alias(libs.plugins.cinex.android.feature)
    alias(libs.plugins.cinex.android.roborazzi)
}

dependencies {
    // "Now showing" on a cinema's page reads the downloaded program (spec §6.2); it never downloads.
    implementation(projects.core.program)
}
