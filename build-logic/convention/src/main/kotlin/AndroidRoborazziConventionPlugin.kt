import io.github.katznmeik.cinexperience.buildlogic.configureRobolectricTests
import io.github.katznmeik.cinexperience.buildlogic.library
import io.github.katznmeik.cinexperience.buildlogic.libs
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

/**
 * Roborazzi screenshot tests running on Robolectric (JVM, no emulator).
 *
 *   ./gradlew recordRoborazziDebug   → writes PNGs to <module>/build/outputs/roborazzi
 *   ./gradlew verifyRoborazziDebug   → compares against recorded PNGs
 */
class AndroidRoborazziConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("io.github.takahirom.roborazzi")
        configureRobolectricTests()

        dependencies {
            "testImplementation"(platform(libs.library("compose-bom")))
            "testImplementation"(libs.library("roborazzi"))
            "testImplementation"(libs.library("roborazzi-compose"))
            "testImplementation"(libs.library("roborazzi-junit-rule"))
            "testImplementation"(libs.library("compose-ui-test-junit4"))
            "debugImplementation"(libs.library("compose-ui-test-manifest"))
        }
    }
}
