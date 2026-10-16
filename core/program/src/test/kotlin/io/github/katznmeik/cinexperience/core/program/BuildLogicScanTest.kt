package io.github.katznmeik.cinexperience.core.program

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The release dependency check's task type (`CheckDependencyGroupsTask`) lives in
 * build-logic, which [NetworkConfinementTest] scans like a build file; its lists stay
 * in `app/build.gradle.kts`. The build-file scan accepts a line that names a forbidden
 * library only when it ends with the denylist marker, so the marker may appear on
 * one line only: the release check's list of forbidden groups.
 */
class BuildLogicScanTest {

    private val root = NetworkScan.repositoryRoot()
    private val scanned = NetworkScan.scan(root).scannedFiles.map { it.relativeTo(root).invariantSeparatorsPath }

    @Test
    fun theOnlyDenylistLineIsTheReleaseCheckInTheAppBuildFile() {
        val textFiles = scanned.filter { path -> TEXT_EXTENSIONS.any { path.endsWith(it) } }
        assertTrue(textFiles.toString(), "app/build.gradle.kts" in textFiles)
        assertTrue(textFiles.toString(), textFiles.any { it.endsWith("/buildlogic/CheckDependencyGroupsTask.kt") })
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
