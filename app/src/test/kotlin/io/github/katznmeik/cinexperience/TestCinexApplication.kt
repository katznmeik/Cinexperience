package io.github.katznmeik.cinexperience

import io.github.katznmeik.cinexperience.di.AppContainer

/**
 * The application of the activity tests (`@Config(application = TestCinexApplication::class)`):
 * [MainActivity] runs with the container a test sets in [next] (fakes), never the real one.
 */
class TestCinexApplication : CinexApplication() {
    override val container: AppContainer get() = checkNotNull(next) { "Set TestCinexApplication.next before launching MainActivity" }

    companion object {
        /** The container the next activity gets. */
        var next: AppContainer? = null
    }
}
