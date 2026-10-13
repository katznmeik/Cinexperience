import io.github.katznmeik.cinexperience.buildlogic.library
import io.github.katznmeik.cinexperience.buildlogic.libs
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

/** Room with KSP code generation. */
class AndroidRoomConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.google.devtools.ksp")

        dependencies {
            "implementation"(libs.library("androidx-room-runtime"))
            "implementation"(libs.library("androidx-room-ktx"))
            "ksp"(libs.library("androidx-room-compiler"))
        }
    }
}
