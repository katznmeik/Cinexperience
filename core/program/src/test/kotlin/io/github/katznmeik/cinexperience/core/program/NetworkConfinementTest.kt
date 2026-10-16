package io.github.katznmeik.cinexperience.core.program

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * - any non-test source (`src/<set>/…` except the test sets `test`, `androidTest`
 *   and `test<Variant>` / `androidTest<Variant>`; `testFixtures` and a set such as
 *   `testing` are scanned) of any module, outside `core/program/src/…/core/program/net/`,
 *   uses a network or remote-media API: `java.net` (except `URI`), `javax.net`,
 *   `URLConnection`, `openConnection`, `openStream`, `.toURL(`, `android.net.http`,
 *   `android.webkit` / WebView, `DownloadManager`, MediaPlayer, VideoView, ExoPlayer /
 *   media3, socket channels, OkHttp, Retrofit, Ktor, Volley, Cronet, …, or (not in
 *   the build-logic plugins, which run only at build time) native or other code from
 *   outside the sources: `System.loadLibrary` / `System.load`, `exec(` /
 *   `ProcessBuilder`, `dalvik.system` (dex class loaders);
 * - any manifest of any source set other than core:program's declares the
 *   INTERNET permission, or any manifest allows cleartext traffic;
 * - a resource (any source set) is a network security config outside
 *   `app/src/main/res/xml/`, trusts user certificates, allows cleartext or has
 *   `debug-overrides`, or a layout uses a WebView;
 * - a build file (`*.gradle.kts`, Groovy `*.gradle`, the convention plugins in
 *   `build-logic/`) or a version catalog (`*.toml`) pulls in a network,
 *   image-loading, media, WebView, Firebase, Sentry or Play services library, a
 *   file dependency (`implementation(files(…))`, `fileTree(…)`), a native build
 *   (`externalNativeBuild`) or source folders of its own (`srcDir`, `srcDirs`,
 *   `setSrcDirs`, `srcFile`, `….directories` except the assets', the variant API's
 *   `addStaticSourceDirectory` / `addGeneratedSourceDirectory` / `add…ManifestFile`),
 *   which this scan wouldn't see;
 * - a build or settings file declares a repository other than `google()`,
 *   `mavenCentral()` and `gradlePluginPortal()` (`maven`, `mavenLocal`, `flatDir`,
 *   `ivy`, `jcenter`, or another `url` for one of them), which could serve a fake
 *   `androidx.*` library. The one exception is the optional init script
 *   `scripts/maven-central-mirror.init.gradle` (installed outside the repository by
 *   `scripts/setup-android-sdk.sh`, which isn't scanned): its `maven { url = … }`
 *   may only point at Google's Maven Central mirror;
 * - a module's build file applies none of `cinex.android.application`, `.library`,
 *   `.feature` and `cinex.jvm.library`, the convention plugins that refuse
 *   dependencies on files;
 * - a compiled library or code file (`.jar`, `.aar`, `.so`, `.dex`, `.class`, in
 *   any case) is anywhere in the tree (outside hidden folders and build outputs),
 *   in any module or source set, except the Gradle wrapper's
 *   `gradle/wrapper/gradle-wrapper.jar`: such a file has no Maven group, so
 *   `:app:checkReleaseDependencyGroups` can't see it.
 *
 * The scan is a plain text search, so a mention in a comment counts too. Its
 * inputs are declared in core/program's build file, so it reruns when any
 * module changes. The merged manifests' permissions are checked in app
 * (`AppPermissionsTest`, debug and release), the release dependencies by
 * `:app:checkReleaseDependencyGroups`, and every module's build refuses a
 * dependency on files (build-logic `forbidFileDependencies`), however it is
 * written.
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
        assertTrue(scan.scannedFiles.any { it.path.endsWith("scripts/maven-central-mirror.init.gradle") }) // Groovy
        assertTrue(scan.scannedFiles.any { it.path.endsWith("build-logic/convention/src/main/kotlin/AndroidApplicationConventionPlugin.kt") })
        assertTrue(scan.scannedFiles.any { it.path.endsWith("gradle/wrapper/gradle-wrapper.jar") }) // the one allowed binary

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
    fun sourceRulesCatchNativeCodeProcessesAndDexLoading() {
        val caught = listOf(
            "System.loadLibrary(\"vendor\")",
            "init { System.loadLibrary(\"vendor\") }",
            "System.load(\"/data/local/tmp/libvendor.so\")",
            "java.lang.System.load(path)",
            "listOf(\"vendor\").forEach(System::loadLibrary)",
            "Runtime.getRuntime().loadLibrary(\"vendor\")",
            "Runtime.getRuntime().load(path)",
            "Runtime.getRuntime().exec(\"ping example.com\")",
            "val process = runtime.exec(arrayOf(\"sh\", \"-c\", command))",
            "ProcessBuilder(\"sh\", \"-c\", command).start()",
            "new ProcessBuilder(command).start();",
            "import dalvik.system.DexClassLoader",
            "val loader = dalvik.system.InMemoryDexClassLoader(buffer, parent)",
            "Class.forName(\"dalvik.system.PathClassLoader\")",
        )
        for (line in caught) {
            assertTrue(line, NetworkScan.sourceViolations(line).isNotEmpty())
            // Build-time code (the build-logic plugins) may.
            assertEquals(line, emptyList<String>(), NetworkScan.sourceViolations(line, appCode = false))
        }
        // Network APIs stay forbidden there too.
        assertTrue(NetworkScan.sourceViolations("import java.net.URL", appCode = false).isNotEmpty())
        val allowed = listOf(
            "executor.execute(task)",
            "db.execSQL(\"DELETE FROM visit\")",
            "ServiceLoader.load(Plugin::class.java)",
            "val loaded = repository.load()",
            "ProcessLifecycleOwner.get()",
            "// The photo is loaded on a background thread.",
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
    fun buildRulesCatchFileDependenciesAndNativeBuilds() {
        val caught = listOf(
            // Kotlin DSL
            "implementation(files(\"libs/vendor.aar\"))",
            "implementation(fileTree(mapOf(\"dir\" to \"libs\", \"include\" to listOf(\"*.jar\"))))",
            "releaseImplementation(project.files(\"/opt/vendor/vendor.jar\"))",
            "api(rootProject.layout.files(\"../vendor.jar\"))",
            "runtimeOnly(files(downloadVendor))",
            "\"implementation\"(files(\"libs/vendor.jar\"))",
            "dependencies.add(\"implementation\", files(\"libs/vendor.jar\"))",
            // Groovy
            "implementation files('libs/vendor.aar')",
            "implementation fileTree(dir: 'libs', include: ['*.jar'])",
            "compileOnly files(\"libs/vendor.jar\")",
            // Native code built by the module
            "externalNativeBuild { cmake { path(\"src/main/cpp/CMakeLists.txt\") } }",
            "android.externalNativeBuild.ndkBuild.path = file(\"Android.mk\")",
        )
        for (line in caught) assertTrue(line, NetworkScan.buildViolations(line).isNotEmpty())
        val allowed = listOf(
            "implementation(projects.core.model)",
            "api(libs.kotlinx.coroutines.core)",
            "testImplementation(libs.kotlinx.coroutines.test)",
            "val sources = fileTree(rootDir) { include(\"**/*.kt\") }",
            "inputs.files(sources, resources)",
            "from(files(\"../gradle/libs.versions.toml\"))",
            "storeFile = file(releaseKeystorePath!!)",
        )
        for (line in allowed) assertEquals(line, emptyList<String>(), NetworkScan.buildViolations(line))
    }

    @Test
    fun buildRulesCatchSourceFoldersOutsideTheScan() {
        val caught = listOf(
            // Kotlin DSL
            "android.sourceSets.getByName(\"main\").java.srcDir(\"extra\")",
            "sourceSets[\"main\"].kotlin.srcDirs(\"src/test/kotlin\")",
            "sourceSets.named(\"main\") { java.setSrcDirs(listOf(\"elsewhere\")) }",
            "main { jniLibs.srcDirs(\"libs\") }",
            // The Android plugin's variant API
            "variant.sources.java?.addStaticSourceDirectory(\"extra\")",
            "variant.sources.kotlin?.addGeneratedSourceDirectory(generate, Generate::outputDir)",
            "variant.sources.jniLibs?.addStaticSourceDirectory(\"libs\")",
            "variant.sources.manifests.addStaticManifestFile(\"other/AndroidManifest.xml\")",
            "variant.sources.manifests.addGeneratedManifestFile(generate, Generate::manifest)",
            "manifest.srcFile(\"other/AndroidManifest.xml\")",
            "sourceSets.getByName(\"main\").kotlin.directories.add(\"extra\")",
            "java.directories += \"extra\"",
            // Groovy
            "java.srcDirs = ['extra']",
            "main { java { srcDir 'src/test/kotlin' } }",
            "kotlin.srcDirs += 'extra'",
        )
        for (line in caught) assertTrue(line, NetworkScan.buildViolations(line).isNotEmpty())
        val allowed = listOf(
            // The Room plugin: the schemas as test assets.
            "sourceSets.getByName(\"test\").assets.directories.add(schemaDir)",
            "val schemaDir = \"\$projectDir/schemas\"",
            "val dir = layout.projectDirectory.dir(\"schemas\")",
            "room { schemaDirectory(schemaDir) }",
        )
        for (line in allowed) assertEquals(line, emptyList<String>(), NetworkScan.buildViolations(line))
    }

    @Test
    fun buildRulesAllowOnlyTheStandardRepositories() {
        val caught = listOf(
            // Kotlin DSL
            "maven { url = uri(\"https://repo.example.com/maven2/\") }",
            "maven(\"https://jitpack.io\")",
            "maven(url = \"https://repo.example.com/\")",
            "repositories.maven { setUrl(\"https://repo.example.com/\") }",
            "exclusiveContent { forRepository { maven(\"https://repo.example.com/\") }; filter { includeGroup(\"androidx.core\") } }",
            "mavenLocal()",
            "flatDir { dirs(\"libs\") }",
            "ivy { url = uri(\"https://ivy.example.com/\") }",
            "jcenter()",
            // Another address for an allowed one
            "google { url = uri(\"https://repo.example.com/\") }",
            "mavenCentral { setUrl(\"https://repo.example.com/\") }",
            // Groovy
            "maven { url 'https://jitpack.io' }",
            "maven { url('https://jitpack.io') }",
            "flatDir dirs: 'libs'",
        )
        for (line in caught) assertTrue(line, NetworkScan.buildViolations(line).isNotEmpty())
        val allowed = listOf(
            "google()",
            "mavenCentral()",
            "gradlePluginPortal()",
            // settings.gradle.kts and build-logic: only Google's groups from google().
            "google {\n    content {\n        includeGroupByRegex(\"androidx.*\")\n    }\n}",
            "repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)",
            "// Maven Central (repo1.maven.org) may answer HTTP 429",
            "a library has no Maven group",
        )
        for (line in allowed) assertEquals(line, emptyList<String>(), NetworkScan.buildViolations(line))
    }

    @Test
    fun onlyTheMirrorScriptMayAddGooglesMavenCentralMirror() {
        val mirror = File(NetworkScan.repositoryRoot(), "scripts/maven-central-mirror.init.gradle").readText()
        assertEquals(emptyList<String>(), NetworkScan.buildViolations(mirror, mirrorScript = true))
        // The same text anywhere else is a repository like any other.
        assertTrue(NetworkScan.buildViolations(mirror).isNotEmpty())
        // The mirror script itself may use no other address and no other repository.
        val mirrorUrl = "https://maven-central.storage-download.googleapis.com/maven2/"
        assertTrue(mirror.contains(mirrorUrl))
        for (tampered in listOf(
            mirror.replace(mirrorUrl, "https://repo.example.com/maven2/"),
            mirror.replace(mirrorUrl, "http://maven-central.storage-download.googleapis.com/maven2/"),
            mirror + "\nallprojects { repositories { maven { url = 'https://repo.example.com/' } } }",
            mirror + "\nallprojects { repositories { mavenLocal() } }",
            mirror + "\nallprojects { repositories { flatDir { dirs 'libs' } } }",
        )) {
            assertTrue(tampered.takeLast(80), NetworkScan.buildViolations(tampered, mirrorScript = true).isNotEmpty())
        }
    }

    @Test
    fun everyModuleAppliesAConventionPluginThatRefusesFileDependencies() {
        for (text in listOf(
            "plugins {\n    alias(libs.plugins.cinex.android.feature)\n    alias(libs.plugins.cinex.android.roborazzi)\n}",
            "plugins { alias(libs.plugins.cinex.android.library) }",
            "plugins { alias(libs.plugins.cinex.android.application) }",
            "plugins { id(\"cinex.jvm.library\") }",
            "plugins { id 'cinex.android.library' }",
        )) {
            assertEquals(text, emptyList<String>(), NetworkScan.moduleBuildFileViolations("feature/x/build.gradle.kts", text))
        }
        for (text in listOf(
            "plugins { id(\"com.android.library\") }",
            "plugins { alias(libs.plugins.android.library) }",
            "plugins { id(\"org.jetbrains.kotlin.jvm\") }",
            // Compose, Room and Roborazzi alone don't install the guard.
            "plugins { alias(libs.plugins.cinex.android.compose); id(\"com.android.library\") }",
            "",
        )) {
            assertTrue(text, NetworkScan.moduleBuildFileViolations("feature/x/build.gradle.kts", text).isNotEmpty())
            assertTrue(text, NetworkScan.moduleBuildFileViolations("core/x/build.gradle", text).isNotEmpty())
        }
        // The root build file and build-logic's own aren't modules of the app.
        assertEquals(emptyList<String>(), NetworkScan.moduleBuildFileViolations("build.gradle.kts", "plugins { alias(libs.plugins.android.library) apply false }"))
        assertEquals(emptyList<String>(), NetworkScan.moduleBuildFileViolations("build-logic/convention/build.gradle.kts", "plugins { `kotlin-dsl` }"))
        assertEquals(emptyList<String>(), NetworkScan.moduleBuildFileViolations("settings.gradle.kts", "include(\":app\")"))
    }

    @Test
    fun theScanFindsOtherRepositoriesAndModulesWithoutTheGuard() {
        val root = temp.root
        fun write(path: String, text: String) = File(root, path).apply { parentFile!!.mkdirs() }.writeText(text)
        val mirror = File(NetworkScan.repositoryRoot(), "scripts/maven-central-mirror.init.gradle").readText()
        write("settings.gradle.kts", "rootProject.name = \"Cinexperience\"\ndependencyResolutionManagement { repositories { google(); mavenCentral() } }")
        write("build.gradle.kts", "plugins { alias(libs.plugins.android.library) apply false }")
        write("scripts/maven-central-mirror.init.gradle", mirror) // the one allowed place
        write("scripts/other.init.gradle", mirror)
        write("build-logic/settings.gradle.kts", "dependencyResolutionManagement { repositories { maven(\"https://jitpack.io\") } }")
        write("feature/ok/build.gradle.kts", "plugins { alias(libs.plugins.cinex.android.feature) }")
        write("feature/repo/build.gradle.kts", "plugins { alias(libs.plugins.cinex.android.feature) }\nrepositories { mavenLocal() }")
        write("feature/plain/build.gradle.kts", "plugins { id(\"com.android.library\") }")
        write("core/groovy/build.gradle", "plugins { id 'org.jetbrains.kotlin.jvm' }")

        assertEquals(
            setOf(
                "build-logic/settings.gradle.kts",
                "core/groovy/build.gradle",
                "feature/plain/build.gradle.kts",
                "feature/repo/build.gradle.kts",
                "scripts/other.init.gradle",
            ),
            NetworkScan.scan(root).violations.map { it.substringBefore(':') }.toSet(),
        )
    }

    @Test
    fun binaryRulesCatchVendoredLibrariesAnywhere() {
        val caught = listOf(
            "feature/x/libs/vendor.aar",
            "feature/x/libs/vendor.jar",
            "app/src/main/jniLibs/arm64-v8a/libvendor.so",
            "app/src/main/assets/plugin.dex",
            "app/src/main/assets/plugin.jar",
            "core/data/src/test/resources/fixture.jar",
            "classes/a/A.class",
            "gradle/wrapper/other.jar",
            // In any case: a build reads them all the same.
            "feature/x/libs/Vendor.JAR",
            "app/src/main/jniLibs/arm64-v8a/libvendor.So",
        )
        for (path in caught) assertTrue(path, NetworkScan.binaryViolations(path).isNotEmpty())
        // An APK isn't a dependency (and an IDE may save one next to the sources); loading code from one needs dalvik.system.
        val allowed = listOf(
            "gradle/wrapper/gradle-wrapper.jar",
            "core/data/src/test/resources/backup/v1/manifest.json",
            "app/src/main/res/drawable/photo.png",
            "app/release/app-release.apk",
            "jar",
        )
        for (path in allowed) {
            assertEquals(path, emptyList<String>(), NetworkScan.binaryViolations(path))
        }
    }

    @Test
    fun onlyRealTestSetsAreSkipped() {
        for (set in listOf("test", "testDebug", "testRelease", "testFreeDebug", "androidTest", "androidTestDebug")) {
            assertTrue(set, NetworkScan.isTestSet(set))
        }
        // Sets that only look like tests are compiled into the app (or can be: testFixtures).
        for (set in listOf("main", "debug", "release", "testing", "tests", "testable", "test-debug", "testFixtures", "testFixturesDebug", "androidTesting", "screenshotTest")) {
            assertFalse(set, NetworkScan.isTestSet(set))
        }
    }

    @Test
    fun theScanFindsBinariesOtherBuildFilesAndSetsThatOnlyLookLikeTests() {
        val root = temp.root
        fun write(path: String, text: String) = File(root, path).apply { parentFile!!.mkdirs() }.writeText(text)
        write("settings.gradle.kts", "rootProject.name = \"Cinexperience\"")
        write("gradle/wrapper/gradle-wrapper.jar", "PK") // allowed
        write("feature/x/libs/vendor.aar", "PK")
        write("feature/z/libs/Vendor.JAR", "PK")
        write("feature/x/src/main/jniLibs/arm64-v8a/libvendor.so", "ELF")
        write("feature/x/src/test/resources/fixture.jar", "PK") // in a test set too
        write("feature/x/build/intermediates/compiled.jar", "PK") // build output is skipped
        write("feature/x/build.gradle", "dependencies { implementation 'com.squareup.okhttp3:okhttp:4.12.0' }") // Groovy
        write("feature/y/build.gradle.kts", "dependencies { implementation(fileTree(mapOf(\"dir\" to \"libs\"))) }")
        write("gradle/vendor.versions.toml", "okhttp = \"com.squareup.okhttp3:okhttp:4.12.0\"") // a second catalog
        write("build-logic/convention/src/main/kotlin/VendorPlugin.kt", "\"implementation\"(\"com.github.bumptech.glide:glide:4.16.0\")")
        write("build-logic/convention/src/main/kotlin/SourcesPlugin.kt", "sourceSets.getByName(\"main\").java.srcDir(\"extra\")")
        // Build-time code may start processes (e.g. for the build ID).
        write("build-logic/convention/src/main/kotlin/BuildIdPlugin.kt", "ProcessBuilder(\"git\", \"rev-parse\", \"HEAD\").start()")
        write("feature/x/src/testing/kotlin/a/A.kt", "import java.net.URL") // not a test set
        write("feature/x/src/testFixtures/kotlin/a/B.kt", "Runtime.getRuntime().exec(\"sh\")")
        write("feature/x/src/main/kotlin/a/C.kt", "init { System.loadLibrary(\"vendor\") }")
        write("feature/x/src/testDebug/kotlin/a/DTest.kt", "import java.net.URL") // a test set: may
        write("feature/x/src/androidTestDebug/kotlin/a/ETest.kt", "ProcessBuilder(\"sh\")") // a test set: may

        val scan = NetworkScan.scan(root)
        assertEquals(
            scan.violations.joinToString("\n"),
            setOf(
                "build-logic/convention/src/main/kotlin/SourcesPlugin.kt",
                "build-logic/convention/src/main/kotlin/VendorPlugin.kt",
                "feature/x/build.gradle",
                "feature/x/libs/vendor.aar",
                "feature/x/src/main/jniLibs/arm64-v8a/libvendor.so",
                "feature/x/src/main/kotlin/a/C.kt",
                "feature/x/src/test/resources/fixture.jar",
                "feature/x/src/testFixtures/kotlin/a/B.kt",
                "feature/x/src/testing/kotlin/a/A.kt",
                "feature/y/build.gradle.kts",
                "feature/z/libs/Vendor.JAR",
                "gradle/vendor.versions.toml",
            ),
            scan.violations.map { it.substringBefore(':') }.toSet(),
        )
        assertTrue(scan.scannedFiles.any { it.path.endsWith("gradle/wrapper/gradle-wrapper.jar") })
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
    private const val BUILD_LOGIC_DIR = "build-logic/"

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

    /**
     * Native code (whose sockets need no Java API), other programs and dex code loaded
     * at runtime. Only for the app's sources: the build-logic plugins run at build time
     * (and may start processes, e.g. for the build ID); they never reach the APK.
     */
    private val CODE_LOADING_RULES: List<Pair<String, Regex>> = listOf(
        "native libraries" to Regex("""\bloadLibrary\b|\bSystem\s*(?:\.|::)\s*load\b|\bgetRuntime\s*\(\s*\)\s*\.\s*load\b"""),
        "processes" to Regex("""\bProcessBuilder\b|\.\s*exec\s*\(|::\s*exec\b"""),
        "dex class loaders" to Regex("""\bdalvik\.system\b"""),
    )

    private val BUILD_RULE = Regex(
        """(?i)\b(?:okhttp3?|retrofit2?|ktor|volley|cronet|netty|glide|picasso|fresco|exoplayer|media3|firebase|sentry|play-services)\b""" +
            """|\bcoil\b|coil-|coil3|io\.coil-kt|androidx\.webkit|com\.google\.android\.gms""",
    )

    /**
     * Ways around the release dependency check and this scan: a dependency on files has
     * no Maven group (the build also refuses one, build-logic `forbidFileDependencies`),
     * native code is built here, and source folders outside `src/<set>/` aren't scanned
     * (the Room plugin adds a schema folder to the test assets with `assets.directories`).
     */
    private val BUILD_CODE_RULES: List<Pair<String, Regex>> = listOf(
        "file dependency" to Regex(
            """\b(?:\w*[Ii]mplementation|api|\w*Api|\w*[Rr]untimeOnly|\w*[Cc]ompileOnly)["']?\s*\(?\s*(?:\w+\s*\.\s*)*(?:files|fileTree)\s*\(""" +
                """|\badd\s*\(\s*["'][^"']*["']\s*,\s*(?:\w+\s*\.\s*)*(?:files|fileTree)\s*\(""",
        ),
        "native build" to Regex("""\bexternalNativeBuild\b"""),
        "custom source folder" to Regex(
            """\b(?:srcDirs?|setSrcDirs|srcFile)\b|(?<!\bassets)\.\s*directories\b""" +
                """|\badd(?:Static|Generated)(?:SourceDirectory|ManifestFile)\b""",
        ),
    )

    /**
     * A repository other than google(), mavenCentral() and gradlePluginPortal(), or another
     * address for one of them (their `{ … }` block may only filter the content).
     */
    private val REPOSITORY = Regex(
        """\bmaven\s*[({]|\bmavenLocal\b|\bflatDir\b|\bivy\s*[({]|\bjcenter\b""" +
            """|\b(?:url|setUrl|artifactUrls|setArtifactUrls)\s*(?:=|\(|["'])""",
    )

    /** The optional Maven Central mirror; outside the repository, `scripts/setup-android-sdk.sh` installs it. */
    private const val MIRROR_SCRIPT = "scripts/maven-central-mirror.init.gradle"
    private const val MIRROR_URL = "https://maven-central.storage-download.googleapis.com/maven2/"

    /** What [REPOSITORY] finds in [MIRROR_SCRIPT]: its `repos.maven {`, `url = mirrorUrl` and Robolectric's `…repo.url'`. */
    private val MIRROR_SCRIPT_MATCHES = setOf("maven {", "url =", "url'")
    private val ADDRESS = Regex("""\b[A-Za-z][A-Za-z0-9+.-]*://[^\s'"]*""")

    /** The convention plugins that refuse dependencies on files (build-logic `forbidFileDependencies`). */
    private val BASE_CONVENTION_PLUGIN = Regex("""(?:\blibs\.plugins\.|["'])cinex\.(?:android\.(?:application|library|feature)|jvm\.library)\b""")

    /** Compiled libraries and code; [ALLOWED_BINARY] is the only one the repository may contain. */
    private val BINARY_EXTENSIONS = setOf("jar", "aar", "so", "dex", "class")
    private const val ALLOWED_BINARY = "gradle/wrapper/gradle-wrapper.jar"

    /** `test`, `test<Variant>`, `androidTest`, `androidTest<Variant>`; not `testFixtures` (it can be put on the release classpath). */
    private val TEST_SET = Regex("""(?:test(?!Fixtures)|androidTest)(?:[A-Z].*)?""")
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

    /** Rules for a source file; [appCode] false for the build-logic plugins (build-time code). */
    fun sourceViolations(text: String, appCode: Boolean = true): List<String> =
        matches(text, SOURCE_RULES) + if (appCode) matches(text, CODE_LOADING_RULES) else emptyList()

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
     * Network libraries, file dependencies and native builds in a build file. For the
     * libraries, a line ending with [DENYLIST_MARKER] is a list of forbidden groups
     * (`:app:checkReleaseDependencyGroups`) and is skipped.
     */
    fun buildViolations(text: String, mirrorScript: Boolean = false): List<String> =
        BUILD_RULE.findAll(text)
            .filterNot { match -> lineAt(text, match.range.first).trimEnd().endsWith(DENYLIST_MARKER) }
            .map { "${lineOf(text, it.range.first)}: network or media library '${it.value}'" }
            .toList() + matches(text, BUILD_CODE_RULES) + repositoryViolations(text, mirrorScript)

    /**
     * Repositories other than google(), mavenCentral() and gradlePluginPortal(). With
     * [mirrorScript] (only for [MIRROR_SCRIPT]), its own mirror block is accepted, and
     * every address in it must be [MIRROR_URL].
     */
    fun repositoryViolations(text: String, mirrorScript: Boolean = false): List<String> {
        val repositories = REPOSITORY.findAll(text)
            .filterNot { mirrorScript && it.value in MIRROR_SCRIPT_MATCHES }
            .map { "${lineOf(text, it.range.first)}: repository other than google(), mavenCentral(), gradlePluginPortal() ('${it.value}')" }
        val addresses = if (mirrorScript) {
            ADDRESS.findAll(text).filter { it.value != MIRROR_URL }
                .map { "${lineOf(text, it.range.first)}: the mirror script may only use $MIRROR_URL ('${it.value}')" }
        } else {
            emptySequence()
        }
        return (repositories + addresses).toList()
    }

    /** A module's build file ([relative] in the repository) must apply a convention plugin that refuses file dependencies. */
    fun moduleBuildFileViolations(relative: String, text: String): List<String> {
        val buildFile = relative.substringAfterLast('/').let { it == "build.gradle.kts" || it == "build.gradle" }
        val module = buildFile && relative.contains('/') && !relative.startsWith(BUILD_LOGIC_DIR)
        if (!module || BASE_CONVENTION_PLUGIN.containsMatchIn(text)) return emptyList()
        return listOf("1: module without cinex.android.application/.library/.feature or cinex.jvm.library, which refuse file dependencies")
    }

    /** A compiled library or code file at [relative] (the repository-relative path), other than [ALLOWED_BINARY]. */
    fun binaryViolations(relative: String): List<String> {
        val extension = relative.substringAfterLast('/').substringAfterLast('.', "").lowercase()
        if (extension !in BINARY_EXTENSIONS || relative == ALLOWED_BINARY) return emptyList()
        return listOf("1: compiled library or code (.$extension); vendored binaries bypass the dependency check")
    }

    /** True for a test source set, whose sources may use anything (they never reach the app). */
    fun isTestSet(set: String): Boolean = TEST_SET.matches(set)

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
                file.extension.lowercase() in BINARY_EXTENSIONS -> binaryViolations(relative)
                name == "AndroidManifest.xml" && sourceSet != null ->
                    manifestViolations(file.readText(), moduleOf(root, file) == NETWORK_MODULE)
                sourceSet != null && file.extension == "xml" && relative.contains("/src/$sourceSet/res/") -> buildList {
                    if (NETWORK_CONFIG_NAME.matches(name) && !relative.startsWith(ALLOWED_NETWORK_CONFIG_DIR)) {
                        add("1: network security config outside $ALLOWED_NETWORK_CONFIG_DIR")
                    }
                    addAll(resourceViolations(file.parentFile.name, file.readText()))
                }
                name.endsWith(".gradle.kts") || name.endsWith(".gradle") || file.extension == "toml" -> {
                    val text = file.readText()
                    buildViolations(text, mirrorScript = relative == MIRROR_SCRIPT) + moduleBuildFileViolations(relative, text)
                }
                sourceSet != null && !isTestSet(sourceSet) && file.extension in SOURCE_EXTENSIONS -> {
                    val text = file.readText()
                    val allowed = moduleOf(root, file) == NETWORK_MODULE && relative.contains(NETWORK_PACKAGE_DIR)
                    // The convention plugins are build files too, and run only at build time.
                    val buildLogic = relative.startsWith(BUILD_LOGIC_DIR)
                    (if (allowed) emptyList() else sourceViolations(text, appCode = !buildLogic)) +
                        (if (buildLogic) buildViolations(text) else emptyList())
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
