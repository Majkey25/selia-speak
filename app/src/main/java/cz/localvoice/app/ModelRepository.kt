package cz.localvoice.app

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class ModelRepository internal constructor(
    private val root: File,
    private val catalog: List<ModelSpec>,
    private val configuration: () -> SpeakConfig,
    private val openConnection: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
) {
    constructor(context: Context) : this(
        File(context.filesDir, "models/catalog-v1"), ModelCatalog.models,
        { SpeakSettings.load(context) },
    )

    init {
        require(catalog.size in 1..200 && catalog.map { it.id }.distinct().size == catalog.size) {
            "Invalid model catalog"
        }
    }

    fun directory(spec: ModelSpec): File {
        require(catalog.any { it == spec }) { "Model is not in the verified catalog" }
        return File(root, spec.id)
    }

    fun file(spec: ModelSpec, name: String): File {
        require(spec.files.any { it.name == name }) { "Unknown model file" }
        return File(directory(spec), name)
    }

    fun isInstalled(spec: ModelSpec): Boolean = synchronized(mutationLock) {
        key(spec) !in operations && stored(spec)
    }

    suspend fun verify(spec: ModelSpec): Boolean = withContext(Dispatchers.IO) {
        beginOperation(spec)
        try {
            verifyStored(spec)
        } finally {
            endOperation(spec)
        }
    }

    // The engine holds a lease until all native users of these paths have been released.
    fun acquire(spec: ModelSpec): AutoCloseable = synchronized(mutationLock) {
        check(isInstalled(spec)) { "Install the model before using it" }
        val key = key(spec)
        users[key] = users.getOrDefault(key, 0) + 1
        val closed = AtomicBoolean(false)
        AutoCloseable {
            if (closed.compareAndSet(false, true)) synchronized(mutationLock) {
                val remaining = users.getValue(key) - 1
                if (remaining == 0) users.remove(key) else users[key] = remaining
            }
        }
    }

    suspend fun download(spec: ModelSpec, onProgress: (Long, Long) -> Unit) = withContext(Dispatchers.IO) {
        beginOperation(spec)
        try {
            val directory = directory(spec)
            check(directory.isDirectory || directory.mkdirs()) { "Could not create model storage" }
            if (verifyStored(spec)) {
                onProgress(spec.totalBytes, spec.totalBytes)
                return@withContext
            }
            check(directory.usableSpace >= spec.totalBytes - spec.files.sumOf { partialBytes(spec, it) }) {
                "Not enough free storage for this model"
            }
            var complete = 0L
            var lastProgress = System.nanoTime() - PROGRESS_INTERVAL_NANOS
            spec.files.forEach { model ->
                coroutineContext.ensureActive()
                val destination = file(spec, model.name)
                if (!matches(destination, model)) {
                    if (destination.exists()) check(destination.delete()) { "Could not replace an invalid model file" }
                    if (!reuseVerifiedFile(spec, model, destination)) {
                        downloadFile(model, destination) { current ->
                            val now = System.nanoTime()
                            if (now - lastProgress >= PROGRESS_INTERVAL_NANOS) {
                                onProgress(complete + current, spec.totalBytes)
                                lastProgress = now
                            }
                        }
                    }
                }
                complete += model.size
                onProgress(complete, spec.totalBytes)
            }
            coroutineContext.ensureActive()
            val marker = File(directory, "ready.part")
            marker.writeText(stamp(spec), Charsets.US_ASCII)
            check(marker.renameTo(File(directory, "ready"))) { "Could not finish model installation" }
        } finally {
            endOperation(spec)
        }
    }

    suspend fun delete(spec: ModelSpec) = withContext(Dispatchers.IO) {
        beginOperation(spec, deleting = true)
        try {
            val directory = directory(spec)
            if (directory.exists()) {
                check(directory.deleteRecursively()) { "Could not remove the model. Try again." }
            }
        } finally {
            endOperation(spec)
        }
    }

    private fun beginOperation(spec: ModelSpec, deleting: Boolean = false) = synchronized(mutationLock) {
        val key = key(spec)
        check(key !in operations) { "This model is already being changed" }
        check(users.getOrDefault(key, 0) == 0) { "Stop dictation before changing this model" }
        if (deleting) check(!configuration().references(spec.id)) {
            "Deselect this model and remove its language routes before deleting it"
        }
        operations.add(key)
    }

    private fun endOperation(spec: ModelSpec) = synchronized(mutationLock) { operations.remove(key(spec)) }

    private fun key(spec: ModelSpec): String = directory(spec).absolutePath

    private fun stored(spec: ModelSpec): Boolean {
        val marker = File(directory(spec), "ready")
        val expected = stamp(spec)
        return marker.isFile && marker.length() == expected.length.toLong() &&
            marker.readText(Charsets.US_ASCII) == expected &&
            spec.files.all { file(spec, it.name).isFile && file(spec, it.name).length() == it.size }
    }

    private fun verifyStored(spec: ModelSpec): Boolean {
        val valid = stored(spec) && spec.files.all { matches(file(spec, it.name), it) }
        if (!valid) File(directory(spec), "ready").delete()
        return valid
    }

    private fun partialBytes(spec: ModelSpec, model: ModelFile): Long {
        val destination = file(spec, model.name)
        if (matches(destination, model)) return model.size
        return File(destination.parentFile, "${destination.name}.part").length().coerceIn(0, model.size)
    }

    private fun reuseVerifiedFile(spec: ModelSpec, model: ModelFile, destination: File): Boolean {
        for (source in catalog) {
            if (source == spec || !isInstalled(source)) continue
            val asset = source.files.firstOrNull { it.sha256 == model.sha256 && it.size == model.size } ?: continue
            val lease = runCatching { acquire(source) }.getOrNull() ?: continue
            lease.use {
                val existing = file(source, asset.name)
                if (!matches(existing, asset)) return@use
                try {
                    Files.createLink(destination.toPath(), existing.toPath())
                } catch (_: UnsupportedOperationException) {
                    existing.copyTo(destination)
                } catch (_: IOException) {
                    existing.copyTo(destination)
                }
                if (matches(destination, model)) return true
                check(destination.delete()) { "Could not discard an invalid shared model file" }
            }
        }
        return false
    }

    private suspend fun downloadFile(model: ModelFile, destination: File, onProgress: (Long) -> Unit) =
        coroutineScope {
            suspendCancellableCoroutine<Unit> { continuation ->
                val connection = AtomicReference<HttpURLConnection?>()
                continuation.invokeOnCancellation { connection.get()?.disconnect() }
                launch(Dispatchers.IO) {
                    try {
                        val partial = File(destination.parentFile, "${destination.name}.part")
                        if (partial.length() >= model.size && !matches(partial, model)) {
                            check(partial.delete()) { "Could not discard an invalid partial download" }
                        }
                        if (!matches(partial, model)) {
                            val existing = partial.length()
                            val request = openConnection(URL(model.url)).apply {
                                connectTimeout = 30_000
                                readTimeout = 30_000
                                setRequestProperty("User-Agent", "SeliaSpeak/0.1")
                                if (existing > 0) setRequestProperty("Range", "bytes=$existing-")
                            }
                            connection.set(request)
                            coroutineContext.ensureActive()
                            try {
                                check(request.responseCode in setOf(200, 206)) { "Model download failed: HTTP ${request.responseCode}" }
                                val append = existing > 0 && request.responseCode == 206
                                val offset = if (append) existing else 0L
                                if (request.responseCode == 206) require(
                                    request.getHeaderField("Content-Range") == "bytes $offset-${model.size - 1}/${model.size}",
                                ) { "Invalid model download range" }
                                require(request.contentLengthLong == -1L || request.contentLengthLong == model.size - offset) {
                                    "Unexpected model download size"
                                }
                                request.inputStream.use { input ->
                                    FileOutputStream(partial, append).buffered().use { output ->
                                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                                        var written = offset
                                        while (true) {
                                            coroutineContext.ensureActive()
                                            val read = input.read(buffer)
                                            if (read < 0) break
                                            require(read.toLong() <= model.size - written) { "Model download exceeds verified size" }
                                            output.write(buffer, 0, read)
                                            written += read
                                            onProgress(written)
                                        }
                                    }
                                }
                                require(matches(partial, model)) { "Model integrity check failed" }
                            } catch (error: IllegalArgumentException) {
                                partial.delete()
                                throw error
                            } finally {
                                request.disconnect()
                                connection.set(null)
                            }
                        }
                        coroutineContext.ensureActive()
                        check(partial.renameTo(destination)) { "Could not finish model download" }
                        if (continuation.isActive) continuation.resume(Unit)
                    } catch (error: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(error)
                        if (error is CancellationException) throw error
                    }
                }
            }
        }

    private fun matches(file: File, model: ModelFile): Boolean =
        file.isFile && file.length() == model.size && sha256(file) == model.sha256

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun stamp(spec: ModelSpec): String = spec.files.joinToString("\n", prefix = "${spec.id}\n") {
        "${it.name}:${it.size}:${it.sha256}"
    }

    companion object {
        private const val PROGRESS_INTERVAL_NANOS = 100_000_000L
        internal val mutationLock = Any()
        private val operations = mutableSetOf<String>()
        private val users = mutableMapOf<String, Int>()
    }
}
