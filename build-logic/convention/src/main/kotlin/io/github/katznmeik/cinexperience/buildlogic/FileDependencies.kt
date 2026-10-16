package io.github.katznmeik.cinexperience.buildlogic

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.artifacts.FileCollectionDependency

/**
 * Configurations that the Android Gradle plugin itself fills with the SDK's
 * `android.jar` (found in a full build, `assembleRelease` included); reviewed, nothing
 * from them reaches the APK:
 * - `androidJdkImage`: the JDK image of it, the system modules javac compiles against;
 * - `androidApis`: the classes the boot classpath and the unit tests' mockable jar are
 *   made from.
 */
private val PLUGIN_FILE_CONFIGURATIONS = setOf("androidJdkImage", "androidApis")

/**
 * Spec §11.1 rule 4: fails the build as soon as any configuration of the module gets
 * a dependency on files (`files(…)`, `fileTree(…)`), however the build file writes it
 * (also through a variable or a configuration of its own). Such a jar, AAR or native
 * library has no Maven group and isn't part of the resolved module graph, so
 * `:app:checkReleaseDependencyGroups` would never see it. NetworkConfinementTest also
 * looks for such files in the repository and for the usual ways of declaring them.
 * A plugin that adds files of its own fails here too until its configuration is
 * reviewed and added to [PLUGIN_FILE_CONFIGURATIONS].
 */
internal fun Project.forbidFileDependencies() {
    val projectPath = path
    configurations.matching { it.name !in PLUGIN_FILE_CONFIGURATIONS }.configureEach {
        val configuration = name
        dependencies.withType(FileCollectionDependency::class.java).configureEach {
            throw GradleException(
                "$projectPath: '$configuration' has a dependency on files, which the release dependency " +
                    "check can't see (spec §11.1: no third-party network, HTML or image libraries). " +
                    "Use a reviewed Maven dependency instead.",
            )
        }
    }
}
