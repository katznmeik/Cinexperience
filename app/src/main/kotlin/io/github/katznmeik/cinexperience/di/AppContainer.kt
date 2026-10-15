package io.github.katznmeik.cinexperience.di

import android.content.Context
import io.github.katznmeik.cinexperience.BuildConfig
import io.github.katznmeik.cinexperience.core.data.DataContainer
import io.github.katznmeik.cinexperience.core.data.backup.BackupRepository
import io.github.katznmeik.cinexperience.core.data.files.ReferenceImageStore
import io.github.katznmeik.cinexperience.core.data.files.VisitPhotoStore
import io.github.katznmeik.cinexperience.core.data.repository.CinemaRankingsRepository
import io.github.katznmeik.cinexperience.core.data.repository.CinemaRepository
import io.github.katznmeik.cinexperience.core.data.repository.HallRepository
import io.github.katznmeik.cinexperience.core.data.repository.RankingsRepository
import io.github.katznmeik.cinexperience.core.data.repository.SeatOverrideRepository
import io.github.katznmeik.cinexperience.core.data.repository.VisitRepository
import io.github.katznmeik.cinexperience.core.program.DefaultProgramRepository
import io.github.katznmeik.cinexperience.core.program.ProgramRepository
import io.github.katznmeik.cinexperience.feature.program.ProgramFilterStore
import io.github.katznmeik.cinexperience.feature.program.SharedPreferencesProgramFilterStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Manual dependency injection (no Hilt, spec §10). The container owns the
 * app-wide singletons — the Room database, the repositories from core:data, the
 * reference screenshot and visit photo stores and the program repository from
 * core:program — and hands them to ViewModel factories in the feature modules.
 */
interface AppContainer {
    val applicationContext: Context

    /** Version name of the installed app, shown in Settings → About. */
    val appVersion: String

    /** Short commit hash the APK was built from (or `unknown`), shown in Settings → About. */
    val buildId: String
    val cinemaRepository: CinemaRepository
    val hallRepository: HallRepository
    val visitRepository: VisitRepository
    val seatOverrideRepository: SeatOverrideRepository

    /** Halls and cinemas with their scores for the Rankings tab, derived from [cinemaRepository]. */
    val rankingsRepository: RankingsRepository get() = CinemaRankingsRepository(cinemaRepository, visitRepository)
    val referenceImageStore: ReferenceImageStore
    val visitPhotoStore: VisitPhotoStore

    /** Backup export and import (Settings, spec §8). */
    val backupRepository: BackupRepository

    /**
     * Yorck's program (v1.1, spec §11.1): the download permission, the cached
     * program and the download, from core:program (the only module with network code).
     */
    val programRepository: ProgramRepository

    /** The Program tab's current and saved filters (app settings, spec §6.9, §8). */
    val programFilterStore: ProgramFilterStore

    /** Housekeeping when the app starts (once per process), off the main thread. */
    fun onAppStart() {}
}

class DefaultAppContainer(context: Context) : AppContainer {
    override val applicationContext: Context = context.applicationContext

    /** The one version source of the container: Settings → About and the program's User-Agent use it. */
    override val appVersion: String = BuildConfig.VERSION_NAME

    override val buildId: String = BuildConfig.BUILD_ID

    /** Lives as long as the process; used for app-wide work such as seeding the database. */
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Opens the database lazily on first repository use, which also starts seeding. */
    private val data = DataContainer(applicationContext, applicationScope)

    override val cinemaRepository: CinemaRepository get() = data.cinemaRepository
    override val hallRepository: HallRepository get() = data.hallRepository
    override val visitRepository: VisitRepository get() = data.visitRepository
    override val seatOverrideRepository: SeatOverrideRepository get() = data.seatOverrideRepository
    override val referenceImageStore: ReferenceImageStore get() = data.referenceImageStore
    override val visitPhotoStore: VisitPhotoStore get() = data.visitPhotoStore
    override val backupRepository: BackupRepository get() = data.backupRepository

    /**
     * Created on first use; it then reads the stored permission and the cached
     * program (files/program/). It downloads only when the Program UI asks and the
     * owner has agreed.
     */
    override val programRepository: ProgramRepository by lazy {
        DefaultProgramRepository.create(applicationContext, appVersion, applicationScope)
    }

    override val programFilterStore: ProgramFilterStore by lazy { SharedPreferencesProgramFilterStore(applicationContext) }

    private val started = AtomicBoolean(false)

    /**
     * Deletes reference screenshots and visit photos that nothing uses any more
     * (left by an editor or visit form that never saved, or by deleted halls).
     */
    override fun onAppStart() {
        if (!started.compareAndSet(false, true)) return
        applicationScope.launch { data.sweepOrphanFiles() }
    }
}
