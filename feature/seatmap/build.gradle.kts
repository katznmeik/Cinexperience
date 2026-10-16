plugins {
    alias(libs.plugins.cinex.android.feature)
}

dependencies {
    implementation(projects.core.seatmapui)

    // Photo picker (PickVisualMedia) and BackHandler of the layout editor.
    implementation(libs.androidx.activity.compose)
}
