plugins {
    alias(libs.plugins.cinex.android.library)
    alias(libs.plugins.cinex.android.robolectric)
}

// Yorck's ticket PDFs (spec §11.2): the app's own small reader for exactly their
// structure, the ticket fields and the QR code's module grid. The file is untrusted
// data. No PDF, OCR, image or QR-code library and no network code; Android is used
// only to decode the QR code's small JPEG (BitmapFactory), everything else is plain
// Kotlin.
dependencies {
    // The rules for booking numbers and seats (BookingNumber, TicketSeat).
    implementation(projects.core.model)
}
