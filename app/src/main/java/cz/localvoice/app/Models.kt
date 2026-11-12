package cz.localvoice.app

import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.StringReader

enum class EditAction(val wireName: String) {
    INSERT("insert"),
    REPLACE_SELECTION("replace_selection"),
    COPY_ONLY("copy_only"),
}

data class EditPlan(
    val action: EditAction,
    val text: String,
    val reason: String,
) {
    companion object {
        fun parse(response: String, hasSelection: Boolean): EditPlan {
            val value = parseSemanticFields(response)
            require(value.keys == setOf("action", "text", "reason")) { "Invalid semantic response fields" }
            val action = EditAction.entries.firstOrNull {
                it.wireName == value["action"]
            } ?: error("Unknown edit action")
            val text = value.getValue("text").trim()
            val reason = value.getValue("reason")
            require(reason.length <= 160) { "Invalid semantic field reason" }
            require(text.isNotEmpty() && text.length <= 50_000) { "Invalid edit text" }
            require(action != EditAction.REPLACE_SELECTION || hasSelection) {
                "replace_selection requires selected text"
            }
            require(action != EditAction.INSERT || !hasSelection) {
                "insert cannot overwrite selected text"
            }
            return EditPlan(action, text, reason)
        }

        fun rawFallback(text: String, hasSelection: Boolean, reason: String): EditPlan {
            val normalized = text.trim()
            require(normalized.isNotEmpty() && normalized.length <= 50_000) { "Invalid fallback text" }
            return EditPlan(
                action = if (hasSelection) EditAction.COPY_ONLY else EditAction.INSERT,
                text = normalized,
                reason = reason.take(160),
            )
        }
    }
}

internal fun parseSemanticFields(response: String): Map<String, String> {
    require(response.length in 1..50_000) { "Local editing returned an invalid response length" }
    try {
        return JsonReader(StringReader(response)).use { reader ->
            reader.strictness = Strictness.STRICT
            reader.beginObject()
            val fields = mutableMapOf<String, String>()
            while (reader.hasNext()) {
                require(fields.size < 3)
                val name = reader.nextName()
                require(name !in fields && reader.peek() == JsonToken.STRING)
                fields[name] = reader.nextString()
            }
            reader.endObject()
            require(reader.peek() == JsonToken.END_DOCUMENT)
            fields
        }
    } catch (_: Exception) {
        // Parser exceptions can contain the private model response.
        throw IllegalArgumentException("Local editing returned invalid JSON fields")
    }
}
