package cz.localvoice.app

import android.content.Context
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.StringReader
import org.json.JSONObject

data class SpeakConfig(
    val activeModelId: String? = null,
    val language: String? = null,
    val enabled: Boolean = false,
    val livePreview: Boolean = true,
    val semanticEnabled: Boolean = false,
    val routingEnabled: Boolean = false,
    val detectorModelId: String? = null,
    val routes: Map<String, String> = emptyMap(),
) {
    fun references(modelId: String): Boolean = activeModelId == modelId || detectorModelId == modelId ||
        modelId in routes.values || semanticEnabled && ModelCatalog.semanticModels.any { it.id == modelId }
}

object SpeakSettings {
    internal const val PREFERENCES = "selia_speak"
    private const val KEY = "configuration_v1"
    private const val MAX_CONFIGURATION_CHARS = 524_288

    fun load(context: Context): SpeakConfig {
        val raw = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getString(KEY, null)
            ?: return SpeakConfig()
        return try {
            decode(raw)
        } catch (_: Exception) {
            throw IllegalArgumentException("Invalid saved dictation settings")
        }
    }

    private fun decode(raw: String): SpeakConfig {
        require(raw.length <= MAX_CONFIGURATION_CHARS) { "Saved dictation settings are too large" }
        return JsonReader(StringReader(raw)).use { reader ->
            reader.strictness = Strictness.STRICT
            reader.beginObject()
            val seen = mutableSetOf<String>()
            var config = SpeakConfig()
            while (reader.hasNext()) {
                val field = reader.nextName()
                require(seen.add(field)) { "Duplicate saved setting" }
                config = when (field) {
                    "version" -> { require(reader.peek() == JsonToken.NUMBER && reader.nextInt() == 1); config }
                    "active_model" -> config.copy(activeModelId = reader.nullableSetting())
                    "language" -> config.copy(language = reader.nullableSetting())
                    "enabled" -> config.copy(enabled = reader.booleanSetting())
                    "live_preview" -> config.copy(livePreview = reader.booleanSetting())
                    "semantic_editing" -> config.copy(semanticEnabled = reader.booleanSetting())
                    "routing_enabled" -> config.copy(routingEnabled = reader.booleanSetting())
                    "detector_model" -> config.copy(detectorModelId = reader.nullableSetting())
                    "routes" -> {
                        reader.beginObject()
                        val routes = mutableMapOf<String, String>()
                        while (reader.hasNext()) {
                            require(routes.size < ModelCatalog.languages.size)
                            val language = reader.nextName()
                            require(language in ModelCatalog.languages && language !in routes)
                            routes[language] = requireNotNull(reader.nullableSetting())
                        }
                        reader.endObject()
                        config.copy(routes = routes)
                    }
                    else -> error("Unknown saved setting")
                }
            }
            reader.endObject()
            require(seen == setOf("version", "active_model", "language", "enabled", "live_preview",
                "semantic_editing", "routing_enabled", "detector_model", "routes"))
            require(reader.peek() == JsonToken.END_DOCUMENT)
            config.also(::validateMetadata)
        }
    }

    fun save(context: Context, config: SpeakConfig) = synchronized(ModelRepository.mutationLock) {
        validate(context, config)
        val json = JSONObject()
            .put("version", 1)
            .put("active_model", config.activeModelId ?: JSONObject.NULL)
            .put("language", config.language ?: JSONObject.NULL)
            .put("enabled", config.enabled)
            .put("live_preview", config.livePreview)
            .put("semantic_editing", config.semanticEnabled)
            .put("routing_enabled", config.routingEnabled)
            .put("detector_model", config.detectorModelId ?: JSONObject.NULL)
            .put("routes", JSONObject(config.routes))
        check(context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().putString(KEY, json.toString()).commit()) {
            "Dictation settings could not be saved. Try again."
        }
    }

    fun validate(context: Context, config: SpeakConfig) {
        validateMetadata(config)
        val repository = ModelRepository(context)
        val active = ModelCatalog.byId(config.activeModelId)
        if (active != null) check(repository.isInstalled(active)) { "Install the selected model first" }
        if (config.routingEnabled) {
            val detector = requireNotNull(ModelCatalog.byId(config.detectorModelId)) { "Select a language detector" }
            check(repository.isInstalled(detector)) { "Install the language detector first" }
            check(config.routes.isNotEmpty()) { "Add at least one installed language route" }
            require(config.routes.keys.all { it in detector.languages }) {
                "The selected detector does not identify every configured route language"
            }
        }
        config.routes.values.toSet().forEach { id ->
            check(repository.isInstalled(requireNotNull(ModelCatalog.byId(id)))) { "Install every configured language model first" }
        }
        if (config.semanticEnabled) check(repository.isInstalled(ModelCatalog.semanticModels.single())) {
            "Install the local editing model first"
        }
    }

    fun selectModel(config: SpeakConfig, detectedLanguage: String?, installedIds: Set<String>): ModelSpec {
        validateMetadata(config)
        val active = requireNotNull(ModelCatalog.byId(config.activeModelId)) { "Select a dictation model" }
        val language = if (config.routingEnabled) {
            requireNotNull(detectedLanguage) { "No language was detected" }.also {
                require(it in ModelCatalog.languages) { "The detected language is unsupported" }
            }
        } else config.language
        val selected = if (config.routingEnabled) {
            config.routes[language]?.let { requireNotNull(ModelCatalog.byId(it)) }
                ?: error("No installed model is configured for the detected language")
        } else active
        check(selected.id in installedIds) { "The selected language model is not installed" }
        require(language == null && selected.autoLanguage || language in selected.languages) {
            "This model does not support the selected language"
        }
        return selected
    }

    private fun validateMetadata(config: SpeakConfig) {
        val active = config.activeModelId?.let { id ->
            requireNotNull(ModelCatalog.byId(id)) { "Unknown dictation model" }.also {
                require(it.role == ModelRole.ASR) { "Choose a speech recognition model" }
            }
        }
        require(!config.enabled || active != null) { "Select a model before starting dictation" }
        require(config.language == null || config.language in ModelCatalog.languages) { "Unsupported dictation language" }
        if (active != null && !config.routingEnabled) {
            require(config.language == null || active.manualLanguage) { "This model chooses its language automatically" }
            require(config.language != null || active.autoLanguage) { "Choose a language for this model" }
            require(config.language == null || config.language in active.languages) { "This model does not support the selected language" }
        }
        config.detectorModelId?.let { id ->
            val detector = requireNotNull(ModelCatalog.byId(id)) { "Unknown language detector" }
            require(detector.languageIdentification) { "This model cannot identify languages" }
        }
        require(config.routes.size <= ModelCatalog.languages.size) { "Too many language routes" }
        config.routes.forEach { (language, modelId) ->
            val model = requireNotNull(ModelCatalog.byId(modelId)) { "Unknown language route model" }
            require(language in ModelCatalog.languages && model.role == ModelRole.ASR && language in model.languages) {
                "The route model does not support this language"
            }
        }
        require(!config.routingEnabled || config.detectorModelId != null && active != null) {
            "Select a default model and language detector before enabling routing"
        }
    }

    private fun JsonReader.nullableSetting(): String? {
        if (peek() == JsonToken.NULL) { nextNull(); return null }
        require(peek() == JsonToken.STRING)
        return nextString().also { require(it.length <= 80) }
    }

    private fun JsonReader.booleanSetting(): Boolean {
        require(peek() == JsonToken.BOOLEAN)
        return nextBoolean()
    }
}
