plugins {
    alias(libs.plugins.cinex.android.library)
    alias(libs.plugins.cinex.android.room)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(projects.core.model)
    api(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
}
