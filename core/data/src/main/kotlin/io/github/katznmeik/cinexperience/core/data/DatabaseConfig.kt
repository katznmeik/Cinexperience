package io.github.katznmeik.cinexperience.core.data

/**
 * Persistence lives in this module: the Room database, DAOs, repositories and
 * the preloaded seed data (spec §4, §9). The schema itself arrives in M1.
 */
object DatabaseConfig {
    /** File name of the app's Room database in app-private storage. */
    const val DATABASE_NAME: String = "cinexperience.db"
}
