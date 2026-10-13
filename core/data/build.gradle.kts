plugins {
    alias(libs.plugins.cinex.android.library)
    alias(libs.plugins.cinex.android.room)
}

dependencies {
    implementation(projects.core.model)
}
