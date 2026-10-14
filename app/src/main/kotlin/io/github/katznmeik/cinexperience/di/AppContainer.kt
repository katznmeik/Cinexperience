package io.github.katznmeik.cinexperience.di

import android.content.Context
import io.github.katznmeik.cinexperience.core.data.DataContainer
import io.github.katznmeik.cinexperience.core.data.files.ReferenceImageStore
import io.github.katznmeik.cinexperience.core.data.repository.CinemaRepository
import io.github.katznmeik.cinexperience.core.data.repository.HallRepository
import io.github.katznmeik.cinexperience.core.data.repository.SeatOverrideRepository
import io.github.katznmeik.cinexperience.core.data.repository.VisitRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Manual dependency injection (no Hilt, spec §10). The container owns the
 * app-wide singletons — the Room database, the repositories from core:data and
 * the reference screenshot store — and hands them to ViewModel factories in the
 * feature modules.
 */
interface AppContainer {
    val applicationContext: Context

    /** Version name of the installed app, shown in Settings → About. */
    val appVersion: String
    val cinemaRepository: CinemaRepository
    val hallRepository: HallRepository
    val visitRepository: VisitRepository
    val seatOverrideRepository: SeatOverrideRepository
    val referenceImageStore: ReferenceImageStore

    /** Housekeeping when the app starts (once per process), off the main thread. */
    fun onAppStart() {}
}

class DefaultAppContainer(context: Context) : AppContainer {
    override val applicationContext: Context = context.applicationContext

    override val appVersion: String by lazy {
        applicationContext.packageManager.getPackageInfo(applicationContext.packageName, 0).versionName.orEmpty()
    }

    /** Lives as long as the process; used for app-wide work such as seeding the database. */
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Opens the database lazily on first repository use, which also starts seeding. */
    private val data = DataContainer(applicationContext, applicationScope)

    override val cinemaRepository: CinemaRepository get() = data.cinemaRepository
    override val hallRepository: HallRepository get() = data.hallRepository
    override val visitRepository: VisitRepository get() = data.visitRepository
    override val seatOverrideRepository: SeatOverrideRepository get() = data.seatOverrideRepository
    override val referenceImageStore: ReferenceImageStore get() = data.referenceImageStore

    private val started = AtomicBoolean(false)

    /** Deletes reference screenshots that no hall uses any more (left by an editor that never saved). */
    override fun onAppStart() {
        if (!started.compareAndSet(false, true)) return
        applicationScope.launch { data.sweepOrphanReferenceImages() }
    }
}
