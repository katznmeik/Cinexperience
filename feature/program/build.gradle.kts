plugins {
    alias(libs.plugins.cinex.android.feature)
    alias(libs.plugins.cinex.android.roborazzi)
}

// The Program tab and the film page (spec §6.9, v1.1). It only reads the program
// through core:program's ProgramRepository and opens yorck.de links (built by
// ProgramLinks) in the phone's browser; it contains no network code.
dependencies {
    implementation(projects.core.program)
    // The filters kept in shared preferences.
    implementation(libs.kotlinx.serialization.json)
}
