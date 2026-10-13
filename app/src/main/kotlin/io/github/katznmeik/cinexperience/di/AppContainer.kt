package io.github.katznmeik.cinexperience.di

import android.content.Context
import io.github.katznmeik.cinexperience.core.data.DataContainer
import io.github.katznmeik.cinexperience.core.data.repository.CinemaRepository
import io.github.katznmeik.cinexperience.core.data.repository.HallRepository
import io.github.katznmeik.cinexperience.core.data.repository.SeatOverrideRepository
import io.github.katznmeik.cinexperience.core.data.repository.VisitRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Manual dependency injection (no Hilt, spec §10). The container owns the
 * app-wide singletons — the Room database and the repositories from core:data —
 * and hands them to ViewModel factories in the feature modules.
 */
interface AppContainer {
    val applicationContext: Context

    /** Version name of the installed app, shown in Settings → About. */
    val appVersion: String
    val cinemaRepository: CinemaRepository
    val hallRepository: HallRepository
    val visitRepository: VisitRepository
    val seatOverrideRepository: SeatOverrideRepository
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
}
