plugins {
    alias(libs.plugins.cinex.android.feature)
}

dependencies {
    // "Now showing" on a cinema's page reads the downloaded program (spec §6.2); it never downloads.
    implementation(projects.core.program)
}
