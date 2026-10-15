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
 *   outside `core/program/src/…/core/program/net/`, uses a network or remote-media
 *   API: `java.net` (except `URI`), `javax.net`, `URLConnection`, `openConnection`,
 *   `openStream`, `.toURL(`, `android.net.http`, `android.webkit` / WebView,
 *   `DownloadManager`, MediaPlayer, VideoView, ExoPlayer / media3, socket channels,
 *   OkHttp, Retrofit, Ktor, Volley, Cronet, …;
 * - any manifest of any source set other than core:program's declares the
 *   INTERNET permission, or any manifest allows cleartext traffic;
 * - a resource (any source set) is a network security config outside
 *   `app/src/main/res/xml/`, trusts user certificates, allows cleartext or has
 *   `debug-overrides`, or a layout uses a WebView;
 * - a build file or the version catalog pulls in a network, image-loading,
 *   media, WebView, Firebase, Sentry or Play services library.
 *
 * The scan is a plain text search, so a mention in a comment counts too. Its
 * inputs are declared in core/program's build file, so it reruns when any
 * module changes. The merged manifest's permissions are checked in app
 * (`AppPermissionsTest`), the release dependencies by `:app:checkReleaseDependencyGroups`.
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
        assertTrue(scan.scannedFiles.any { it.path.endsWith("res/xml/network_security_config.xml") })
        assertTrue(scan.scannedFiles.any { it.path.endsWith("libs.versions.toml") })

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
            "val connection: URLConnection = x",
            "val c = uri.toURL()",
            "url.openConnection()",
            "url.openStream().use { }",
            "import android.net.http.HttpEngine",
            "import android.net.Network",
            "import android.net.SSLCertificateSocketFactory",
            "import android.webkit.WebView",
            "import android.webkit.URLUtil",
            "AndroidView(factory = { WebView(it) })",
            "import android.app.DownloadManager",
            "val player = MediaPlayer()",
            "MediaPlayer.create(context, uri)",
            "VideoView(context).setVideoURI(uri)",
            "import com.google.android.exoplayer2.ExoPlayer",
            "import androidx.media3.exoplayer.ExoPlayer",
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
            "resolver.openInputStream(uri)",
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
    fun resourceRulesCatchWeakNetworkConfigsAndWebViews() {
        for (xml in listOf("<certificates src=\"user\" />", "<certificates src = 'user'/>", "<base-config cleartextTrafficPermitted=\"true\">", "<debug-overrides>")) {
            assertTrue(xml, NetworkScan.resourceViolations("xml", xml).isNotEmpty())
        }
        assertEquals(emptyList<String>(), NetworkScan.resourceViolations("xml", "<certificates src=\"system\" /><!-- no debug-overrides -->"))
        assertTrue(NetworkScan.resourceViolations("layout", "<WebView android:id=\"@+id/web\" />").isNotEmpty())
        assertTrue(NetworkScan.resourceViolations("layout-land", "<android.webkit.WebView />").isNotEmpty())
        assertEquals(emptyList<String>(), NetworkScan.resourceViolations("layout", "<TextView />"))
    }

    @Test
    fun buildRulesCatchNetworkAndMediaLibraries() {
        val caught = listOf(
            "okhttp = { module = \"com.squareup.okhttp3:okhttp\" }",
            "implementation(\"com.squareup.retrofit2:retrofit:2.11.0\")",
            "io.ktor:ktor-client-android",
            "coil-network-okhttp",
            "io.coil-kt.coil3:coil-compose",
            "com.github.bumptech.glide:glide",
            "com.squareup.picasso:picasso",
            "com.facebook.fresco:fresco",
            "androidx.media3:media3-exoplayer",
            "com.google.android.exoplayer:exoplayer",
            "com.google.firebase:firebase-bom",
            "io.sentry:sentry-android",
            "androidx.webkit:webkit",
            "com.google.android.gms:play-services-base",
            "com.android.volley:volley",
        )
        for (line in caught) assertTrue(line, NetworkScan.buildViolations(line).isNotEmpty())
        assertEquals(emptyList<String>(), NetworkScan.buildViolations("implementation(libs.kotlinx.serialization.json)"))
        // Only a denylist line with the marker is accepted; the marker elsewhere on another line doesn't help.
        assertEquals(emptyList<String>(), NetworkScan.buildViolations("forbidden.set(setOf(\"androidx.webkit\")) // network-scan: denylist"))
        assertTrue(NetworkScan.buildViolations("// network-scan: denylist\nimplementation(\"androidx.webkit:webkit:1.12.0\")").isNotEmpty())
        assertTrue(NetworkScan.buildViolations("implementation(\"androidx.webkit:webkit:1.12.0\") // network-scan: denylist and more").isNotEmpty())
    }

    @Test
    fun theScanFindsAViolationInAnyModuleButTheAllowedPackage() {
        val root = temp.root
        fun write(path: String, text: String) = File(root, path).apply { parentFile!!.mkdirs() }.writeText(text)
        write("settings.gradle.kts", "rootProject.name = \"Cinexperience\"")
        write("feature/x/src/main/kotlin/a/A.kt", "import java.net.URL")
        write("feature/x/src/debug/java/a/B.java", "import javax.net.ssl.SSLContext;")
        write("feature/x/src/test/kotlin/a/ATest.kt", "import java.net.URL") // test code may
        write("feature/x/src/main/AndroidManifest.xml", "<uses-permission android:name=\"android.permission.INTERNET\"/>")
        write("feature/x/src/test/AndroidManifest.xml", "<uses-permission android:name=\"android.permission.INTERNET\"/>")
        write("feature/x/build.gradle.kts", "implementation(\"com.squareup.okhttp3:okhttp:4.12.0\")")
        write("feature/x/src/release/res/xml/network_security_config.xml", "<network-security-config/>")
        write("feature/x/src/debug/res/xml/other.xml", "<debug-overrides><trust-anchors><certificates src=\"user\"/></trust-anchors></debug-overrides>")
        write("feature/x/src/main/res/layout/screen.xml", "<WebView android:id=\"@+id/web\"/>")
        write("app/src/main/res/xml/network_security_config.xml", "<base-config cleartextTrafficPermitted=\"false\"/>") // the allowed place
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
                "feature/x/src/test/AndroidManifest.xml",
                "feature/x/src/main/kotlin/a/A.kt",
                "feature/x/src/release/res/xml/network_security_config.xml",
                "feature/x/src/debug/res/xml/other.xml",
                "feature/x/src/main/res/layout/screen.xml",
            ),
            violations.map { it.substringBefore(':') }.toSet(),
        )
    }
}

/** The text scan behind [NetworkConfinementTest]. */
internal object NetworkScan {
    private const val NETWORK_MODULE = "core/program"
    private const val NETWORK_PACKAGE_DIR = "/io/github/katznmeik/cinexperience/core/program/net/"
    private const val ALLOWED_NETWORK_CONFIG_DIR = "app/src/main/res/xml/"

    private val SOURCE_RULES: List<Pair<String, Regex>> = listOf(
        "java.net (only URI is allowed)" to Regex("""\bjava\.net\.(?!(?:URI|URISyntaxException)\b)"""),
        "javax.net" to Regex("""\bjavax\.net\b"""),
        "URLConnection" to Regex("""URLConnection\b"""),
        "openConnection / openStream / toURL" to Regex("""\bopenConnection\b|\bopenStream\b|\.toURL\s*\("""),
        "android.net.http" to Regex("""\bandroid\.net\.http\b"""),
        "android.net network classes" to Regex("""\bandroid\.net\.(?:Network|SSL\w*|TrafficStats|Proxy)\b"""),
        "WebView" to Regex("""\bandroid\.webkit\b|\bWebView"""),
        "DownloadManager" to Regex("""\bDownloadManager\b"""),
        "MediaPlayer / VideoView" to Regex("""\bMediaPlayer\b|\bVideoView\b"""),
        "ExoPlayer / media3" to Regex("""(?i)\bexoplayer|\bmedia3\b"""),
        "socket channels" to Regex("""\bjava\.nio\.channels\.\w*(?:Socket|Datagram)\w*"""),
        "OkHttp" to Regex("""(?i)\bokhttp"""),
        "Retrofit" to Regex("""(?i)\bretrofit"""),
        "Ktor" to Regex("""(?i)\bio\.ktor\b|\bktor\b"""),
        "Volley" to Regex("""(?i)\bvolley\b"""),
        "Cronet" to Regex("""(?i)\bcronet|\borg\.chromium\.net\b"""),
        "Apache HTTP" to Regex("""\borg\.apache\.http\b"""),
        "Netty" to Regex("""\bio\.netty\b"""),
    )

    private val BUILD_RULE = Regex(
        """(?i)\b(?:okhttp3?|retrofit2?|ktor|volley|cronet|netty|glide|picasso|fresco|exoplayer|media3|firebase|sentry|play-services)\b""" +
            """|\bcoil\b|coil-|coil3|io\.coil-kt|androidx\.webkit|com\.google\.android\.gms""",
    )
    private val INTERNET = Regex("""android\.permission\.INTERNET\b""")
    private val CLEARTEXT_MANIFEST = Regex("""usesCleartextTraffic\s*=\s*["']true["']""")
    private val XML_RULES: List<Pair<String, Regex>> = listOf(
        "user certificates trusted" to Regex("""\bsrc\s*=\s*["']user["']"""),
        "cleartext allowed" to Regex("""cleartextTrafficPermitted\s*=\s*["']true["']"""),
        "debug-overrides" to Regex("""<\s*debug-overrides\b"""),
    )
    private val LAYOUT_WEBVIEW = Regex("""WebView\b""")
    private val NETWORK_CONFIG_NAME = Regex("""network_security_config.*\.xml""")

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
        CLEARTEXT_MANIFEST.findAll(text).forEach { add("${lineOf(text, it.range.first)}: cleartext traffic") }
    }

    /** Rules for a resource file in the resource folder [resourceDir] (e.g. `xml`, `layout-land`). */
    fun resourceViolations(resourceDir: String, text: String): List<String> = when {
        resourceDir == "xml" || resourceDir.startsWith("xml-") -> matches(text, XML_RULES)
        resourceDir == "layout" || resourceDir.startsWith("layout-") ->
            LAYOUT_WEBVIEW.findAll(text).map { "${lineOf(text, it.range.first)}: WebView in a layout" }.toList()
        else -> emptyList()
    }

    /**
     * Network libraries named in a build file. A line ending with [DENYLIST_MARKER]
     * is a list of forbidden groups (`:app:checkReleaseDependencyGroups`) and is skipped.
     */
    fun buildViolations(text: String): List<String> =
        BUILD_RULE.findAll(text)
            .filterNot { match -> lineAt(text, match.range.first).trimEnd().endsWith(DENYLIST_MARKER) }
            .map { "${lineOf(text, it.range.first)}: network or media library '${it.value}'" }
            .toList()

    private const val DENYLIST_MARKER = "// network-scan: denylist"

    private fun lineAt(text: String, index: Int): String {
        val start = text.lastIndexOf('\n', index - 1) + 1
        val end = text.indexOf('\n', index).let { if (it < 0) text.length else it }
        return text.substring(start, end)
    }

    fun scan(root: File): Result {
        val scanned = mutableListOf<File>()
        val violations = mutableListOf<String>()
        walk(root, root) { file, relative ->
            val name = file.name
            val sourceSet = sourceSetOf(relative)
            val found: List<String> = when {
                name == "AndroidManifest.xml" && sourceSet != null ->
                    manifestViolations(file.readText(), moduleOf(root, file) == NETWORK_MODULE)
                sourceSet != null && file.extension == "xml" && relative.contains("/src/$sourceSet/res/") -> buildList {
                    if (NETWORK_CONFIG_NAME.matches(name) && !relative.startsWith(ALLOWED_NETWORK_CONFIG_DIR)) {
                        add("1: network security config outside $ALLOWED_NETWORK_CONFIG_DIR")
                    }
                    addAll(resourceViolations(file.parentFile.name, file.readText()))
                }
                name.endsWith(".gradle.kts") || name == "libs.versions.toml" -> buildViolations(file.readText())
                sourceSet != null && !isTestSet(sourceSet) && file.extension in SOURCE_EXTENSIONS -> {
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

    /** The source set of `…/src/<set>/…`, or `null` outside sources. */
    private fun sourceSetOf(relative: String): String? {
        val src = if (relative.startsWith("src/")) 0 else relative.indexOf("/src/").takeIf { it >= 0 }?.plus(1) ?: return null
        return relative.substring(src + 4).substringBefore('/').takeIf { it.isNotEmpty() && relative.length > src + 4 + it.length }
    }

    private fun isTestSet(set: String): Boolean = set.startsWith("test") || set.startsWith("androidTest")

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
