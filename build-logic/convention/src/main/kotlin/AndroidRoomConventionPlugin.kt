import androidx.room.gradle.RoomExtension
import com.android.build.api.dsl.CommonExtension
import io.github.katznmeik.cinexperience.buildlogic.configureRobolectricTests
import io.github.katznmeik.cinexperience.buildlogic.library
import io.github.katznmeik.cinexperience.buildlogic.libs
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

/**
 * Room with KSP code generation and schema export.
 *
 * The exported schemas (`<module>/schemas/<database class>/<version>.json`) are
 * committed: every schema change needs a new version, a migration and a
 * migration test (`MigrationTestHelper`). Database tests run on Robolectric.
 */
class AndroidRoomConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.google.devtools.ksp")
        pluginManager.apply("androidx.room")

        val schemaDir = "$projectDir/schemas"
        extensions.configure<RoomExtension> {
            schemaDirectory(schemaDir)
        }
        // MigrationTestHelper reads the schemas from assets. The Room plugin only
        // adds them to androidTest; our database tests run on Robolectric.
        extensions.configure<CommonExtension> {
            sourceSets.getByName("test").assets.directories.add(schemaDir)
        }

        configureRobolectricTests()

        dependencies {
            "implementation"(libs.library("androidx-room-runtime"))
            "implementation"(libs.library("androidx-room-ktx"))
            "ksp"(libs.library("androidx-room-compiler"))

            "testImplementation"(libs.library("androidx-room-testing"))
            "testImplementation"(libs.library("androidx-test-core"))
            "testImplementation"(libs.library("kotlinx-coroutines-test"))
        }
    }
}
