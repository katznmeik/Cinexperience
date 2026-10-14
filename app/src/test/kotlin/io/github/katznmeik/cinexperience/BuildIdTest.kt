package io.github.katznmeik.cinexperience

import org.junit.Assert.assertTrue
import org.junit.Test

/** The build ID shown in Settings → About is the 7-character commit hash, or `unknown` without git. */
class BuildIdTest {

    @Test
    fun buildIdIsShortCommitHashOrUnknown() {
        val buildId = BuildConfig.BUILD_ID
        println("BuildConfig.BUILD_ID=$buildId")
        assertTrue("Unexpected build ID '$buildId'", buildId.matches(Regex("^([0-9a-f]{7}|unknown)$")))
    }
}
