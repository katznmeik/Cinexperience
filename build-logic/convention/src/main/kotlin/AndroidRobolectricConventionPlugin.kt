import io.github.katznmeik.cinexperience.buildlogic.configureRobolectricTests
import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * Unit tests on Robolectric (Android framework on the JVM) for a library without
 * Room or screenshots, e.g. core:ticket's JPEG decoding through BitmapFactory.
 * Configures the tests once the Android library plugin is applied, so the order of
 * the plugins doesn't matter.
 */
class AndroidRobolectricConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        target.pluginManager.withPlugin("com.android.library") { target.configureRobolectricTests() }
    }
}
