import com.android.build.api.dsl.ApplicationExtension
import io.github.katznmeik.cinexperience.buildlogic.BASE_PACKAGE
import io.github.katznmeik.cinexperience.buildlogic.configureAndroidCommon
import io.github.katznmeik.cinexperience.buildlogic.library
import io.github.katznmeik.cinexperience.buildlogic.libs
import io.github.katznmeik.cinexperience.buildlogic.versionInt
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.application")

        extensions.configure<ApplicationExtension> {
            configureAndroidCommon(this)
            namespace = BASE_PACKAGE
            defaultConfig {
                applicationId = BASE_PACKAGE
                targetSdk = libs.versionInt("targetSdk")
            }
            testOptions.unitTests.isIncludeAndroidResources = true
        }

        dependencies {
            "testImplementation"(libs.library("junit4"))
        }
    }
}
