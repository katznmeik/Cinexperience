package io.github.katznmeik.cinexperience.core.testing

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * A clock that stands still at [now] until a test moves it, e.g. to let a showing
 * start between two ticks of a screen's minute ticker.
 */
class MutableClock(var now: Instant, private val zone: ZoneId = ZoneOffset.UTC) : Clock() {
    override fun instant(): Instant = now

    override fun getZone(): ZoneId = zone

    override fun withZone(zone: ZoneId): Clock = MutableClock(now, zone)

    /** Moves the clock on by [duration]. */
    fun advance(duration: Duration) {
        now = now.plus(duration)
    }
}
