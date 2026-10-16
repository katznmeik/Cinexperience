package io.github.katznmeik.cinexperience.core.model

import java.util.Locale

/**
 * Upper bounds for how many rows of a kind the app holds (next to [TextLimits],
 * which bounds each text). The repositories refuse to go beyond them, the seeder
 * stops at them and the backup validator refuses backups beyond them, so data the
 * app saved always exports (and imports again).
 */
object DataLimits {
    /**
     * Halls in all cinemas together: far above anything real (Yorck has 38), and
     * low enough that a backup can't fill the database with halls, each of which
     * may hold a large layout.
     */
    const val MAX_HALLS: Int = 1_000

    /** [MAX_HALLS] as user-facing text: "1,000". */
    val MAX_HALLS_TEXT: String = "%,d".format(Locale.ROOT, MAX_HALLS)
}
