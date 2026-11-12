package cz.localvoice.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.json.JSONObject

class SemanticDraftTest {
    @Test
    fun globalReplacementCanDiscardALongSessionWithoutSendingItsPrefix() {
        val prefix = "Earlier private sentence. ".repeat(500)
        val request = SemanticDraft.request(prefix + "Meeting at five.", "Forget all that and just write OK.", "en")

        assertTrue(request.tailStart > 0)
        assertEquals("OK", SemanticDraft.apply(request, """{"op":"replace_draft","text":"OK"}"""))
        val sent = JSONObject(request.json)
        assertEquals(request.tailStart, sent.getInt("earlier_characters"))
        assertEquals((prefix + "Meeting at five.").substring(request.tailStart), sent.getString("draft_tail"))
        assertTrue(sent.getString("draft_tail").toByteArray(Charsets.UTF_8).size <= 1_000)
    }

    @Test
    fun recentCorrectionPreservesTheUnseenSessionPrefix() {
        val original = "Original sentence. ".repeat(300) + "Meeting at five."
        val request = SemanticDraft.request(original, "Actually at six.", "en")
        val tail = original.substring(request.tailStart).replace("five", "six")

        assertEquals(original.take(request.tailStart) + tail, SemanticDraft.apply(
            request, JSONObject().put("op", "replace_tail").put("text", tail).toString(),
        ))
    }

    @Test
    fun unsupportedEditAndMalformedResponseDoNotChangeTheDraftOrLeakIt() {
        val previous = "private-sentinel"
        val request = SemanticDraft.request(previous, "Rewrite the unavailable first paragraph.", "en")
        assertFailsWith<IllegalStateException> {
            SemanticDraft.apply(request, """{"op":"unsupported","text":""}""")
        }
        for (response in listOf(
            "prefix {\"op\":\"append\",\"text\":\"private-sentinel\"}",
            """{"op":"append","text":"private-sentinel"} suffix""",
            """{"op":"append","text":42}""",
        )) {
            val error = assertFailsWith<IllegalArgumentException> { SemanticDraft.apply(request, response) }
            assertFalse("private-sentinel" in error.toString())
            assertEquals(null, error.cause)
            assertEquals(previous, request.previousDraft)
        }
    }

    @Test
    fun unicodeChunksPreserveEveryCharacterAndCompleteCodePoints() {
        val transcript = "Žluťoučký kůň 日本語 😀 ".repeat(300)
        val chunks = SemanticDraft.chunks(transcript)

        assertTrue(chunks.size > 1)
        assertEquals(transcript, chunks.joinToString(""))
        chunks.forEach {
            assertTrue(it.toByteArray(Charsets.UTF_8).size <= 1_000)
            assertFalse(it.first().isLowSurrogate())
            assertFalse(it.last().isHighSurrogate())
        }
        val request = SemanticDraft.request(transcript, "Přidej 😀.", "cs")
        assertFalse(transcript[request.tailStart].isLowSurrogate())
    }

    @Test
    fun quotedSpokenCommandsStayLiteralDataInTheModelRequest() {
        val spoken = "Write the literal words \"discard everything\"."
        val request = SemanticDraft.request("", spoken, "en")

        assertEquals(spoken, JSONObject(request.json).getString("spoken_input"))
        assertEquals("discard everything", SemanticDraft.apply(
            request, """{"op":"append","text":"discard everything"}""",
        ))
        assertEquals("auto", JSONObject(SemanticDraft.request("", "Bonjour.", "").json).getString("language"))
    }

    @Test
    fun audioBoundaryRejectsInvalidValuesAndExcessLength() {
        validateAudio(floatArrayOf(-1f, 0f, 1f), 3)
        for (samples in listOf(floatArrayOf(), floatArrayOf(Float.NaN), floatArrayOf(Float.POSITIVE_INFINITY),
            floatArrayOf(1.1f), floatArrayOf(0f, 0f, 0f, 0f))) {
            assertFailsWith<IllegalArgumentException> { validateAudio(samples, 3) }
        }
    }
}
