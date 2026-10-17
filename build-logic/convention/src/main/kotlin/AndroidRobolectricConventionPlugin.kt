import io.github.katznmeik.cinexperience.buildlogic.configureRobolectricTests
import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * Unit tests on Robolectric (Android framework on the JVM) for a library without
 * Room or screenshots, e.g. core:ticket's JPEG decoding through BitmapFactory.
 * Apply after the library convention plugin.
 */
class AndroidRobolectricConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = target.configureRobolectricTests()
}
