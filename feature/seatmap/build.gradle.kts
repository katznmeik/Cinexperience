plugins {
    alias(libs.plugins.cinex.android.feature)
    alias(libs.plugins.cinex.android.roborazzi)
}

dependencies {
    // Photo picker (PickVisualMedia) and BackHandler of the layout editor.
    implementation(libs.androidx.activity.compose)
}
