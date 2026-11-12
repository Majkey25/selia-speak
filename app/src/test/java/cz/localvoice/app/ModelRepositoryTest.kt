package cz.localvoice.app

import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URL
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test

class ModelRepositoryTest {
    @Test
    fun verifiedInstallationReusesAssetsAndLeasesPreventDeletingAnActiveModel() = runBlocking {
        val model = fixtureModel()
        val detector = model.copy(id = "fixture-detector", role = ModelRole.LANGUAGE_DETECTOR)
        withServer("model", listOf(model, detector)) { repository, calls ->
            repository.download(model) { _, _ -> }
            assertTrue(repository.isInstalled(model))
            assertTrue(repository.verify(model))
            repository.download(detector) { _, _ -> }
            assertEquals(1, calls.get())
            val lease = repository.acquire(model)
            try {
                assertFailsWith<IllegalStateException> { repository.delete(model) }
                assertTrue(repository.isInstalled(model))
            } finally {
                lease.close()
            }
            repository.delete(model)
            assertFalse(repository.isInstalled(model))
            assertTrue(repository.verify(detector))
        }
    }

    @Test
    fun corruptFileAndOversizedResponseNeverBecomeUsableModels() = runBlocking {
        val model = fixtureModel()
        withServer("model", listOf(model)) { repository, _ ->
            repository.download(model) { _, _ -> }
            repository.file(model, "model.onnx").writeText("other")
            assertFalse(repository.verify(model))
            assertFalse(repository.isInstalled(model))
            assertFailsWith<IllegalStateException> { repository.acquire(model) }
        }
        withServer("model-extra", listOf(model)) { repository, _ ->
            assertFailsWith<IllegalArgumentException> { repository.download(model) { _, _ -> } }
            assertFalse(repository.isInstalled(model))
            assertFalse(repository.file(model, "model.onnx").exists())
        }
    }

    @Test
    fun cancellationKeepsTheMutationGuardUntilTheBlockedWorkerActuallyExits() = runBlocking {
        val root = Files.createTempDirectory("model-cancel").toFile()
        val model = fixtureModel()
        val reading = CountDownLatch(1)
        val disconnected = CountDownLatch(1)
        val release = CountDownLatch(1)
        val connection = object : HttpURLConnection(URL(model.files.single().url)) {
            override fun connect() = Unit
            override fun usingProxy(): Boolean = false
            override fun disconnect() { disconnected.countDown() }
            override fun getResponseCode(): Int = 200
            override fun getContentLengthLong(): Long = 5
            override fun getInputStream(): InputStream = object : InputStream() {
                override fun read(): Int {
                    reading.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    throw IOException("Closed fixture transport")
                }
            }
        }
        val repository = ModelRepository(root, listOf(model), { SpeakConfig() }, { connection })
        val job = launch(Dispatchers.Default) { repository.download(model) { _, _ -> } }
        try {
            assertTrue(reading.await(5, TimeUnit.SECONDS))
            job.cancel()
            assertTrue(disconnected.await(5, TimeUnit.SECONDS))
            assertFailsWith<IllegalStateException> { repository.delete(model) }
            assertFailsWith<IllegalStateException> { repository.download(model) { _, _ -> } }
            release.countDown()
            withTimeout(5_000) { job.join() }
            assertFalse(repository.isInstalled(model))
            repository.delete(model)
        } finally {
            release.countDown()
            job.cancel()
            job.join()
            root.deleteRecursively()
        }
    }

    @Test
    fun configuredModelCannotBeDeletedEvenWhenTheEngineIsStopped() = runBlocking<Unit> {
        val root = Files.createTempDirectory("model-selected").toFile()
        val model = fixtureModel()
        val repository = ModelRepository(root, listOf(model), { SpeakConfig(activeModelId = model.id) })
        try {
            assertFailsWith<IllegalStateException> { repository.delete(model) }
        } finally {
            root.deleteRecursively()
        }
    }

    private suspend fun withServer(
        body: String,
        models: List<ModelSpec>,
        block: suspend (ModelRepository, AtomicInteger) -> Unit,
    ) {
        val root = Files.createTempDirectory("model-repository").toFile()
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 5_000 }
        val executor = Executors.newSingleThreadExecutor()
        val response = executor.submit {
            server.accept().use { socket ->
                socket.soTimeout = 5_000
                val request = socket.getInputStream().bufferedReader()
                while (!request.readLine().isNullOrEmpty()) Unit
                socket.getOutputStream().write(
                    "HTTP/1.1 200 OK\r\nConnection: close\r\nContent-Length: ${body.length}\r\n\r\n$body".toByteArray(),
                )
            }
        }
        val calls = AtomicInteger()
        val repository = ModelRepository(root, models, { SpeakConfig() }) {
            check(calls.incrementAndGet() == 1) { "Unexpected duplicate asset download" }
            URL("http://127.0.0.1:${server.localPort}/model").openConnection() as HttpURLConnection
        }
        try {
            block(repository, calls)
            response.get(5, TimeUnit.SECONDS)
        } finally {
            server.close()
            executor.shutdownNow()
            root.deleteRecursively()
        }
    }

    private fun fixtureModel(): ModelSpec = ModelSpec(
        id = "fixture", name = "Fixture recognizer", family = ModelFamily.WHISPER, role = ModelRole.ASR,
        languages = setOf("en"), autoLanguage = true, languageIdentification = false, streaming = false,
        minRamBytes = null, license = "MIT", sourceUrl = "https://huggingface.co/fixture/model",
        files = listOf(
            ModelFile(
                "model.onnx", "https://huggingface.co/fixture/model/resolve/${"0".repeat(40)}/model.onnx", 5,
                MessageDigest.getInstance("SHA-256").digest("model".toByteArray()).joinToString("") { "%02x".format(it) },
            ),
        ),
    )
}
