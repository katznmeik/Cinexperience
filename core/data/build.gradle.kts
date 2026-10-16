plugins {
    alias(libs.plugins.cinex.android.library)
    alias(libs.plugins.cinex.android.room)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(projects.core.model)
    api(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    // FileProvider for camera captures of visit photos.
    implementation(libs.androidx.core.ktx)

    // The repository contract test also runs against core:testing's fake repositories.
    testImplementation(projects.core.testing)
}
