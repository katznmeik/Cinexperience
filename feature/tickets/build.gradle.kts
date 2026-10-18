plugins {
    alias(libs.plugins.cinex.android.feature)
}

dependencies {
    // The ticket import (spec §6.10, §11.2): the app's own reader of Yorck's ticket PDF.
    implementation(projects.core.ticket)
    // The import's matching: the Yorck-name table and the cached program's screening format
    // (only the program already on the phone: the import never downloads).
    implementation(projects.core.program)
}
