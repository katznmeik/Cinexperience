package io.github.katznmeik.cinexperience.core.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import io.github.katznmeik.cinexperience.core.ui.R

/** Display / headings. Titles are set in uppercase (see [CinexTextStyles]). */
val Archivo = FontFamily(
    Font(R.font.archivo_bold, FontWeight.Bold),
    Font(R.font.archivo_extrabold, FontWeight.ExtraBold),
)

/** Body text. */
val InstrumentSans = FontFamily(
    Font(R.font.instrument_sans_regular, FontWeight.Normal),
    Font(R.font.instrument_sans_medium, FontWeight.Medium),
    Font(R.font.instrument_sans_semibold, FontWeight.SemiBold),
)

/** App-specific text styles that have no exact Material 3 slot. Sizes follow the mockups. */
@Immutable
data class CinexTextStyles(
    /** Screen title, e.g. "CINEMAS". Render the text uppercase. */
    val screenTitle: TextStyle,
    /** Section header above a card group, e.g. "BACKUP". Render uppercase, muted colour. */
    val sectionHeader: TextStyle,
    /** Card / list item title, e.g. a cinema name. Render uppercase. */
    val cardTitle: TextStyle,
    /** Big score number. */
    val score: TextStyle,
    /** Bottom navigation label. */
    val navigationLabel: TextStyle,
)

val DefaultCinexTextStyles = CinexTextStyles(
    screenTitle = TextStyle(
        fontFamily = Archivo,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 34.sp,
        lineHeight = 40.sp,
        letterSpacing = (-0.5).sp,
    ),
    sectionHeader = TextStyle(
        fontFamily = Archivo,
        fontWeight = FontWeight.Bold,
        fontSize = 14.sp,
        lineHeight = 18.sp,
        letterSpacing = 1.2.sp,
    ),
    cardTitle = TextStyle(
        fontFamily = Archivo,
        fontWeight = FontWeight.Bold,
        fontSize = 17.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.2.sp,
    ),
    score = TextStyle(
        fontFamily = Archivo,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 22.sp,
        lineHeight = 26.sp,
    ),
    navigationLabel = TextStyle(
        fontFamily = InstrumentSans,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
)

private val base = Typography()

/** Material 3 typography: Archivo for display/headline/title, Instrument Sans for body/label. */
val CinexTypography = Typography(
    displayLarge = base.displayLarge.copy(fontFamily = Archivo, fontWeight = FontWeight.ExtraBold),
    displayMedium = base.displayMedium.copy(fontFamily = Archivo, fontWeight = FontWeight.ExtraBold),
    displaySmall = base.displaySmall.copy(fontFamily = Archivo, fontWeight = FontWeight.ExtraBold),
    headlineLarge = base.headlineLarge.copy(fontFamily = Archivo, fontWeight = FontWeight.ExtraBold),
    headlineMedium = base.headlineMedium.copy(fontFamily = Archivo, fontWeight = FontWeight.ExtraBold),
    headlineSmall = base.headlineSmall.copy(fontFamily = Archivo, fontWeight = FontWeight.Bold),
    titleLarge = base.titleLarge.copy(fontFamily = Archivo, fontWeight = FontWeight.Bold),
    titleMedium = base.titleMedium.copy(fontFamily = Archivo, fontWeight = FontWeight.Bold),
    titleSmall = base.titleSmall.copy(fontFamily = Archivo, fontWeight = FontWeight.Bold),
    bodyLarge = TextStyle(fontFamily = InstrumentSans, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontFamily = InstrumentSans, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = InstrumentSans, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontFamily = InstrumentSans, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontFamily = InstrumentSans, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 18.sp),
    labelSmall = TextStyle(fontFamily = InstrumentSans, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 14.sp),
)
