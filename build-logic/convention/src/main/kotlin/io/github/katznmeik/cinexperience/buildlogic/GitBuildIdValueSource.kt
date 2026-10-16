package io.github.katznmeik.cinexperience.buildlogic

import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters
import org.gradle.process.ExecOperations
import java.io.ByteArrayOutputStream
import javax.inject.Inject

/**
 * The build ID: the first 7 characters of the commit the build runs on
 * (`git rev-parse HEAD`), or `unknown` without git or outside a git checkout.
 * As a ValueSource it works with the configuration cache: Gradle runs it again on
 * every build and reconfigures when HEAD has moved.
 */
abstract class GitBuildIdValueSource : ValueSource<String, ValueSourceParameters.None> {
    @get:Inject
    abstract val execOperations: ExecOperations

    override fun obtain(): String = try {
        val output = ByteArrayOutputStream()
        val result = execOperations.exec {
            commandLine("git", "rev-parse", "HEAD")
            standardOutput = output
            errorOutput = ByteArrayOutputStream()
            isIgnoreExitValue = true
        }
        val hash = output.toString(Charsets.UTF_8).trim()
        if (result.exitValue == 0 && hash.matches(Regex("[0-9a-f]{40}"))) hash.take(7) else "unknown"
    } catch (e: Exception) {
        "unknown" // git is not installed
    }
}
