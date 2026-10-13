package io.github.katznmeik.cinexperience.di

import android.content.Context

/**
 * Manual dependency injection (no Hilt, spec §10). The container owns the
 * app-wide singletons — the Room database and the repositories from core:data
 * (from M1) — and hands them to ViewModel factories in the feature modules.
 */
interface AppContainer {
    val applicationContext: Context
}

class DefaultAppContainer(context: Context) : AppContainer {
    override val applicationContext: Context = context.applicationContext
}
