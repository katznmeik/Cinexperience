package io.github.katznmeik.cinexperience.core.data

/**
 * Persistence lives in this module: the Room database, DAOs, repositories and
 * the preloaded seed data (spec §4, §9).
 */
object DatabaseConfig {
    /** File name of the app's Room database in app-private storage. */
    const val DATABASE_NAME: String = "cinexperience.db"

    /** Seed asset with the preloaded cinemas and halls (spec §9). */
    const val SEED_ASSET_PATH: String = "seed/yorck.json"
}
