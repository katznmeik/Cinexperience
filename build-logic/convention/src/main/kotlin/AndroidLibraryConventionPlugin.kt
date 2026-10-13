import com.android.build.api.dsl.LibraryExtension
import io.github.katznmeik.cinexperience.buildlogic.configureAndroidCommon
import io.github.katznmeik.cinexperience.buildlogic.defaultNamespace
import io.github.katznmeik.cinexperience.buildlogic.library
import io.github.katznmeik.cinexperience.buildlogic.libs
import io.github.katznmeik.cinexperience.buildlogic.versionInt
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.library")

        extensions.configure<LibraryExtension> {
            configureAndroidCommon(this)
            namespace = defaultNamespace()
            testOptions.targetSdk = libs.versionInt("targetSdk")
            lint.targetSdk = libs.versionInt("targetSdk")
            testOptions.unitTests.isIncludeAndroidResources = true
        }

        dependencies {
            "testImplementation"(libs.library("junit4"))
        }
    }
}
