package cz.localvoice.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ModelCatalogTest {
    @Test
    fun catalogPreservesRareLanguagesAndWritingVariantsWithoutInventedMeasurements() {
        val omni = requireNotNull(ModelCatalog.byId("omnilingual-300m-int8"))
        assertEquals(1_650, omni.languages.size)
        assertEquals(1_672, omni.languageVariants.size)
        assertTrue("cs" in omni.languages && "aae" in omni.languages && "yue" in omni.languages)
        assertTrue("el-Grek-x-cypr1249" in omni.languageVariants)
        assertTrue("lld-Latn-x-gherd" in omni.languageVariants)
        assertFalse(omni.manualLanguage)
        assertEquals(99, ModelCatalog.whisperLanguages.size)
        assertTrue(ModelCatalog.models.all { it.minRamBytes == null && it.benchmarks.isEmpty() })
        assertTrue(ModelCatalog.models.single { it.streaming }.family == ModelFamily.ZIPFORMER)
    }

    @Test
    fun integrityMetadataRejectsTraversalMutableRevisionsAndInvalidSuffixes() {
        val model = ModelCatalog.asrModels.first()
        assertFailsWith<IllegalArgumentException> { model.copy(id = "../outside") }
        assertFailsWith<IllegalArgumentException> {
            model.copy(files = listOf(model.files.first().copy(name = "../outside")))
        }
        assertFailsWith<IllegalArgumentException> {
            model.copy(files = listOf(model.files.first().copy(url = model.files.first().url.replace(Regex("/resolve/[a-f0-9]{40}/"), "/resolve/main/"))))
        }
        assertFailsWith<IllegalArgumentException> {
            model.copy(files = listOf(model.files.first().copy(sha256 = model.files.first().sha256 + "junk")))
        }
        assertFailsWith<IllegalArgumentException> {
            model.copy(files = listOf(model.files.first().copy(url = model.files.first().url + "?revision=main")))
        }
    }

    @Test
    fun routingUsesOnlyExplicitInstalledAssignmentsAndNeverAnUnmappedFallback() {
        val config = SpeakConfig(
            activeModelId = "whisper-tiny-int8", routingEnabled = true, detectorModelId = "whisper-tiny-lid",
            routes = mapOf("cs" to "omnilingual-300m-int8", "en" to "zipformer-en-streaming"),
        )
        val installed = setOf("whisper-tiny-int8", "omnilingual-300m-int8", "zipformer-en-streaming")
        assertEquals("omnilingual-300m-int8", SpeakSettings.selectModel(config, "cs", installed).id)
        assertEquals("zipformer-en-streaming", SpeakSettings.selectModel(config, "en", installed).id)
        assertFailsWith<IllegalStateException> { SpeakSettings.selectModel(config, "de", installed) }
        assertFailsWith<IllegalStateException> { SpeakSettings.selectModel(config, "cs", installed - "omnilingual-300m-int8") }
        assertFailsWith<IllegalArgumentException> {
            SpeakSettings.selectModel(config.copy(routes = mapOf("cs" to "zipformer-en-streaming")), "cs", installed)
        }
        assertFailsWith<IllegalArgumentException> {
            SpeakSettings.selectModel(SpeakConfig(activeModelId = "omnilingual-300m-int8", language = "cs"), null, installed)
        }
    }
}
