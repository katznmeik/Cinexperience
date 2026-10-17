package io.github.katznmeik.cinexperience.core.ui.flow

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

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

/**
 * Each value as [Result.success]; if the flow fails (e.g. a repository error), one
 * [Result.failure] with its exception ends it, so a screen shows a message instead
 * of crashing.
 */
fun <T> Flow<T>.asResult(): Flow<Result<T>> = map { Result.success(it) }.catch { emit(Result.failure(it)) }
