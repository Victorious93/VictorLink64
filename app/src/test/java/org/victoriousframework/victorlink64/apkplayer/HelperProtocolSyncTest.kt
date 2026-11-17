package org.victoriousframework.victorlink64.apkplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Victorious Framework: the Kotlin and Java copies of the helper protocol must stay identical. */
class HelperProtocolSyncTest {
    private fun javaSource(): String {
        val rel = "box32/src/main/java/org/victoriousframework/victorlink64/box32/HelperProtocol.java"
        val file = listOf(File(rel), File("..", rel)).first { it.isFile }
        return file.readText()
    }

    @Test fun constantsMatchTheHelperModule() {
        val regex = Regex("""public static final (String|int) ([A-Z_0-9]+) = ("[^"]*"|\d+);""")
        val javaConstants = regex.findAll(javaSource()).associate { m ->
            val raw = m.groupValues[3]
            m.groupValues[2] to if (m.groupValues[1] == "int") raw.toInt() else raw.trim('"')
        }
        assertTrue("found ${javaConstants.size} constants in the Java file", javaConstants.size >= 15)
        for ((name, javaValue) in javaConstants) {
            val field = HelperProtocol::class.java.getDeclaredField(name)
            field.isAccessible = true
            assertEquals("HelperProtocol.$name", javaValue, field.get(null))
        }
    }

    @Test fun theHelperPackageNamesFollowTheMainPackage() {
        val manifest = File("../box32/src/main/AndroidManifest.xml").takeIf { it.isFile } ?: File("box32/src/main/AndroidManifest.xml")
        val text = manifest.readText()
        assertTrue(text.contains("android:name=\"${HelperProtocol.META_PROTOCOL}\""))
        assertTrue(text.contains("android:value=\"${HelperProtocol.VERSION}\""))
        assertTrue(text.contains(HelperProtocol.ACTION_PING))
        assertTrue(text.contains(HelperProtocol.ACTION_RUN_APK))
    }
}
