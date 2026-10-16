plugins {
    alias(libs.plugins.cinex.android.feature)
}

dependencies {
    implementation(projects.core.seatmapui)

    // Camera (TakePicture), photo picker (PickVisualMedia) and BackHandler of the visit form.
    implementation(libs.androidx.activity.compose)
    // The form draft kept in the SavedStateHandle across process death.
    implementation(libs.kotlinx.serialization.json)
}
