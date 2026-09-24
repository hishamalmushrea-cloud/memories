package com.memorymap.security

import com.memorymap.testing.RepoFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The privacy guarantees that live in build configuration rather than in code.
 *
 * A diary that promises it sends nothing to third parties has to be configured
 * that way too. Android's own cloud backup will upload the database and every
 * photo if it is allowed to, and no amount of care in the Kotlin stops it — so
 * the manifest is checked here like any other part of the implementation.
 */
class ClientSecurityTest {

    private val manifest = RepoFiles.read("app/src/main/AndroidManifest.xml")
    private val proguard = RepoFiles.read("app/proguard-rules.pro")
    private val buildScript = RepoFiles.read("app/build.gradle.kts")

    @Test
    fun `the diary is never handed to the operating system backup`() {
        // With allowBackup on, the room database and every attachment are copied
        // to a third party's servers by the OS, which the user is never asked
        // about and cannot turn off from inside the app.
        assertTrue(
            "android:allowBackup must be false",
            manifest.contains("android:allowBackup=\"false\""),
        )
    }

    @Test
    fun `plain http traffic is refused`() {
        // §39: HTTPS. The session token and the whole archive would otherwise be
        // readable by anything on the path.
        assertTrue(
            "android:usesCleartextTraffic must be false",
            manifest.contains("android:usesCleartextTraffic=\"false\""),
        )
    }

    @Test
    fun `no permission is declared that a feature does not use`() {
        // Location is on demand only, so there is deliberately no background
        // variant; adding one would be a tracking capability the app does not
        // have a use for.
        val declared = Regex("""uses-permission android:name="([^"]+)"""")
            .findAll(manifest).map { it.groupValues[1] }.toSet()

        assertEquals(
            setOf(
                "android.permission.INTERNET",
                "android.permission.ACCESS_NETWORK_STATE",
                "android.permission.ACCESS_FINE_LOCATION",
                "android.permission.ACCESS_COARSE_LOCATION",
                "android.permission.CAMERA",
                "android.permission.RECORD_AUDIO",
            ),
            declared,
        )
    }

    @Test
    fun `release builds actually run the shrinker`() {
        // Without R8 the log-stripping rules below never run at all, and debug
        // output would ship.
        assertTrue(buildScript.contains("isMinifyEnabled = true"))
    }

    @Test
    fun `debug and verbose logs are stripped from release builds`() {
        assertTrue(
            "MmLog must be listed for stripping",
            proguard.contains("-assumenosideeffects class com.memorymap.util.MmLog"),
        )
        val rule = proguard.substringAfter("-assumenosideeffects class com.memorymap.util.MmLog")
            .substringBefore("}")

        assertTrue("d() must be stripped", Regex("""\bd\(\.\.\.\)""").containsMatchIn(rule))
        assertTrue("v() must be stripped", Regex("""\bv\(\.\.\.\)""").containsMatchIn(rule))
    }

    @Test
    fun `the stripping rule is not written against a static that does not exist`() {
        // MmLog is a Kotlin object, so d() and v() are instance methods on the
        // singleton. A rule naming `static` matches nothing and strips nothing,
        // which is exactly how this guarantee was broken once already.
        val rule = proguard.substringAfter("-assumenosideeffects class com.memorymap.util.MmLog")
            .substringBefore("}")

        assertFalse("a static signature would never match", rule.contains("static"))
    }

    @Test
    fun `the service role key is nowhere in the client build`() {
        // Everything inside an APK is public, so a service key in the build
        // script or the manifest would be a published credential.
        //
        // Both files state the rule in a comment, which is the opposite of
        // breaking it, so comments are dropped first: what matters is that no
        // statement reaches for the key.
        val buildStatements = buildScript.lines()
            .filterNot { line ->
                val trimmed = line.trimStart()
                trimmed.startsWith("//") || trimmed.startsWith("/*") || trimmed.startsWith("*")
            }
            .joinToString("\n")

        assertFalse(buildStatements.contains("service_role"))

        val manifestStatements = manifest.replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
        assertFalse(manifestStatements.contains("service_role"))
    }
}
