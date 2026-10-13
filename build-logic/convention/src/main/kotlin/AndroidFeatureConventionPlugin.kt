import io.github.katznmeik.cinexperience.buildlogic.library
import io.github.katznmeik.cinexperience.buildlogic.libs
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

/**
 * A feature module: Android library + Compose + type-safe navigation routes, with
 * access to the repositories in core:data. Feature modules never depend on each
 * other; the app module wires them together and hands them the repositories from
 * its manual DI container. ViewModel tests use the fakes from core:testing.
 */
class AndroidFeatureConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("cinex.android.library")
        pluginManager.apply("cinex.android.compose")
        pluginManager.apply("org.jetbrains.kotlin.plugin.serialization")

        dependencies {
            "implementation"(project(":core:model"))
            "implementation"(project(":core:data"))
            "implementation"(project(":core:ui"))
            "implementation"(libs.library("androidx-navigation-compose"))
            "implementation"(libs.library("androidx-lifecycle-runtime-compose"))
            "implementation"(libs.library("androidx-lifecycle-viewmodel-compose"))

            "testImplementation"(project(":core:testing"))
            "testImplementation"(libs.library("kotlinx-coroutines-test"))
        }
    }
}
