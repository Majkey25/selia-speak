package cz.localvoice.app

import android.text.InputType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FieldDraftTest {
    private val field = FieldFacts("notes", 1, "editor", 0, 0, 100, 100, "Before | after", 7, 7,
        true, true, true, false, InputType.TYPE_CLASS_TEXT)

    @Test fun partialUpdatesReplaceOnlyThisDictationAndFollowTheirOwnWrites() {
        val draft = FieldDraft(FieldSnapshot.from(field))
        val first = assertNotNull(draft.replacement("Meeting at five", field))
        draft.acknowledge(first)
        val second = assertNotNull(draft.replacement("Meeting at six", draft.expected.facts))
        draft.acknowledge(second)
        val reset = assertNotNull(draft.replacement("OK", draft.expected.facts))
        assertEquals("Before OK| after", reset.text)
        assertEquals(9, reset.cursor)
    }

    @Test fun userEditsOrMovingTheCursorBlockReplacementAndRollback() {
        val draft = FieldDraft(FieldSnapshot.from(field))
        draft.acknowledge(assertNotNull(draft.replacement("Hello", field)))
        val edited = draft.expected.facts.copy(text = "user changed this")
        assertNull(draft.replacement("OK", edited))
        assertFalse(draft.canRestore(edited))
        assertNull(draft.replacement("OK", draft.expected.facts.copy(selectionStart = 0, selectionEnd = 0)))
        assertTrue(draft.canRestore(draft.expected.facts))
    }

    @Test fun existingSelectionIsNeverOverwrittenByInterimText() {
        val selected = field.copy(selectionEnd = 8)
        assertNull(FieldDraft(FieldSnapshot.from(selected)).replacement("OK", selected))
    }
}
