import com.android.build.api.dsl.CommonExtension
import io.github.katznmeik.cinexperience.buildlogic.library
import io.github.katznmeik.cinexperience.buildlogic.libs
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.getByType

/** Enables Jetpack Compose. Apply after the application or library convention plugin. */
class AndroidComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("org.jetbrains.kotlin.plugin.compose")

        extensions.getByType<CommonExtension>().buildFeatures.compose = true

        dependencies {
            val bom = platform(libs.library("compose-bom"))
            "implementation"(bom)
            "testImplementation"(bom)
            "implementation"(libs.library("compose-ui"))
            "implementation"(libs.library("compose-ui-graphics"))
            "implementation"(libs.library("compose-foundation"))
            "implementation"(libs.library("compose-material3"))
            "implementation"(libs.library("compose-ui-tooling-preview"))
            "debugImplementation"(libs.library("compose-ui-tooling"))
        }
    }
}
