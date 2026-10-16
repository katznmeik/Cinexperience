package io.github.katznmeik.cinexperience

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser
import java.io.File

/**
 * Spec §11.1, Network & security rule 5: HTTPS only (cleartext blocked) and only
 * the phone's system certificate authorities, no user-installed ones and no
 * pinning, in debug and release builds.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NetworkSecurityConfigTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** Every element as (name, attributes). */
    private fun elements(): List<Pair<String, Map<String, String>>> {
        val parser = context.resources.getXml(R.xml.network_security_config)
        val elements = mutableListOf<Pair<String, Map<String, String>>>()
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType != XmlPullParser.START_TAG) continue
            val attributes = (0 until parser.attributeCount).associate { parser.getAttributeName(it) to parser.getAttributeValue(it) }
            elements += parser.name to attributes
        }
        return elements
    }

    @Test
    fun httpsOnlyWithTheSystemCertificateAuthorities() {
        val elements = elements()
        assertEquals(listOf("network-security-config", "base-config", "trust-anchors", "certificates"), elements.map { it.first })
        assertEquals(mapOf("cleartextTrafficPermitted" to "false"), elements[1].second)
        assertEquals(mapOf("src" to "system"), elements[3].second)
        // No exceptions: no domain-config, debug-overrides (user CAs in debug builds) or pin-set.
        assertTrue(elements.none { it.first in setOf("domain-config", "debug-overrides", "pin-set") })
    }

    @Test
    fun theManifestUsesIt() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android:networkSecurityConfig=\"@xml/network_security_config\""))
        assertFalse(manifest.contains("usesCleartextTraffic"))
        // The INTERNET permission comes from core:program only (NetworkConfinementTest).
        assertFalse(manifest.contains("android.permission.INTERNET"))
    }

    @Test
    fun theDebugAndReleaseManifestsUseIt() {
        // As merged with every library's manifest; CI runs only the debug unit tests.
        // (NetworkConfinementTest allows this config file only in app/src/main/res/xml/.)
        for (buildType in MergedManifest.BUILD_TYPES) {
            val manifest = MergedManifest.of(buildType)
            assertEquals(buildType, "@xml/network_security_config", manifest.applicationAttribute("networkSecurityConfig"))
            val cleartext = manifest.applicationAttribute("usesCleartextTraffic")
            assertTrue("$buildType: usesCleartextTraffic=$cleartext", cleartext == null || cleartext == "false")
        }
    }
}
