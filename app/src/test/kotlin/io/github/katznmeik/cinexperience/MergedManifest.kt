package io.github.katznmeik.cinexperience

import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The merged manifest (the app's and every library's) of a build type, as it is
 * packaged into that APK. The build passes the files of both build types to every
 * unit test task (app/build.gradle.kts), so the debug unit tests that CI runs check
 * the release manifest too.
 */
internal class MergedManifest private constructor(private val manifest: Element) {

    /** The application ID. */
    val packageName: String get() = manifest.getAttribute("package")

    /** Every `uses-permission` (and `uses-permission-sdk-23`). */
    val requestedPermissions: Set<String>
        get() = PERMISSION_TAGS.flatMap { tag -> manifest.elements(tag) }.mapNotNull { it.android("name") }.toSet()

    /** An `android:` attribute of `<application>`; `null` if it isn't set. */
    fun applicationAttribute(name: String): String? = manifest.elements("application").single().android(name)

    private fun Element.elements(tag: String): List<Element> =
        getElementsByTagName(tag).let { nodes -> (0 until nodes.length).map { nodes.item(it) as Element } }

    private fun Element.android(name: String): String? = getAttributeNodeNS(ANDROID_NAMESPACE, name)?.value

    companion object {
        val BUILD_TYPES: List<String> = listOf("debug", "release")

        private const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
        private val PERMISSION_TAGS = listOf("uses-permission", "uses-permission-sdk-23")

        fun of(buildType: String): MergedManifest {
            val property = "cinex.mergedManifest.$buildType"
            val path = checkNotNull(System.getProperty(property)) { "$property is not set: app/build.gradle.kts passes it to the unit tests" }
            val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            return MergedManifest(factory.newDocumentBuilder().parse(File(path)).documentElement)
        }
    }
}
