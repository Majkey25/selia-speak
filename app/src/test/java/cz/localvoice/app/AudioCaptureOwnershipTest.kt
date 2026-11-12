package cz.localvoice.app

import android.Manifest
import android.media.AudioRecord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAudioRecord
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class AudioCaptureOwnershipTest {
    @Test
    fun cancellationKeepsTheSlotUntilReaderExitAndOldStopCannotCleanANewRecording() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        shadowOf(context).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val capture = AudioCapture(120) { 8_000 }
        val firstRecorder = AtomicReference<AudioRecord?>()
        val firstReadEntered = CountDownLatch(1)
        val allowFirstRead = CountDownLatch(1)
        val nextChunkCaptured = CountDownLatch(1)
        val allowNextChunk = CountDownLatch(1)
        ShadowAudioRecord.setSourceProvider { recorder ->
            val first = firstRecorder.compareAndSet(null, recorder) || firstRecorder.get() === recorder
            object : ShadowAudioRecord.AudioRecordSource {
                override fun readInShortArray(audioData: ShortArray, offsetInShorts: Int, sizeInShorts: Int, isBlocking: Boolean): Int {
                    if (first) {
                        firstReadEntered.countDown()
                        check(allowFirstRead.await(5, TimeUnit.SECONDS))
                    }
                    audioData.fill(if (first) 1_000 else 2_000, offsetInShorts, offsetInShorts + sizeInShorts)
                    return sizeInShorts
                }
            }
        }
        try {
            capture.start(context, scope) {}
            assertTrue(firstReadEntered.await(5, TimeUnit.SECONDS))
            val oldStop = async(start = CoroutineStart.UNDISPATCHED) { capture.stop() }
            assertFalse(capture.isRunning)
            assertFailsWith<IllegalStateException> { capture.start(context, scope) {} }
            capture.cancel()
            assertFailsWith<IllegalStateException> { capture.start(context, scope) {} }

            allowFirstRead.countDown()
            assertFailsWith<CancellationException> { withTimeout(5_000) { oldStop.await() } }
            assertEquals(AudioRecord.STATE_UNINITIALIZED, firstRecorder.get()?.state)

            capture.start(context, scope, onSamples = {
                nextChunkCaptured.countDown()
                check(allowNextChunk.await(5, TimeUnit.SECONDS))
            }, onLimit = {})
            assertTrue(nextChunkCaptured.await(5, TimeUnit.SECONDS))
            assertTrue(capture.isRunning)
            val nextStop = async(start = CoroutineStart.UNDISPATCHED) { capture.stop() }
            allowNextChunk.countDown()
            val samples = withTimeout(5_000) { nextStop.await() }
            assertTrue(samples.size > AudioCapture.SAMPLE_RATE / 4)
            assertTrue(samples.all { it == 2_000 / 32_768f })
            assertFalse(capture.isRunning)
        } finally {
            allowFirstRead.countDown()
            allowNextChunk.countDown()
            capture.cancel()
            scope.cancel()
            ShadowAudioRecord.clearSource()
        }
    }
}
