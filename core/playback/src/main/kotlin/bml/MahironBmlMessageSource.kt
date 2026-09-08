package net.rokoucha.visiomata.playback.bml

import android.os.Process
import android.os.Trace
import android.util.Base64
import android.util.Log
import net.rokoucha.visiomata.network.ServerHttpClients
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

internal class MahironBmlMessageSource(
    apiRoot: String,
    private val serviceId: Long,
    basicAuthUsername: String,
    basicAuthPassword: String,
    bearerToken: String,
    private val maxModuleBytes: Long = 16L * 1024 * 1024,
) : BmlMessageSource {
    private data class ModuleKey(
        val componentTag: Int,
        val downloadId: Long,
        val moduleId: Int,
        val version: Int,
    )

    private class ModuleTask(
        private val priority: Int,
        private val sequence: Long,
        private val action: () -> Unit,
    ) : Runnable,
        Comparable<ModuleTask> {
        override fun run() = action()

        override fun compareTo(other: ModuleTask): Int =
            compareValuesBy(this, other, ModuleTask::priority, ModuleTask::sequence)
    }

    private val root = apiRoot.trimEnd('/').let { if (it.endsWith("/api")) it else "$it/api" }
    private val client =
        ServerHttpClients.get(
            apiRoot = root,
            username = basicAuthUsername,
            password = basicAuthPassword,
            bearerToken = bearerToken,
        )
    private val moduleExecutor =
        ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            PriorityBlockingQueue(),
            { runnable ->
                Thread(
                    {
                        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                        runnable.run()
                    },
                    "MahironBmlModules",
                ).apply { isDaemon = true }
            },
        )
    private val reconnectExecutor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(
                {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                    runnable.run()
                },
                "MahironBmlReconnect",
            ).apply { isDaemon = true }
        }
    private val released = AtomicBoolean(false)
    private val active = AtomicBoolean(false)
    private val generation = AtomicInteger()
    private val taskSequence = AtomicLong()
    private val requestedModules = mutableSetOf<ModuleKey>()
    private val entryPointComponents = mutableSetOf<Int>()
    private val calls = mutableSetOf<Call>()

    @Volatile private var consumer: ((String) -> Unit)? = null

    @Volatile private var started = false

    override fun start() {
        if (released.get() || !active.compareAndSet(false, true)) return
        if (consumer != null) {
            started = true
            start(generation.get())
        }
    }

    override fun stop() {
        if (!active.compareAndSet(true, false)) return
        cancelPendingWork()
        started = false
    }

    override fun setConsumer(consumer: ((String) -> Unit)?) {
        this.consumer = consumer
        if (consumer != null && active.get() && !started && !released.get()) {
            started = true
            start(generation.get())
        }
    }

    override fun reset() {
        if (released.get()) return
        cancelPendingWork()
        started = active.get() && consumer != null
        if (started) start(generation.get())
    }

    override fun release() {
        if (!released.compareAndSet(false, true)) return
        active.set(false)
        consumer = null
        cancelPendingWork()
        moduleExecutor.shutdownNow()
        reconnectExecutor.shutdownNow()
        client.dispatcher.cancelAll()
    }

    private fun start(expectedGeneration: Int) {
        enqueue(
            "services/$serviceId/data-broadcast/state?allowCache=1",
            object : Callback {
                override fun onFailure(
                    call: Call,
                    e: IOException,
                ) {
                    removeCall(call)
                    if (isCurrent(expectedGeneration)) {
                        Log.w(logTag, "Initial state unavailable; opening event stream", e)
                        openEvents(expectedGeneration)
                    }
                }

                override fun onResponse(
                    call: Call,
                    response: Response,
                ) {
                    try {
                        response.use {
                            if (isCurrent(expectedGeneration) && it.isSuccessful) {
                                it.body
                                    .string()
                                    .takeIf(String::isNotBlank)
                                    ?.let(::handleSnapshot)
                            }
                        }
                    } catch (e: IOException) {
                        if (!call.isCanceled() && isCurrent(expectedGeneration)) {
                            Log.w(logTag, "Initial state unavailable; opening event stream", e)
                        }
                        if (isCurrent(expectedGeneration)) openEvents(expectedGeneration)
                        return
                    } finally {
                        removeCall(call)
                    }
                    if (isCurrent(expectedGeneration)) openEvents(expectedGeneration)
                }
            },
        )
    }

    /** The generated client models SSE as String, so stream it directly to avoid unbounded buffering. */
    private fun openEvents(expectedGeneration: Int) {
        enqueue(
            "services/$serviceId/data-broadcast/events",
            object : Callback {
                override fun onFailure(
                    call: Call,
                    e: IOException,
                ) {
                    removeCall(call)
                    if (isCurrent(expectedGeneration)) {
                        Log.w(logTag, "Event stream ended; reconnecting", e)
                        scheduleReconnect(expectedGeneration)
                    }
                }

                override fun onResponse(
                    call: Call,
                    response: Response,
                ) {
                    try {
                        response.use {
                            if (!it.isSuccessful) {
                                Log.w(logTag, "Event stream returned HTTP ${it.code}")
                                return@use
                            }
                            val source = it.body.source()
                            while (isCurrent(expectedGeneration) && !source.exhausted()) {
                                val line = source.readUtf8Line() ?: break
                                if (line.startsWith("data:")) {
                                    line
                                        .substringAfter(':')
                                        .trim()
                                        .takeIf(String::isNotEmpty)
                                        ?.let(::handleEvent)
                                }
                            }
                        }
                    } catch (e: IOException) {
                        if (!call.isCanceled() && isCurrent(expectedGeneration)) {
                            Log.w(logTag, "Event stream ended; reconnecting", e)
                        }
                        if (isCurrent(expectedGeneration)) scheduleReconnect(expectedGeneration)
                        return
                    } finally {
                        removeCall(call)
                    }
                    if (isCurrent(expectedGeneration)) scheduleReconnect(expectedGeneration)
                }
            },
        )
    }

    private fun scheduleReconnect(expectedGeneration: Int) {
        if (!isCurrent(expectedGeneration)) return
        reconnectExecutor.schedule(
            { if (isCurrent(expectedGeneration)) start(expectedGeneration) },
            reconnectDelaySeconds,
            TimeUnit.SECONDS,
        )
    }

    private fun handleSnapshot(json: String) {
        val snapshot = JSONObject(json)
        snapshot.optJSONObject("pmt")?.let(::emitPmt)
        emitProgramInfo(snapshot.optJSONObject("programInfo"), snapshot.optLongOrNull("serviceId"))
        snapshot.optJSONObject("pcr")?.let(::emitPcr)
        val components = snapshot.optJSONArray("components") ?: JSONArray()
        for (index in 0 until components.length()) components.optJSONObject(index)?.let(::emitComponentModules)
    }

    private fun handleEvent(json: String) {
        val event = JSONObject(json)
        when (event.optString("type")) {
            "snapshot" -> event.optJSONObject("snapshot")?.let { handleSnapshot(it.toString()) }
            "pmt" -> event.optJSONObject("pmt")?.let(::emitPmt)
            "moduleListUpdated" -> event.optJSONObject("moduleList")?.let(::emitModuleList)
            "moduleUpdated" -> event.optJSONObject("module")?.let(::requestCompletedModule)
            "programInfo" -> emitProgramInfo(event.optJSONObject("programInfo"), serviceId)
            "pcr" -> event.optJSONObject("pcr")?.let(::emitPcr)
            "esEventUpdated" -> event.optJSONObject("esEvent")?.let { emit(it.put("type", "esEventUpdated")) }
            "bit" -> event.optJSONObject("bit")?.let { emit(it.put("type", "bit")) }
        }
    }

    private fun emitPmt(pmt: JSONObject) {
        val normalized = JSONArray()
        val components = pmt.optJSONArray("components") ?: JSONArray()
        val newEntryPointComponents = mutableSetOf<Int>()
        for (index in 0 until components.length()) {
            val component = components.optJSONObject(index) ?: continue
            if (component.optJSONObject("bxmlInfo")?.optBoolean("entryPointFlag") == true) {
                newEntryPointComponents.add(component.optInt("componentTag"))
            }
            normalized.put(
                JSONObject()
                    .put("pid", component.optInt("pid"))
                    .put("componentId", component.optInt("componentTag"))
                    .put("streamType", component.optInt("streamType"))
                    .putIfPresent("dataComponentId", component, "dataComponentId")
                    .putIfPresent("bxmlInfo", component, "bxmlInfo"),
            )
        }
        synchronized(entryPointComponents) {
            entryPointComponents.clear()
            entryPointComponents.addAll(newEntryPointComponents)
        }
        emit(JSONObject().put("type", "pmt").put("components", normalized))
    }

    private fun emitProgramInfo(
        info: JSONObject?,
        fallbackServiceId: Long?,
    ) {
        // Mirakurun-compatible service item IDs are originalNetworkId * 100000 + serviceId.
        // Mahiron's data-broadcast programInfo deliberately contains only the 16-bit service ID,
        // while BML browser APIs also require the original network ID.
        val originalNetworkId = (serviceId / serviceItemIdNetworkMultiplier).toInt()
        val broadcastServiceId =
            info?.optLongOrNull("serviceId") ?: (serviceId % serviceItemIdNetworkMultiplier)
        emit(
            JSONObject()
                .put("type", "programInfo")
                .put("originalNetworkId", originalNetworkId)
                .put("transportStreamId", JSONObject.NULL)
                .put("serviceId", broadcastServiceId.takeIf { it in 0..0xffff } ?: fallbackServiceId ?: JSONObject.NULL)
                .put(
                    "eventId",
                    info?.optJSONArray("eventIds")?.takeIf { it.length() > 0 }?.optInt(0) ?: JSONObject.NULL,
                ).put("eventName", JSONObject.NULL)
                .put("startTimeUnixMillis", JSONObject.NULL)
                .put("durationSeconds", JSONObject.NULL)
                .put("indefiniteDuration", JSONObject.NULL)
                .put("networkId", originalNetworkId),
        )
    }

    private fun emitPcr(pcr: JSONObject) {
        emit(
            JSONObject()
                .put("type", "pcr")
                .put("pcrBase", pcr.optLong("pcrBase"))
                .put("pcrExtension", pcr.optInt("pcrExtension")),
        )
    }

    private fun emitComponentModules(component: JSONObject) {
        val list =
            JSONObject()
                .put("componentTag", component.optInt("componentTag"))
                .put("downloadId", component.optJSONObject("carousel")?.optLong("downloadId") ?: 0L)
                .put("dataEventId", component.optInt("dataEventId"))
                .put("modules", component.optJSONArray("modules") ?: JSONArray())
        emitModuleList(list)
    }

    private fun emitModuleList(list: JSONObject) {
        val componentTag = list.optInt("componentTag")
        val downloadId = list.optLong("downloadId")
        val announced = JSONArray()
        val complete = ArrayList<JSONObject>()
        val modules = list.optJSONArray("modules") ?: JSONArray()
        for (index in 0 until modules.length()) {
            val module = modules.optJSONObject(index) ?: continue
            if (module.optString("status") == "rejected") continue
            announced.put(
                JSONObject()
                    .put("id", module.optInt("moduleId"))
                    .put("version", module.optInt("version"))
                    .put("size", module.optLong("size")),
            )
            if (module.optBoolean("complete") || module.optString("status") == "complete") complete.add(module)
        }
        emit(
            JSONObject()
                .put("type", "moduleListUpdated")
                .put("componentId", componentTag)
                .put("modules", announced)
                .put("dataEventId", list.optInt("dataEventId")),
        )
        complete.sortedBy { if (it.optInt("moduleId") == 0) 0 else 1 }.forEach { module ->
            if (!module.has("componentTag")) module.put("componentTag", componentTag)
            if (!module.has("downloadId")) module.put("downloadId", downloadId)
            requestCompletedModule(module)
        }
    }

    private fun requestCompletedModule(module: JSONObject) {
        if (!(module.optBoolean("complete") || module.optString("status") == "complete")) return
        val key =
            ModuleKey(
                module.optInt("componentTag"),
                module.optLong("downloadId"),
                module.optInt("moduleId"),
                module.optInt("version"),
            )
        if (!synchronized(requestedModules) { requestedModules.add(key) } || released.get()) return
        val expectedGeneration = generation.get()
        val entryPoint = synchronized(entryPointComponents) { key.componentTag in entryPointComponents }
        val priority =
            when {
                entryPoint && key.moduleId == 0 -> 0
                entryPoint -> 1
                key.moduleId == 0 -> 2
                else -> 3
            }
        moduleExecutor.execute(
            ModuleTask(priority, taskSequence.getAndIncrement()) {
                if (!isCurrent(expectedGeneration)) return@ModuleTask
                runCatching { downloadModule(key, expectedGeneration) }.onFailure { error ->
                    synchronized(requestedModules) { requestedModules.remove(key) }
                    if (isCurrent(expectedGeneration)) Log.w(logTag, "Failed to fetch module $key", error)
                }
            },
        )
    }

    private fun downloadModule(
        key: ModuleKey,
        expectedGeneration: Int,
    ) {
        Trace.beginSection("Mahiron BML module")
        try {
            val base =
                "services/$serviceId/data-broadcast/components/${key.componentTag}/carousels/${key.downloadId}" +
                    "/modules/${key.moduleId}/versions/${key.version}"
            val resources = executeJson(base, expectedGeneration).getJSONArray("resources")
            val files = JSONArray()
            var totalBytes = 0L
            for (index in 0 until resources.length()) {
                if (!isCurrent(expectedGeneration)) return
                val resource = resources.getJSONObject(index)
                val bytes = executeBytes("$base/resources/${resource.getString("id")}", expectedGeneration)
                totalBytes += bytes.size
                check(totalBytes <= maxModuleBytes) { "decoded module exceeds receiver limit" }
                val media =
                    resource
                        .optString(
                            "contentType",
                            "application/octet-stream",
                        ).substringBefore(';')
                        .split('/', limit = 2)
                files.put(
                    JSONObject()
                        .put("contentLocation", resource.optNullableString("contentLocation"))
                        .put(
                            "contentType",
                            JSONObject()
                                .put("type", media.getOrElse(0) { "application" })
                                .put("originalType", media.getOrElse(0) { "application" })
                                .put("subtype", media.getOrElse(1) { "octet-stream" })
                                .put("originalSubtype", media.getOrElse(1) { "octet-stream" })
                                .put("parameters", JSONArray()),
                        ).put("dataBase64", Base64.encodeToString(bytes, Base64.NO_WRAP)),
                )
            }
            emit(
                JSONObject()
                    .put("type", "moduleDownloaded")
                    .put("componentId", key.componentTag)
                    .put("moduleId", key.moduleId)
                    .put("files", files)
                    .put("version", key.version)
                    .put("dataEventId", ((key.downloadId ushr 28) and 0x0f).toInt()),
            )
            Log.i(logTag, "Fetched module $key resources=${resources.length()} bytes=$totalBytes")
        } finally {
            Trace.endSection()
        }
    }

    private fun executeJson(
        path: String,
        expectedGeneration: Int,
    ): JSONObject = JSONObject(execute(path, expectedGeneration).use { it.body.string() })

    private fun executeBytes(
        path: String,
        expectedGeneration: Int,
    ): ByteArray = execute(path, expectedGeneration).use { it.body.bytes() }

    private fun execute(
        path: String,
        expectedGeneration: Int,
    ): Response {
        check(isCurrent(expectedGeneration)) { "stale BML request" }
        val call = client.newCall(request(path))
        addCall(call)
        return try {
            val response = call.execute()
            if (!response.isSuccessful) {
                val code = response.code
                response.close()
                throw IOException("Mahiron returned HTTP $code for $path")
            }
            response
        } finally {
            removeCall(call)
        }
    }

    private fun enqueue(
        path: String,
        callback: Callback,
    ) {
        val call = client.newCall(request(path))
        addCall(call)
        call.enqueue(callback)
    }

    private fun request(path: String): Request =
        Request
            .Builder()
            .url("$root/$path")
            .build()

    private fun addCall(call: Call) = synchronized(calls) { calls.add(call) }

    private fun removeCall(call: Call) = synchronized(calls) { calls.remove(call) }

    private fun cancelPendingWork() {
        generation.incrementAndGet()
        synchronized(calls) {
            calls.forEach(Call::cancel)
            calls.clear()
        }
        synchronized(requestedModules) { requestedModules.clear() }
        synchronized(entryPointComponents) { entryPointComponents.clear() }
        moduleExecutor.queue.clear()
    }

    private fun isCurrent(expectedGeneration: Int) =
        active.get() && !released.get() && generation.get() == expectedGeneration

    private fun emit(message: JSONObject) = consumer?.invoke("[$message]")

    private fun JSONObject.putIfPresent(
        targetName: String,
        source: JSONObject,
        sourceName: String,
    ): JSONObject {
        if (source.has(sourceName) && !source.isNull(sourceName)) put(targetName, source.get(sourceName))
        return this
    }

    private fun JSONObject.optLongOrNull(name: String): Long? = if (has(name) && !isNull(name)) optLong(name) else null

    private fun JSONObject.optNullableString(name: String): Any =
        if (has(name) && !isNull(name)) optString(name) else JSONObject.NULL

    private companion object {
        const val reconnectDelaySeconds = 2L
        const val serviceItemIdNetworkMultiplier = 100_000L
        const val logTag = "MahironBmlSource"
    }
}
