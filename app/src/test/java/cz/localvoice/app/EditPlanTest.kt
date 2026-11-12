package cz.localvoice.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class EditPlanTest {
    @Test
    fun rejectsThinkingTextOutsideJson() {
        val response = """
            <think>short private reasoning</think>
            {"action":"insert","text":"V šest.","reason":"latest correction"}
        """.trimIndent()

        assertFailsWith<IllegalArgumentException> { EditPlan.parse(response, hasSelection = false) }
    }

    @Test
    fun parsesOneCompleteJsonObjectWithEscapedText() {
        assertEquals(
            "Say \"OK\".",
            EditPlan.parse(""" {"action":"insert","text":"Say \"OK\".","reason":"literal"} """, false).text,
        )
    }

    @Test
    fun rejectsTrailingPayloadAndInvalidFieldTypesWithoutLeakingPrivateText() {
        for (response in listOf(
            """{"action":"insert","text":"private-sentinel","reason":"literal"} extra""",
            """{"action":"insert","text":"private-sentinel","text":"second","reason":"literal"}""",
            """{"action":"insert","text":null,"reason":"private-sentinel"}""",
            """{'action':'insert','text':'private-sentinel','reason':'literal'}""",
        )) {
            val failure = assertFailsWith<IllegalArgumentException> { EditPlan.parse(response, false) }
            kotlin.test.assertFalse("private-sentinel" in failure.toString())
            assertEquals(null, failure.cause)
        }
    }

    @Test
    fun rejectsReplacementWithoutSelection() {
        assertFailsWith<IllegalArgumentException> {
            EditPlan.parse(
                """{"action":"replace_selection","text":"Nový","reason":"rewrite"}""",
                hasSelection = false,
            )
        }
    }

    @Test
    fun rawFallbackNeverOverwritesSelection() {
        assertEquals(
            EditAction.COPY_ONLY,
            EditPlan.rawFallback("Původní", hasSelection = true, reason = "Model není dostupný").action,
        )
        assertEquals(
            EditAction.INSERT,
            EditPlan.rawFallback("Původní", hasSelection = false, reason = "Model není dostupný").action,
        )
    }

    @Test
    fun rawFallbackKeepsExplicitBoundedReason() {
        val reason = "x".repeat(200)

        val plan = EditPlan.rawFallback(" Ahoj ", hasSelection = false, reason = reason)

        assertEquals("Ahoj", plan.text)
        assertEquals(160, plan.reason.length)
    }

    @Test
    fun rawFallbackRejectsBlankText() {
        assertFailsWith<IllegalArgumentException> {
            EditPlan.rawFallback("  ", hasSelection = false, reason = "Model není dostupný")
        }
    }
}
