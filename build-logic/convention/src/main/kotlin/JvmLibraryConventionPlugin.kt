import io.github.katznmeik.cinexperience.buildlogic.JAVA_TARGET
import io.github.katznmeik.cinexperience.buildlogic.configureKotlinJvmTarget
import io.github.katznmeik.cinexperience.buildlogic.configureUnitTests
import io.github.katznmeik.cinexperience.buildlogic.forbidFileDependencies
import io.github.katznmeik.cinexperience.buildlogic.library
import io.github.katznmeik.cinexperience.buildlogic.libs
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

/** Pure Kotlin/JVM module without Android dependencies (e.g. core:model). */
class JvmLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("org.jetbrains.kotlin.jvm")

        extensions.configure<JavaPluginExtension> {
            sourceCompatibility = JAVA_TARGET
            targetCompatibility = JAVA_TARGET
        }
        configureKotlinJvmTarget()
        configureUnitTests()
        forbidFileDependencies()

        // Lets `./gradlew testDebugUnitTest` (the Android task name used by CI)
        // run the tests of pure JVM modules too.
        tasks.register("testDebugUnitTest") {
            group = "verification"
            description = "Runs the unit tests (alias of 'test' for JVM modules)."
            dependsOn("test")
        }

        dependencies {
            "testImplementation"(libs.library("kotlin-test"))
            "testImplementation"(libs.library("junit4"))
        }
    }
}
