plugins {
    alias(libs.plugins.cinex.android.feature)
    alias(libs.plugins.cinex.android.roborazzi)
}

dependencies {
    implementation(projects.core.seatmapui)

    // Camera (TakePicture), photo picker (PickVisualMedia) and BackHandler of the visit form.
    implementation(libs.androidx.activity.compose)
}
