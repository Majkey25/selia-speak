package cz.localvoice.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class SemanticModelInstrumentedTest {
    @Test
    fun installedLocalModelAppliesCorrectionsAndRespectsLiteralCommands() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val model = requireNotNull(ModelCatalog.byId("qwen3-06b-editing"))
        assertTrue("Install the catalog Qwen editing model before running this explicit live suite", ModelRepository(context).verify(model))
        withContext(Dispatchers.Default) {
            SemanticEngine(context, model).use { engine ->
                val english = engine.editDraft("The meeting is at five.", "Actually, at six.", "en").lowercase(Locale.ENGLISH)
                assertTrue("English correction did not retain six", "six" in english || "6" in english)
                assertFalse("English correction retained five", "five" in english || "5" in english)

                val czech = engine.editDraft("Schůzka je v pět.", "Vlastně v šest.", "cs").lowercase(Locale.forLanguageTag("cs"))
                assertTrue("Czech correction did not retain six", "šest" in czech || "6" in czech)
                assertFalse("Czech correction retained five", "pět" in czech || "5" in czech)

                val longDraft = "This earlier text must disappear. ".repeat(200)
                val replacement = engine.editDraft(longDraft, "Discard all of that and just write OK.", "en")
                assertEquals("OK", replacement.trim().trimEnd('.'))

                val literal = engine.editDraft("", "Write the literal words \"discard everything\".", "en").lowercase(Locale.ENGLISH)
                assertEquals("discard everything", literal.trim().trimEnd('.'))
            }
        }
    }
}
