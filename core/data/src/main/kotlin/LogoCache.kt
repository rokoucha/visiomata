package net.rokoucha.visiomata.data

import android.content.Context
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

internal class LogoCache private constructor(
    context: Context,
) {
    private val directory = File(context.cacheDir, "station-logos")
    private val memory =
        object : LruCache<String, ByteArray>(MEMORY_MAX_BYTES) {
            override fun sizeOf(
                key: String,
                value: ByteArray,
            ): Int = value.size
        }
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val downloadGate = Semaphore(DOWNLOAD_CONCURRENCY)

    suspend fun load(
        source: String,
        serviceId: Long,
        logoId: Int?,
        fetch: () -> ByteArray,
    ): ByteArray? =
        withContext(Dispatchers.IO) {
            val key = cacheKey(source, serviceId, logoId)
            memory.get(key)?.let { return@withContext it }
            locks.getOrPut(key) { Mutex() }.withLock {
                memory.get(key)?.let { return@withLock it }
                read(key)?.let {
                    memory.put(key, it)
                    return@withLock it
                }
                downloadGate.withPermit {
                    runCatching(fetch).getOrNull()?.takeIf { it.isNotEmpty() }?.also {
                        write(key, it)
                        memory.put(key, it)
                    }
                }
            }
        }

    private fun read(key: String): ByteArray? {
        val file = File(directory, key)
        if (!file.isFile) return null
        return runCatching {
            file.setLastModified(System.currentTimeMillis())
            file.readBytes()
        }.getOrNull()
    }

    private fun write(
        key: String,
        bytes: ByteArray,
    ) {
        runCatching {
            directory.mkdirs()
            val target = File(directory, key)
            val temporary = File.createTempFile("logo-", ".tmp", directory)
            try {
                temporary.writeBytes(bytes)
                if (!temporary.renameTo(target)) {
                    target.writeBytes(bytes)
                }
            } finally {
                temporary.delete()
            }
            pruneDisk()
        }
    }

    private fun pruneDisk() {
        val files = directory.listFiles()?.filter { it.isFile && !it.name.endsWith(".tmp") }.orEmpty()
        var total = files.sumOf { it.length() }
        if (total <= DISK_MAX_BYTES) return
        for (file in files.sortedBy { it.lastModified() }) {
            val size = file.length()
            if (file.delete()) total -= size
            if (total <= DISK_TARGET_BYTES) break
        }
    }

    private fun cacheKey(
        source: String,
        serviceId: Long,
        logoId: Int?,
    ): String {
        val identity = "$source\u0000$serviceId\u0000${logoId ?: "none"}"
        return MessageDigest
            .getInstance("SHA-256")
            .digest(identity.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val MEMORY_MAX_BYTES = 4 * 1024 * 1024
        private const val DISK_MAX_BYTES = 24L * 1024 * 1024
        private const val DISK_TARGET_BYTES = 20L * 1024 * 1024
        private const val DOWNLOAD_CONCURRENCY = 6

        @Volatile private var instance: LogoCache? = null

        fun get(context: Context): LogoCache =
            instance ?: synchronized(this) {
                instance ?: LogoCache(context.applicationContext).also { instance = it }
            }
    }
}
