package io.github.katznmeik.cinexperience.core.program

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Spec §11.1, Network & security rule 3: only `core:program` contains network
 * code, and only its `net/` package (the HTTPS transport and the download policy).
 * Fails the build if
 *
 * - any non-test source (`src/<set>/…` except test source sets) of any module,
 *   outside `core/program/src/…/core/program/net/`, uses a network API: `java.net`
 *   (except `URI`), `javax.net`, `android.net.http`, `android.webkit` / WebView,
 *   `DownloadManager`, socket channels, OkHttp, Retrofit, Ktor, Volley, Cronet, …;
 * - any module other than `core:program` declares the INTERNET permission, or any
 *   manifest allows cleartext traffic;
 * - a build file or the version catalog pulls in a network library.
 *
 * The scan is a plain text search, so a mention in a comment counts too.
 */
class NetworkConfinementTest {

    @get:Rule
    val temp = TemporaryFolder()

    // --- The real repository --------------------------------------------------------------------

    @Test
    fun onlyCoreProgramContainsNetworkCode() {
        val root = NetworkScan.repositoryRoot()
        val scan = NetworkScan.scan(root)

        // The scan really saw the project, so an empty result means something.
        val modules = scan.scannedFiles.map { NetworkScan.moduleOf(root, it) }.toSet()
        assertTrue(modules.toString(), modules.containsAll(listOf("app", "core/program", "core/data", "core/model", "feature/cinemas")))
        assertTrue("${scan.scannedFiles.size} files", scan.scannedFiles.size > 100)
        assertTrue(scan.scannedFiles.any { it.path.endsWith("net/HttpsProgramTransport.kt") })

        assertEquals("Network code outside core:program/net:\n" + scan.violations.joinToString("\n"), emptyList<String>(), scan.violations)
    }

    @Test
    fun coreProgramDeclaresTheInternetPermission() {
        val manifest = File(NetworkScan.repositoryRoot(), "core/program/src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android.permission.INTERNET"))
    }

    // --- Self-tests: the scanner catches violations ----------------------------------------------

    @Test
    fun sourceRulesCatchNetworkApis() {
        val caught = listOf(
            "import java.net.URL",
            "import java.net.HttpURLConnection",
            "import java.net.Socket",
            "import java.net.*",
            "val c = java.net.URL(\"https://example.com\").openConnection()",
            "Class.forName(\"java.net.URL\")",
            "import java.net.http.HttpClient",
            "import java.net.CookieHandler",
            "import java.net.InetAddress",
            "import javax.net.ssl.HttpsURLConnection",
            "import javax.net.SocketFactory",
            "import android.net.http.HttpEngine",
            "import android.net.Network",
            "import android.net.SSLCertificateSocketFactory",
            "import android.webkit.WebView",
            "AndroidView(factory = { WebView(it) })",
            "import android.app.DownloadManager",
            "import java.nio.channels.SocketChannel",
            "import java.nio.channels.AsynchronousSocketChannel",
            "import okhttp3.OkHttpClient",
            "import retrofit2.Retrofit",
            "import io.ktor.client.HttpClient",
            "import com.android.volley.toolbox.Volley",
            "import org.chromium.net.CronetEngine",
            "import org.apache.http.client.HttpClient",
            "import io.netty.channel.Channel",
        )
        for (line in caught) {
            assertTrue(line, NetworkScan.sourceViolations(line).isNotEmpty())
        }
        val allowed = listOf(
            "import java.net.URI",
            "import java.net.URISyntaxException",
            "import android.net.Uri",
            "import android.net.NetworkCapabilities",
            "val uri = Uri.parse(text)",
            "import java.nio.channels.FileChannel",
            "// the network is never touched here",
        )
        for (line in allowed) {
            assertEquals(line, emptyList<String>(), NetworkScan.sourceViolations(line))
        }
    }

    @Test
    fun manifestRulesCatchTheInternetPermissionAndCleartext() {
        assertTrue(NetworkScan.manifestViolations("<uses-permission android:name=\"android.permission.INTERNET\" />", networkModule = false).isNotEmpty())
        assertEquals(emptyList<String>(), NetworkScan.manifestViolations("<uses-permission android:name=\"android.permission.INTERNET\" />", networkModule = true))
        assertTrue(NetworkScan.manifestViolations("<application android:usesCleartextTraffic=\"true\">", networkModule = true).isNotEmpty())
        assertEquals(emptyList<String>(), NetworkScan.manifestViolations("<uses-permission android:name=\"android.permission.CAMERA\" />", networkModule = false))
    }

    @Test
    fun buildRulesCatchNetworkLibraries() {
        for (line in listOf("okhttp = { module = \"com.squareup.okhttp3:okhttp\" }", "implementation(\"com.squareup.retrofit2:retrofit:2.11.0\")", "io.ktor:ktor-client-android", "coil-network-okhttp", "com.android.volley:volley")) {
            assertTrue(line, NetworkScan.buildViolations(line).isNotEmpty())
        }
        assertEquals(emptyList<String>(), NetworkScan.buildViolations("implementation(libs.kotlinx.serialization.json)"))
    }

    @Test
    fun theScanFindsAViolationInAnyModuleButTheAllowedPackage() {
        val root = temp.root
        fun write(path: String, text: String) = File(root, path).apply { parentFile!!.mkdirs() }.writeText(text)
        write("settings.gradle.kts", "rootProject.name = \"Cinexperience\"")
        write("feature/x/src/main/kotlin/a/A.kt", "import java.net.URL")
        write("feature/x/src/debug/java/a/B.java", "import javax.net.ssl.SSLContext;")
        write("feature/x/src/test/kotlin/a/ATest.kt", "import java.net.URL") // tests may
        write("feature/x/src/main/AndroidManifest.xml", "<uses-permission android:name=\"android.permission.INTERNET\"/>")
        write("feature/x/build.gradle.kts", "implementation(\"com.squareup.okhttp3:okhttp:4.12.0\")")
        write("core/program/src/main/kotlin/io/github/katznmeik/cinexperience/core/program/net/T.kt", "import java.net.URL")
        write("core/program/src/main/kotlin/io/github/katznmeik/cinexperience/core/program/Parser.kt", "import java.net.Socket")
        write("core/program/src/main/AndroidManifest.xml", "<uses-permission android:name=\"android.permission.INTERNET\"/>")
        write("feature/x/build/generated/G.kt", "import java.net.URL") // build output is skipped
        write(".hidden/src/main/kotlin/H.kt", "import java.net.URL") // e.g. other worktrees

        val violations = NetworkScan.scan(root).violations
        assertEquals(
            violations.joinToString("\n"),
            setOf(
                "core/program/src/main/kotlin/io/github/katznmeik/cinexperience/core/program/Parser.kt",
                "feature/x/build.gradle.kts",
                "feature/x/src/debug/java/a/B.java",
                "feature/x/src/main/AndroidManifest.xml",
                "feature/x/src/main/kotlin/a/A.kt",
            ),
            violations.map { it.substringBefore(':') }.toSet(),
        )
    }
}

/** The text scan behind [NetworkConfinementTest]. */
internal object NetworkScan {
    private const val NETWORK_MODULE = "core/program"
    private const val NETWORK_PACKAGE_DIR = "/io/github/katznmeik/cinexperience/core/program/net/"

    private val SOURCE_RULES: List<Pair<String, Regex>> = listOf(
        "java.net (only URI is allowed)" to Regex("""\bjava\.net\.(?!(?:URI|URISyntaxException)\b)"""),
        "javax.net" to Regex("""\bjavax\.net\b"""),
        "android.net.http" to Regex("""\bandroid\.net\.http\b"""),
        "android.net network classes" to Regex("""\bandroid\.net\.(?:Network|SSL\w*|TrafficStats|Proxy)\b"""),
        "WebView" to Regex("""\bandroid\.webkit\b|\bWebView"""),
        "DownloadManager" to Regex("""\bDownloadManager\b"""),
        "socket channels" to Regex("""\bjava\.nio\.channels\.\w*(?:Socket|Datagram)\w*"""),
        "OkHttp" to Regex("""(?i)\bokhttp"""),
        "Retrofit" to Regex("""(?i)\bretrofit"""),
        "Ktor" to Regex("""(?i)\bio\.ktor\b|\bktor\b"""),
        "Volley" to Regex("""(?i)\bvolley\b"""),
        "Cronet" to Regex("""(?i)\bcronet|\borg\.chromium\.net\b"""),
        "Apache HTTP" to Regex("""\borg\.apache\.http\b"""),
        "Netty" to Regex("""\bio\.netty\b"""),
    )

    private val BUILD_RULE = Regex("""(?i)\b(?:okhttp3?|retrofit2?|ktor|volley|cronet|netty)\b|coil-network""")
    private val INTERNET = Regex("""android\.permission\.INTERNET\b""")
    private val CLEARTEXT = Regex("""usesCleartextTraffic\s*=\s*"true"""")

    private val SKIPPED_DIRS = setOf("build", "node_modules")
    private val SOURCE_EXTENSIONS = setOf("kt", "java", "kts")

    class Result(val scannedFiles: List<File>, val violations: List<String>)

    /** The Cinexperience checkout this test runs in (tests run in their module's folder). */
    fun repositoryRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val settings = File(dir, "settings.gradle.kts")
            if (settings.isFile && settings.readText().contains("rootProject.name = \"Cinexperience\"")) return dir
            dir = dir.parentFile
        }
        error("Repository root not found from ${File("").absolutePath}")
    }

    /** The module folder of [file], e.g. `core/program`, or `.` for root files. */
    fun moduleOf(root: File, file: File): String {
        val path = file.relativeTo(root).invariantSeparatorsPath
        val src = path.indexOf("/src/")
        return when {
            src >= 0 -> path.substring(0, src)
            path.contains('/') -> path.substringBeforeLast('/')
            else -> "."
        }
    }

    fun sourceViolations(text: String): List<String> = matches(text, SOURCE_RULES)

    fun manifestViolations(text: String, networkModule: Boolean): List<String> = buildList {
        if (!networkModule) INTERNET.findAll(text).forEach { add("${lineOf(text, it.range.first)}: INTERNET permission") }
        CLEARTEXT.findAll(text).forEach { add("${lineOf(text, it.range.first)}: cleartext traffic") }
    }

    fun buildViolations(text: String): List<String> =
        BUILD_RULE.findAll(text).map { "${lineOf(text, it.range.first)}: network library '${it.value}'" }.toList()

    fun scan(root: File): Result {
        val scanned = mutableListOf<File>()
        val violations = mutableListOf<String>()
        walk(root, root) { file, relative ->
            val name = file.name
            val inSources = isNonTestSource(relative)
            val found: List<String> = when {
                name == "AndroidManifest.xml" && inSources -> manifestViolations(file.readText(), moduleOf(root, file) == NETWORK_MODULE)
                name.endsWith(".gradle.kts") || name == "libs.versions.toml" -> buildViolations(file.readText())
                inSources && file.extension in SOURCE_EXTENSIONS -> {
                    val allowed = moduleOf(root, file) == NETWORK_MODULE && relative.contains(NETWORK_PACKAGE_DIR)
                    if (allowed) emptyList() else sourceViolations(file.readText())
                }
                else -> return@walk
            }
            scanned += file
            found.forEach { violations += "$relative:$it" }
        }
        return Result(scanned, violations)
    }

    /** `…/src/<set>/…` with a set that isn't a test set (main, debug, release, …). */
    private fun isNonTestSource(relative: String): Boolean {
        val src = relative.indexOf("src/").takeIf { it == 0 || relative.getOrNull(it - 1) == '/' } ?: return false
        val set = relative.substring(src + 4).substringBefore('/')
        return set.isNotEmpty() && !set.startsWith("test") && !set.startsWith("androidTest")
    }

    private fun walk(root: File, dir: File, visit: (File, String) -> Unit) {
        for (child in dir.listFiles().orEmpty().sortedBy { it.name }) {
            if (child.isDirectory) {
                // Hidden folders (.git, .gradle, other worktrees under .claude, …) and build outputs are not sources.
                if (child.name.startsWith(".") || child.name in SKIPPED_DIRS) continue
                walk(root, child, visit)
            } else {
                visit(child, child.relativeTo(root).invariantSeparatorsPath)
            }
        }
    }

    private fun matches(text: String, rules: List<Pair<String, Regex>>): List<String> = buildList {
        for ((name, regex) in rules) {
            regex.findAll(text).forEach { add("${lineOf(text, it.range.first)}: $name ('${it.value}')") }
        }
    }

    private fun lineOf(text: String, index: Int): Int = text.substring(0, index).count { it == '\n' } + 1
}
