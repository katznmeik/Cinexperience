package io.github.katznmeik.cinexperience

import android.app.Application
import io.github.katznmeik.cinexperience.di.AppContainer
import io.github.katznmeik.cinexperience.di.DefaultAppContainer

/** Open so that activity tests can run [MainActivity] with a container of fakes. */
open class CinexApplication : Application() {

    /** Manual dependency injection: one container per process, created lazily. */
    open val container: AppContainer by lazy { DefaultAppContainer(this) }
}
