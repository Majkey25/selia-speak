package cz.localvoice.app

import android.content.Context
import android.os.Looper
import androidx.annotation.WorkerThread
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineOmnilingualAsrCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.k2fsa.sherpa.onnx.SpokenLanguageIdentification
import com.k2fsa.sherpa.onnx.SpokenLanguageIdentificationConfig
import com.k2fsa.sherpa.onnx.SpokenLanguageIdentificationWhisperConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.concurrent.Semaphore

data class RecognitionResult(val text: String, val language: String?)

// Native recognizers keep substantial model state. Detection must finish before ASR starts.
private object RecognitionSlot {
    private val slot = Semaphore(1)

    fun acquire() = check(slot.tryAcquire()) { "Close the previous speech engine before starting another" }
    fun release() = slot.release()
}

internal fun requireWorkerThread() {
    check(Looper.myLooper() != Looper.getMainLooper()) { "Speech processing must run off the main thread" }
}

internal fun validateAudio(samples: FloatArray, maxSamples: Int) {
    require(samples.size in 1..maxSamples) { "Audio length is outside the supported limit" }
    require(samples.all { it.isFinite() && it in -1f..1f }) { "Audio contains invalid samples" }
}

@WorkerThread
class LocalRecognizer(context: Context, val spec: ModelSpec, private val language: String?) : AutoCloseable {
    private val lock = Any()
    private val repository = ModelRepository(context)
    private var lease: AutoCloseable? = null
    private var slotHeld = false
    private var offline: OfflineRecognizer? = null
    private var online: OnlineRecognizer? = null
    private var activeStream: StreamingRecognition? = null
    private var closed = false

    init {
        requireWorkerThread()
        require(spec.role == ModelRole.ASR) { "Selected model is not a speech recognizer" }
        require(language == null && spec.autoLanguage || language in spec.languages) {
            "Selected model does not support this language mode"
        }
        require(language == null || spec.manualLanguage) { "This model does not accept a manual language hint" }
        try {
            RecognitionSlot.acquire()
            slotHeld = true
            lease = repository.acquire(spec)
            when (spec.family) {
                ModelFamily.WHISPER -> offline = OfflineRecognizer(
                    config = OfflineRecognizerConfig(
                        featConfig = FeatureConfig(sampleRate = AudioCapture.SAMPLE_RATE, featureDim = 80),
                        modelConfig = OfflineModelConfig(
                            whisper = OfflineWhisperModelConfig(
                                encoder = path("encoder.onnx"), decoder = path("decoder.onnx"),
                                language = language.orEmpty(), task = "transcribe",
                            ),
                            tokens = path("tokens.txt"), modelType = "whisper",
                        ),
                    ),
                )
                ModelFamily.ZIPFORMER -> online = OnlineRecognizer(
                    config = OnlineRecognizerConfig(
                        featConfig = FeatureConfig(sampleRate = AudioCapture.SAMPLE_RATE, featureDim = 80),
                        modelConfig = OnlineModelConfig(
                            transducer = OnlineTransducerModelConfig(
                                encoder = path("encoder.onnx"), decoder = path("decoder.onnx"),
                                joiner = path("joiner.onnx"),
                            ),
                            tokens = path("tokens.txt"), modelType = "zipformer2",
                        ),
                        enableEndpoint = false,
                    ),
                )
                ModelFamily.OMNILINGUAL -> {
                    offline = OfflineRecognizer(
                        config = OfflineRecognizerConfig(
                            featConfig = FeatureConfig(sampleRate = AudioCapture.SAMPLE_RATE, featureDim = 80),
                            modelConfig = OfflineModelConfig(
                                omnilingual = OfflineOmnilingualAsrCtcModelConfig(model = path("model.onnx")),
                                tokens = path("tokens.txt"),
                            ),
                        ),
                    )
                }
                ModelFamily.QWEN -> error("Selected model is not a speech recognizer")
            }
        } catch (error: Throwable) {
            runCatching(::close).onFailure(error::addSuppressed)
            throw error
        }
    }

    suspend fun transcribe(samples: FloatArray): RecognitionResult = withContext(Dispatchers.Default) {
        synchronized(lock) {
            coroutineContext.ensureActive()
            ensureOpen()
            validateAudio(samples, MAX_SAMPLES)
            check(activeStream == null) { "A streaming dictation is already active" }
            val recognizer = offline
            if (recognizer == null) {
                openStream().use { stream ->
                    AudioChunks.ranges(samples.size, AudioCapture.SAMPLE_RATE).forEach { range ->
                        coroutineContext.ensureActive()
                        stream.accept(samples.copyOfRange(range.first, range.last + 1))
                        coroutineContext.ensureActive()
                    }
                    return@synchronized stream.finish().also { coroutineContext.ensureActive() }
                }
            }
            val text = StringBuilder()
            var detectedLanguage = language
            AudioChunks.ranges(samples.size, WHISPER_CHUNK_SAMPLES).forEach { range ->
                coroutineContext.ensureActive()
                val stream = recognizer.createStream()
                try {
                    stream.acceptWaveform(samples.copyOfRange(range.first, range.last + 1), AudioCapture.SAMPLE_RATE)
                    coroutineContext.ensureActive()
                    recognizer.decode(stream)
                    coroutineContext.ensureActive()
                    val result = recognizer.getResult(stream)
                    val part = result.text.trim()
                    if (part.isNotEmpty()) {
                        if (text.isNotEmpty()) text.append(' ')
                        check(text.length + part.length <= MAX_TRANSCRIPT_CHARS) { "Dictation text exceeds the supported limit" }
                        text.append(part)
                    }
                    if (detectedLanguage == null) detectedLanguage = result.lang.takeIf { it in spec.languages }
                } finally {
                    stream.release()
                }
            }
            RecognitionResult(text.toString(), detectedLanguage)
        }
    }

    fun openStream(): StreamingRecognition = synchronized(lock) {
        ensureOpen()
        check(spec.streaming && online != null) { "Selected model does not support native streaming" }
        check(activeStream == null) { "A streaming dictation is already active" }
        StreamingRecognition(requireNotNull(online).createStream()).also { activeStream = it }
    }

    override fun close() = synchronized(lock) {
        requireWorkerThread()
        if (!closed) {
            closed = true
            try {
                activeStream?.releaseLocked()
                offline?.release()
                online?.release()
            } finally {
                offline = null
                online = null
                try {
                    lease?.close()
                    lease = null
                } finally {
                    if (slotHeld) RecognitionSlot.release()
                    slotHeld = false
                }
            }
        }
    }

    private fun ensureOpen() {
        requireWorkerThread()
        check(!closed) { "Speech engine is closed" }
    }

    private fun path(name: String): String = repository.file(spec, name).path

    inner class StreamingRecognition internal constructor(private val stream: OnlineStream) : AutoCloseable {
        private var sampleCount = 0
        private var finished = false
        private var released = false

        fun accept(samples: FloatArray): RecognitionResult = synchronized(lock) {
            ensureActive()
            check(!finished) { "Streaming dictation is already finished" }
            validateAudio(samples, MAX_SAMPLES - sampleCount)
            sampleCount += samples.size
            stream.acceptWaveform(samples, AudioCapture.SAMPLE_RATE)
            decodeReady()
            result()
        }

        fun partial(): RecognitionResult = synchronized(lock) {
            ensureActive()
            result()
        }

        fun finish(): RecognitionResult = synchronized(lock) {
            ensureActive()
            check(!finished) { "Streaming dictation is already finished" }
            finished = true
            stream.acceptWaveform(FloatArray(TAIL_PADDING_SAMPLES), AudioCapture.SAMPLE_RATE)
            stream.inputFinished()
            decodeReady()
            result()
        }

        override fun close() = synchronized(lock) {
            requireWorkerThread()
            releaseLocked()
        }

        internal fun releaseLocked() {
            if (!released) {
                released = true
                try {
                    stream.release()
                } finally {
                    if (activeStream === this) activeStream = null
                }
            }
        }

        private fun ensureActive() {
            ensureOpen()
            check(!released && activeStream === this) { "Streaming dictation is closed" }
        }

        private fun decodeReady() {
            val recognizer = requireNotNull(online)
            while (recognizer.isReady(stream)) recognizer.decode(stream)
        }

        private fun result(): RecognitionResult {
            val text = requireNotNull(online).getResult(stream).text.trim()
            check(text.length <= MAX_TRANSCRIPT_CHARS) { "Dictation text exceeds the supported limit" }
            return RecognitionResult(text, language ?: spec.languages.singleOrNull())
        }
    }

    companion object {
        const val MAX_TRANSCRIPT_CHARS = 50_000
        private const val MAX_SAMPLES = AudioCapture.SAMPLE_RATE * 900
        private const val WHISPER_CHUNK_SAMPLES = AudioCapture.SAMPLE_RATE * 29
        private const val TAIL_PADDING_SAMPLES = AudioCapture.SAMPLE_RATE * 66 / 100
    }
}

@WorkerThread
class SpeechLanguageDetector(context: Context, private val spec: ModelSpec) : AutoCloseable {
    private val lock = Any()
    private val repository = ModelRepository(context)
    private var lease: AutoCloseable? = null
    private var slotHeld = false
    private var detector: SpokenLanguageIdentification? = null

    init {
        requireWorkerThread()
        require(spec.role == ModelRole.LANGUAGE_DETECTOR && spec.languageIdentification && spec.family == ModelFamily.WHISPER) {
            "Selected model cannot identify the spoken language"
        }
        try {
            RecognitionSlot.acquire()
            slotHeld = true
            lease = repository.acquire(spec)
            detector = SpokenLanguageIdentification(
                config = SpokenLanguageIdentificationConfig(
                    whisper = SpokenLanguageIdentificationWhisperConfig(
                        encoder = repository.file(spec, "encoder.onnx").path,
                        decoder = repository.file(spec, "decoder.onnx").path,
                    ),
                ),
            )
        } catch (error: Throwable) {
            runCatching(::close).onFailure(error::addSuppressed)
            throw error
        }
    }

    fun detect(samples: FloatArray): String = synchronized(lock) {
        requireWorkerThread()
        validateAudio(samples, AudioCapture.SAMPLE_RATE * 29)
        val identifier = checkNotNull(detector) { "Language detector is closed" }
        val stream = identifier.createStream()
        try {
            stream.acceptWaveform(samples, AudioCapture.SAMPLE_RATE)
            identifier.compute(stream).also {
                check(it in spec.languages) { "Spoken language could not be identified" }
            }
        } finally {
            stream.release()
        }
    }

    override fun close() = synchronized(lock) {
        requireWorkerThread()
        try {
            detector?.release()
            detector = null
        } finally {
            try {
                lease?.close()
                lease = null
            } finally {
                if (slotHeld) RecognitionSlot.release()
                slotHeld = false
            }
        }
    }
}
