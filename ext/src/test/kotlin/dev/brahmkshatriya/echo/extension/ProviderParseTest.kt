package dev.brahmkshatriya.echo.extension

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KuGouParseTest {

    @Test
    fun parsesKrcLinesAndWords() {
        val krc = """
            [id:00000000]
            [0,1000]<0,500,0>Hello<500,500,0> world
            [1000,2000]<0,2000,0>Second line
        """.trimIndent()
        val lines = parseKrc(krc)
        assertEquals(2, lines.size)
        assertEquals(0L, lines[0].startMs)
        assertEquals("Hello world", lines[0].text)
        assertEquals(2, lines[0].words.size)
        assertEquals(500L, lines[0].words[1].startMs)
        assertEquals("Second line", lines[1].text)
    }

    @Test
    fun metadataOnlyPayloadIsEmpty() {
        assertTrue(parseKrc("[language:abc]\n[ti:title]").isEmpty())
    }

    @Test
    fun codecRoundTrip() {
        val text = "krc-payload"
        assertEquals(text, String(Codec.base64(Codec.encode(text.toByteArray())), Charsets.UTF_8))
    }
}
