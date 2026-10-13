import io.github.katznmeik.cinexperience.buildlogic.library
import io.github.katznmeik.cinexperience.buildlogic.libs
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.withType

/**
 * Roborazzi screenshot tests running on Robolectric (JVM, no emulator).
 *
 *   ./gradlew recordRoborazziDebug   → writes PNGs to <module>/build/outputs/roborazzi
 *   ./gradlew verifyRoborazziDebug   → compares against recorded PNGs
 */
class AndroidRoborazziConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("io.github.takahirom.roborazzi")

        dependencies {
            "testImplementation"(platform(libs.library("compose-bom")))
            "testImplementation"(libs.library("junit4"))
            "testImplementation"(libs.library("robolectric"))
            "testImplementation"(libs.library("roborazzi"))
            "testImplementation"(libs.library("roborazzi-compose"))
            "testImplementation"(libs.library("roborazzi-junit-rule"))
            "testImplementation"(libs.library("compose-ui-test-junit4"))
            "debugImplementation"(libs.library("compose-ui-test-manifest"))
        }

        tasks.withType<Test>().configureEach {
            // Robolectric with native graphics needs a bit more heap than the default.
            maxHeapSize = "2g"
            // Robolectric's SDK 36 runtime pokes at FileDescriptor internals.
            jvmArgs(
                "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED",
                "--add-opens=java.base/java.io=ALL-UNNAMED",
            )
        }
    }
}
