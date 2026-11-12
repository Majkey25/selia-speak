package cz.localvoice.app

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.k2fsa.sherpa.onnx.WaveReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
class RecognitionModelInstrumentedTest {
    @Test
    fun installedZipformerProducesLiveAndFinalText() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val model = requireNotNull(ModelCatalog.byId("zipformer-en-streaming"))
        assertTrue("Install the catalog Zipformer model before this explicit live suite", ModelRepository(context).verify(model))
        val fixture = File(context.filesDir, "asr-fixture.wav")
        assertTrue("Copy the pinned public test WAV to files/asr-fixture.wav first; see QA.md", fixture.isFile)
        val digest = MessageDigest.getInstance("SHA-256").digest(fixture.readBytes())
            .joinToString("") { "%02x".format(it) }
        assertEquals("6bc58a4efdf20daac252b6b1502632601a71efe0308f6757dc1eda34891a7e4f", digest)
        withContext(Dispatchers.Default) {
            val wave = WaveReader.readWave(fixture.path)
            assertEquals(AudioCapture.SAMPLE_RATE, wave.sampleRate)
            val started = SystemClock.elapsedRealtime()
            LocalRecognizer(context, model, "en").use { engine ->
                val loaded = SystemClock.elapsedRealtime()
                var firstPartialMs: Long? = null
                engine.openStream().use { stream ->
                    wave.samples.asList().chunked(1_600).forEach { chunk ->
                        val partial = stream.accept(chunk.toFloatArray())
                        if (partial.text.isNotBlank() && firstPartialMs == null) {
                            firstPartialMs = SystemClock.elapsedRealtime() - loaded
                        }
                    }
                    val result = stream.finish()
                    assertTrue("Native streaming returned no provisional text", firstPartialMs != null)
                    assertTrue("Native recognizer returned no useful final text", result.text.length > 20)
                    Log.i("SeliaSpeakQA", "public_fixture_ms load=${loaded - started} first_partial=$firstPartialMs total=${SystemClock.elapsedRealtime() - started} audio=${wave.samples.size * 1_000L / wave.sampleRate}; text=${result.text}")
                }
            }
        }
    }
}
