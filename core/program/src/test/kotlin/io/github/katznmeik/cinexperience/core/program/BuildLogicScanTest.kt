package io.github.katznmeik.cinexperience.core.program

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The convention plugins in `build-logic` hold build code too: the release
 * dependency check's task type (`CheckDependencyGroupsTask`) and the build ID
 * (`GitBuildIdValueSource`) live there. [NetworkConfinementTest] scans them as
 * sources; here they must also pass the build-file rule (no network, image or
 * media library named), as they did inside `app/build.gradle.kts`. The check's
 * lists stay in that build file, with the one denylist line the scan accepts.
 */
class BuildLogicScanTest {

    private val root = NetworkScan.repositoryRoot()
    private val scanned = NetworkScan.scan(root).scannedFiles.map { it.relativeTo(root).invariantSeparatorsPath }

    @Test
    fun theBuildLogicSourcesAreScannedAndNameNoNetworkLibrary() {
        val buildLogic = scanned.filter { it.startsWith("build-logic/") && it.endsWith(".kt") }
        for (moved in listOf("CheckDependencyGroupsTask.kt", "GitBuildIdValueSource.kt")) {
            assertTrue("$moved not scanned: $buildLogic", buildLogic.any { it.endsWith("/buildlogic/$moved") })
        }
        val violations = buildLogic.flatMap { path ->
            NetworkScan.buildViolations(File(root, path).readText()).map { "$path:$it" }
        }
        assertEquals(violations.joinToString("\n"), emptyList<String>(), violations)
    }

    @Test
    fun theOnlyDenylistLineIsTheReleaseCheckInTheAppBuildFile() {
        val textFiles = scanned.filter { path -> TEXT_EXTENSIONS.any { path.endsWith(it) } }
        assertTrue(textFiles.toString(), "app/build.gradle.kts" in textFiles)
        val marked = textFiles.flatMap { path ->
            File(root, path).readLines()
                .filter { it.trimEnd().endsWith(DENYLIST_MARKER) }
                .map { "$path: ${it.trim()}" }
        }
        assertEquals(marked.joinToString("\n"), 1, marked.size)
        assertTrue(marked.single(), marked.single().startsWith("app/build.gradle.kts: forbiddenGroups.set("))
    }

    private companion object {
        const val DENYLIST_MARKER = "// network-scan: denylist"
        val TEXT_EXTENSIONS = listOf(".kt", ".kts", ".gradle", ".java", ".toml", ".xml")
    }
}
