plugins {
    alias(libs.plugins.cinex.android.library)
    alias(libs.plugins.cinex.android.compose)
    alias(libs.plugins.cinex.android.roborazzi)
}

// The hall seat map shared by feature:seatmap (hall detail, layout editor) and
// feature:visits (seat picker of the visit form): canvas drawing, geometry and
// hit-testing, zoom/pan and the accessible seat picker.
dependencies {
    api(projects.core.model)
    implementation(projects.core.ui)

    testImplementation(projects.core.testing)
}
