package cz.localvoice.app

/** Owns only the text inserted by the current dictation. */
class FieldDraft(val original: FieldSnapshot) {
    var expected: FieldSnapshot = original
        private set

    fun replacement(text: String, current: FieldFacts): TextEditResult? {
        if (!FieldTargetPolicy.isFresh(expected, current) || original.selectedText.isNotEmpty()) return null
        return TextEditor.apply(
            original.facts.text,
            original.facts.selectionStart,
            original.facts.selectionEnd,
            EditPlan(EditAction.INSERT, text, "dictation"),
        )
    }

    fun acknowledge(result: TextEditResult) {
        expected = FieldSnapshot.from(original.facts.copy(
            text = result.text,
            selectionStart = result.cursor,
            selectionEnd = result.cursor,
        ))
    }

    fun canRestore(current: FieldFacts): Boolean = FieldTargetPolicy.isFresh(expected, current)
}
