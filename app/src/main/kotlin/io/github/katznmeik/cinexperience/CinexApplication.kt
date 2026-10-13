package io.github.katznmeik.cinexperience

import android.app.Application
import io.github.katznmeik.cinexperience.di.AppContainer
import io.github.katznmeik.cinexperience.di.DefaultAppContainer

class CinexApplication : Application() {

    /** Manual dependency injection: one container per process, created lazily. */
    val container: AppContainer by lazy { DefaultAppContainer(this) }
}
