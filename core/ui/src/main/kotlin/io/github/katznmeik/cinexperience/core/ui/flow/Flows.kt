package io.github.katznmeik.cinexperience.core.ui.flow

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Emits now and then once a minute, so a screen's "now" stays current (started
 * showings go, dates roll over).
 */
fun minuteTicks(): Flow<Unit> = flow {
    while (true) {
        emit(Unit)
        delay(60_000)
    }
}
