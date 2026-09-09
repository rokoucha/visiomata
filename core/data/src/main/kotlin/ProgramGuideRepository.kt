package net.rokoucha.visiomata.data

import android.content.Context
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import net.rokoucha.visiomata.model.ChannelType
import net.rokoucha.visiomata.model.Program
import net.rokoucha.visiomata.model.ProgramAudio
import net.rokoucha.visiomata.model.ProgramGenre
import net.rokoucha.visiomata.model.ProgramGuide
import net.rokoucha.visiomata.model.ProgramGuideAvailability
import net.rokoucha.visiomata.model.RelatedProgram
import net.rokoucha.visiomata.model.Service
import net.rokoucha.visiomata.network.ServerHttpClients
import net.rokoucha.visiomata.settings.data.AuthenticationType
import net.rokoucha.visiomata.settings.data.MirakurunSettings
import okhttp3.Request
import java.io.IOException
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Room is the source of truth. A future /api/events collector can call [refresh]
 * (or later write event deltas through this repository) and every observer updates via Room.
 */
class ProgramGuideRepository(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val dao = GuideDatabase.get(appContext).guideDao()

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observe(settings: MirakurunSettings): Flow<ProgramGuide> {
        val source = settings.cacheKey
        val homePrograms =
            flow {
                while (true) {
                    emit(System.currentTimeMillis())
                    delay(HOME_QUERY_REFRESH_MILLIS)
                }
            }.flatMapLatest { now -> dao.observeHomePrograms(source, now) }
        return combine(dao.observeServices(source), homePrograms) { services, programs ->
            ProgramGuide(
                services = services.map { it.toModel() },
                programs = programs.map { it.toModel() },
            )
        }
    }

    /**
     * Refreshes the full home snapshot (service catalogue plus every service's current
     * and next programmes) before first display so the home screen never renders a
     * partially loaded service list. Starts immediately because home consumers keep
     * showing their loading indicator until this first refresh completes.
     */
    fun observeWithServiceRefresh(
        settings: MirakurunSettings,
        force: Boolean = false,
    ): Flow<ProgramGuideLoadState> =
        observeWithRefresh(settings, startDelayMillis = 0) {
            refreshHomeSnapshot(settings, force)
        }

    fun observeServiceWithRefresh(
        settings: MirakurunSettings,
        serviceId: Long,
    ): Flow<ProgramGuideLoadState> =
        observeWithRefresh(settings) {
            refreshServices(settings)
            dao.service("${settings.cacheKey}:$serviceId")?.let { service ->
                refreshChannelType(settings, ChannelType(service.channelType))
            }
        }

    private fun observeWithRefresh(
        settings: MirakurunSettings,
        startDelayMillis: Long = AUTO_REFRESH_DELAY_MILLIS,
        refresh: suspend () -> Unit,
    ): Flow<ProgramGuideLoadState> =
        combine(
            observe(settings),
            flow {
                emit(ServiceRefreshResult(isRefreshing = true))
                delay(startDelayMillis)
                val error =
                    try {
                        refresh()
                        null
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (error: Exception) {
                        error
                    }
                emit(ServiceRefreshResult(isRefreshing = false, error = error))
            },
        ) { guide, result ->
            ProgramGuideLoadState(guide, result.isRefreshing, result.error)
        }

    fun observeGuide(
        settings: MirakurunSettings,
        channelType: ChannelType,
        startAt: Instant,
        endAt: Instant,
    ): Flow<ProgramGuide> =
        combine(
            dao.observeGuideServices(settings.cacheKey, channelType.value),
            dao.observeGuidePrograms(
                settings.cacheKey,
                channelType.value,
                startAt.toEpochMilli(),
                endAt.toEpochMilli(),
            ),
        ) { services, programs ->
            ProgramGuide(services.map { it.toModel() }, programs.map { it.toModel() })
        }

    suspend fun refreshGuide(
        settings: MirakurunSettings,
        channelType: ChannelType,
        startAt: Instant,
        endAt: Instant,
    ): ProgramGuideAvailability? =
        refreshMutex.withLock {
            withContext(Dispatchers.IO) {
                val source = settings.cacheKey
                val services = dao.services(source, channelType.value)
                val remote = RemoteGuideSource(settings)
                val requests = Semaphore(PROGRAM_REQUEST_CONCURRENCY)
                // Network latency dominates this path on TV. Fetch independent services in
                // bounded parallel batches, then write bounded batches to Room.
                val fetched =
                    coroutineScope {
                        services
                            .map { service ->
                                async {
                                    requests.withPermit {
                                        service to remote.programs(service.networkId, service.serviceId)
                                    }
                                }
                            }.awaitAll()
                    }
                var availableStart: Instant? = null
                var availableEnd: Instant? = null
                val entities =
                    fetched.asSequence().flatMap { (service, remotePrograms) ->
                        remotePrograms
                            .asSequence()
                            .onEach { program ->
                                val programStart = Instant.ofEpochMilli(program.startAt)
                                val programEnd = programStart.plusMillis(program.duration)
                                availableStart = availableStart?.let { minOf(it, programStart) } ?: programStart
                                availableEnd = availableEnd?.let { maxOf(it, programEnd) } ?: programEnd
                            }.filter {
                                val programStart = Instant.ofEpochMilli(it.startAt)
                                val programEnd = programStart.plusMillis(it.duration)
                                programStart < endAt && programEnd > startAt
                            }.map { it.toEntity(source, service.transportStreamId) }
                    }
                entities.chunked(PROGRAM_BATCH_SIZE).forEach { dao.insertPrograms(it) }
                val first = availableStart
                val last = availableEnd
                if (first != null && last != null && first < last) {
                    ProgramGuideAvailability(first, last)
                } else {
                    null
                }
            }
        }

    suspend fun refreshServices(
        settings: MirakurunSettings,
        force: Boolean = false,
    ) = refreshMutex.withLock {
        withContext(Dispatchers.IO) {
            val source = settings.cacheKey
            val cachedAt = dao.cache(source)?.refreshedAt
            if (!force && cachedAt != null &&
                System.currentTimeMillis() - cachedAt < CACHE_MAX_AGE_MILLIS
            ) {
                return@withContext
            }

            val services =
                RemoteGuideSource(settings).services().filter {
                    it.channel != null && it.type in selectableServiceTypes
                }
            dao.replaceServices(source, services.map { it.toEntity(source) }, System.currentTimeMillis())
        }
    }

    /**
     * Refreshes the service catalogue and every service's home programmes (current plus
     * next) in a single snapshot. Fetching `/programs` once streams the same bytes as
     * one request per service but without per-request round trips, so a cold start with
     * dozens of services completes in roughly two requests. The catalogue and the
     * programmes are written in one transaction, so home observers only ever see the
     * complete set.
     */
    suspend fun refreshHomeSnapshot(
        settings: MirakurunSettings,
        force: Boolean = false,
    ) = refreshMutex.withLock {
        withContext(Dispatchers.IO) {
            val source = settings.cacheKey
            val now = System.currentTimeMillis()
            if (!force) {
                val cachedAt = dao.cache(source)?.refreshedAt
                if (cachedAt != null && now - cachedAt < CACHE_MAX_AGE_MILLIS) {
                    val channelTypes = dao.serviceChannelTypes(source)
                    if (channelTypes.isNotEmpty() &&
                        channelTypes.all { dao.futureProgramCount(source, it, now) > 0 }
                    ) {
                        channelTypes.forEach { channelTypeRefreshedAt.putIfAbsent(source to it, now) }
                        return@withContext
                    }
                }
            }

            val remote = RemoteGuideSource(settings)
            val homePrograms = mutableMapOf<Pair<Int, Int>, MutableList<ProgramDto>>()
            val services =
                coroutineScope {
                    val servicesDeferred = async { remote.services() }
                    launch {
                        remote.forEachProgramBatch { programs ->
                            programs.forEach { program ->
                                if (program.startAt + program.duration > now) {
                                    homePrograms
                                        .getOrPut(program.networkId to program.serviceId) { mutableListOf() }
                                        .add(program)
                                }
                            }
                        }
                    }
                    servicesDeferred.await().filter {
                        it.channel != null && it.type in selectableServiceTypes
                    }
                }
            val transportStreams =
                services.associate { (it.networkId to it.serviceId) to it.transportStreamId }
            val selected = selectHomePrograms(homePrograms, now)
            val updates =
                services.map { service ->
                    ServiceProgramUpdate(
                        service.networkId,
                        service.serviceId,
                        selected[service.networkId to service.serviceId]
                            .orEmpty()
                            .map { it.toEntity(source, transportStreams[it.networkId to it.serviceId]) },
                    )
                }
            val refreshedAt = System.currentTimeMillis()
            dao.replaceHomeSnapshot(source, services.map { it.toEntity(source) }, updates, refreshedAt)
            services.mapNotNullTo(hashSetOf()) { it.channel?.type }.forEach { channelType ->
                channelTypeRefreshedAt[source to channelType] = refreshedAt
            }
        }
    }

    suspend fun refreshChannelType(
        settings: MirakurunSettings,
        channelType: ChannelType,
        force: Boolean = false,
    ) = refreshMutex.withLock {
        withContext(Dispatchers.IO) {
            val source = settings.cacheKey
            val now = System.currentTimeMillis()
            val refreshKey = source to channelType.value
            val refreshedAt = channelTypeRefreshedAt[refreshKey]
            val hasUsableCache = dao.futureProgramCount(source, channelType.value, now) > 0
            if (!force && hasUsableCache &&
                (refreshedAt == null || now - refreshedAt < CACHE_MAX_AGE_MILLIS)
            ) {
                channelTypeRefreshedAt.putIfAbsent(refreshKey, now)
                return@withContext
            }

            val services = dao.services(source, channelType.value)
            val remote = RemoteGuideSource(settings)
            val requests = Semaphore(PROGRAM_REQUEST_CONCURRENCY)
            val updates =
                coroutineScope {
                    services
                        .map { service ->
                            async {
                                requests.withPermit {
                                    val programmes =
                                        remote
                                            .programs(service.networkId, service.serviceId)
                                            .asSequence()
                                            .filter { it.startAt + it.duration > now }
                                            .sortedBy { it.startAt }
                                            .take(HOME_PROGRAMS_PER_SERVICE)
                                            .map { it.toEntity(source, service.transportStreamId) }
                                            .toList()
                                    ServiceProgramUpdate(
                                        service.networkId,
                                        service.serviceId,
                                        programmes,
                                    )
                                }
                            }
                        }.awaitAll()
                }
            dao.replaceProgramsForServices(source, updates)
            channelTypeRefreshedAt[refreshKey] = System.currentTimeMillis()
        }
    }

    suspend fun refresh(
        settings: MirakurunSettings,
        force: Boolean = false,
    ) = refreshMutex.withLock {
        withContext(Dispatchers.IO) {
            val source = settings.cacheKey
            val cachedAt = dao.cache(source)?.refreshedAt
            if (!force && cachedAt != null && System.currentTimeMillis() - cachedAt < CACHE_MAX_AGE_MILLIS) {
                return@withContext
            }
            val staging = "$source#${UUID.randomUUID()}"
            val remote = RemoteGuideSource(settings)
            try {
                val services =
                    remote.services().filter {
                        it.channel != null && it.type in selectableServiceTypes
                    }
                dao.insertServices(services.map { it.toEntity(staging) })
                val transportStreams =
                    services.associate {
                        (it.networkId to it.serviceId) to it.transportStreamId
                    }
                remote.forEachProgramBatch { programs ->
                    dao.insertPrograms(
                        programs.map {
                            it.toEntity(staging, transportStreams[it.networkId to it.serviceId])
                        },
                    )
                }
                dao.promote(staging, source, System.currentTimeMillis())
            } finally {
                withContext(NonCancellable) {
                    dao.deleteServices(staging)
                    dao.deletePrograms(staging)
                }
            }
        }
    }

    suspend fun syncEvents(settings: MirakurunSettings) {
        var retry = EVENT_RETRY_INITIAL_MILLIS
        while (true) {
            currentCoroutineContext().ensureActive()
            val connectedAt = System.currentTimeMillis()
            try {
                val remote = RemoteGuideSource(settings)
                remote.events(
                    onConnected = { reconcileRecentEvents(settings, remote) },
                    onEvent = { applyEvent(settings, remote, it) },
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                // Reconnect after transient failures using the backoff below.
            }
            currentCoroutineContext().ensureActive()
            if (System.currentTimeMillis() - connectedAt >= EVENT_STABLE_CONNECTION_MILLIS) {
                retry = EVENT_RETRY_INITIAL_MILLIS
            }
            val jitter = kotlin.random.Random.nextLong(retry / 2 + 1)
            delay(retry + jitter)
            retry = (retry * 2).coerceAtMost(EVENT_RETRY_MAX_MILLIS)
        }
    }

    private suspend fun reconcileRecentEvents(
        settings: MirakurunSettings,
        remote: RemoteGuideSource,
    ) {
        val source = settings.cacheKey
        val lastEventAt = dao.cache(source)?.lastEventAt
        val events =
            runCatching { remote.recentEvents() }.getOrElse {
                if (it is CancellationException) throw it
                return
            }
        if (lastEventAt != null && events.size >= EVENT_HISTORY_LIMIT && events.first().time > lastEventAt) {
            refresh(settings, force = true)
            return
        }
        events
            .asSequence()
            .filter { lastEventAt == null || it.time >= lastEventAt }
            .forEach { applyEvent(settings, remote, it) }
    }

    private suspend fun applyEvent(
        settings: MirakurunSettings,
        remote: RemoteGuideSource,
        event: GuideEventDto,
    ) = refreshMutex.withLock {
        try {
            applyEventLocked(settings, remote, event)
        } finally {
            withContext(NonCancellable) {
                dao.updateLastEventAt(settings.cacheKey, event.time)
            }
        }
    }

    private suspend fun applyEventLocked(
        settings: MirakurunSettings,
        remote: RemoteGuideSource,
        event: GuideEventDto,
    ) {
        val source = settings.cacheKey
        when (event.resource) {
            "program" -> {
                val id = event.data.long("id") ?: return
                if (event.type == "remove") {
                    dao.deleteProgram("$source:$id")
                } else {
                    val program = remote.programFromEvent(event.data) ?: return
                    val transportStreamId = dao.transportStreamId(source, program.networkId, program.serviceId)
                    dao.insertPrograms(listOf(program.toEntity(source, transportStreamId)))
                }
            }

            "service" -> {
                val id = event.data.long("id") ?: return
                if (event.type == "remove") {
                    val cached = dao.service("$source:$id")
                    dao.deleteService("$source:$id")
                    if (cached != null) dao.deleteProgramsForService(source, cached.networkId, cached.serviceId)
                } else {
                    val service = remote.serviceFromEvent(event.data) ?: return
                    if (service.channel == null || service.type !in selectableServiceTypes) return
                    dao.insertServices(listOf(service.toEntity(source)))
                    dao.updateProgramTransportStream(
                        source,
                        service.networkId,
                        service.serviceId,
                        service.transportStreamId,
                    )
                }
            }
        }
    }

    suspend fun programExtended(
        settings: MirakurunSettings,
        programId: Long,
    ): Map<String, String> =
        withContext(Dispatchers.IO) {
            val cacheId = "${settings.cacheKey}:$programId"
            dao.program(cacheId)?.extendedJson?.let { stringMapAdapter.fromJson(it).orEmpty() }
                ?: RemoteGuideSource(settings).program(programId).extended.orEmpty().also { extended ->
                    dao.updateProgramExtended(cacheId, stringMapAdapter.toJson(extended))
                }
        }

    suspend fun programAudios(
        settings: MirakurunSettings,
        programId: Long,
    ): List<ProgramAudio> =
        withContext(Dispatchers.IO) {
            val cacheId = "${settings.cacheKey}:$programId"
            val stored = dao.program(cacheId)?.audiosJson
            val audios =
                stored?.let(programAudiosAdapter::fromJson)
                    ?: RemoteGuideSource(settings).program(programId).audios.orEmpty().also { remote ->
                        dao.updateProgramAudios(cacheId, programAudiosAdapter.toJson(remote))
                    }
            audios.mapNotNull(ProgramAudioDto::toModel)
        }

    suspend fun logo(
        settings: MirakurunSettings,
        serviceId: Long,
        logoId: Int?,
    ): ByteArray? =
        LogoCache.get(appContext).load(settings.cacheKey, serviceId, logoId) {
            RemoteGuideSource(settings).logo(serviceId)
        }
}

data class ProgramGuideLoadState(
    val guide: ProgramGuide,
    val isRefreshing: Boolean,
    val refreshError: Throwable? = null,
)

private data class ServiceRefreshResult(
    val isRefreshing: Boolean,
    val error: Throwable? = null,
)

private class RemoteGuideSource(
    private val settings: MirakurunSettings,
) {
    private val apiRoot =
        settings.url
            .trim()
            .trimEnd('/')
            .let { if (it.endsWith("/api")) it else "$it/api" }
    private val client =
        ServerHttpClients.get(
            apiRoot = apiRoot,
            username = settings.username.takeIf { settings.authenticationType == AuthenticationType.Basic },
            password = settings.password.takeIf { settings.authenticationType == AuthenticationType.Basic },
            bearerToken = settings.bearerToken.takeIf { settings.authenticationType == AuthenticationType.Bearer },
        )
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val servicesAdapter = listAdapter<ServiceDto>()
    private val eventsAdapter = listAdapter<GuideEventDto>()
    private val programAdapter: JsonAdapter<ProgramDto> = moshi.adapter(ProgramDto::class.java)
    private val serviceAdapter: JsonAdapter<ServiceDto> = moshi.adapter(ServiceDto::class.java)
    private val eventAdapter: JsonAdapter<GuideEventDto> = moshi.adapter(GuideEventDto::class.java)

    fun services(): List<ServiceDto> = json("services", servicesAdapter)

    fun programs(
        networkId: Int,
        serviceId: Int,
    ): List<ProgramDto> = json("programs?networkId=$networkId&serviceId=$serviceId", listAdapter())

    fun recentEvents(): List<GuideEventDto> = json("events", eventsAdapter)

    suspend fun forEachProgramBatch(block: suspend (List<ProgramDto>) -> Unit) {
        response("programs").use { response ->
            response.body.source().use { source ->
                val reader =
                    com.squareup.moshi.JsonReader
                        .of(source)
                reader.beginArray()
                val batch = ArrayList<ProgramDto>(PROGRAM_BATCH_SIZE)
                while (reader.hasNext()) {
                    batch += programAdapter.fromJson(reader)
                        ?: throw IOException("Mirakurunから空の番組が返されました")
                    if (batch.size == PROGRAM_BATCH_SIZE) {
                        block(batch.toList())
                        batch.clear()
                    }
                }
                reader.endArray()
                if (batch.isNotEmpty()) block(batch)
            }
        }
    }

    fun program(id: Long): ProgramDto = json("programs/$id", programAdapter)

    fun programFromEvent(data: Map<String, Any?>): ProgramDto? = programAdapter.fromJsonValue(data)

    fun serviceFromEvent(data: Map<String, Any?>): ServiceDto? = serviceAdapter.fromJsonValue(data)

    fun logo(serviceId: Long): ByteArray = response("services/$serviceId/logo").use { it.body.bytes() }

    suspend fun events(
        onConnected: suspend () -> Unit,
        onEvent: suspend (GuideEventDto) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val eventClient = client.newBuilder().readTimeout(0, TimeUnit.MILLISECONDS).build()
        val call = eventClient.newCall(Request.Builder().url("$apiRoot/events/stream").build())
        val cancellation = currentCoroutineContext().job.invokeOnCompletion { call.cancel() }
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) throw IOException("イベント接続に失敗しました（HTTP ${response.code}）")
                onConnected()
                val source = response.body.source()
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val line = source.readUtf8Line()?.trim() ?: break
                    eventAdapter.parseEventStreamLine(line)?.let { onEvent(it) }
                }
            }
        } finally {
            cancellation.dispose()
            call.cancel()
        }
    }

    private inline fun <reified T> listAdapter(): JsonAdapter<List<T>> =
        moshi.adapter(
            Types.newParameterizedType(List::class.java, T::class.java),
        )

    private fun <T> json(
        path: String,
        adapter: JsonAdapter<T>,
    ): T =
        response(path).use {
            adapter.fromJson(it.body.string()) ?: throw IOException("Mirakurunから空の応答が返されました")
        }

    private fun response(path: String): okhttp3.Response {
        val response = client.newCall(Request.Builder().url("$apiRoot/$path").build()).execute()
        if (!response.isSuccessful) {
            response.close()
            throw IOException("Mirakurunへの接続に失敗しました（HTTP ${response.code}）")
        }
        return response
    }
}

@JsonClass(generateAdapter = true)
internal data class ServiceDto(
    val id: Long,
    val serviceId: Int,
    val networkId: Int,
    val transportStreamId: Int? = null,
    val name: String,
    val type: Int,
    val remoteControlKeyId: Int? = null,
    val logoId: Int? = null,
    val channel: ChannelDto? = null,
)

@JsonClass(generateAdapter = true)
internal data class ChannelDto(
    val type: String,
    val channel: String,
)

@JsonClass(generateAdapter = true)
internal data class ProgramDto(
    val id: Long,
    val eventId: Int,
    val serviceId: Int,
    val networkId: Int,
    val startAt: Long,
    val duration: Long,
    val name: String? = null,
    val description: String? = null,
    val genres: List<ProgramGenreDto>? = null,
    val extended: Map<String, String>? = null,
    val relatedItems: List<RelatedItemDto>? = null,
    val audios: List<ProgramAudioDto>? = null,
)

@JsonClass(generateAdapter = true)
internal data class ProgramAudioDto(
    val componentType: Int? = null,
    val componentTag: Int? = null,
    val isMain: Boolean? = null,
    val langs: List<String>? = null,
)

@JsonClass(generateAdapter = true)
internal data class RelatedItemDto(
    val type: String? = null,
    val networkId: Int? = null,
    val transportStreamId: Int? = null,
    val serviceId: Int? = null,
    val eventId: Int? = null,
)

@JsonClass(generateAdapter = true)
internal data class ProgramGenreDto(
    val lv1: Int? = null,
    val lv2: Int? = null,
)

@JsonClass(generateAdapter = true)
internal data class GuideEventDto(
    val resource: String,
    val type: String,
    val data: Map<String, Any?>,
    val time: Long,
)

internal fun JsonAdapter<GuideEventDto>.parseEventStreamLine(line: String): GuideEventDto? =
    line.trim().takeUnless { it.isEmpty() || it == "[" || it == "," || it == "]" }?.let(::fromJson)

private fun Map<String, Any?>.long(name: String): Long? = (get(name) as? Number)?.toLong()

private fun ServiceDto.toEntity(source: String) =
    ServiceEntity(
        cacheId = "$source:$id",
        source = source,
        id = id,
        networkId = networkId,
        transportStreamId = transportStreamId,
        serviceId = serviceId,
        name = name,
        channelType = requireNotNull(channel).type,
        channel = channel.channel,
        remoteControlKeyId = remoteControlKeyId,
        logoId = logoId,
    )

private fun ProgramDto.toEntity(
    source: String,
    transportStreamId: Int?,
) = ProgramEntity(
    cacheId = "$source:$id",
    source = source,
    id = id,
    eventId = eventId,
    networkId = networkId,
    transportStreamId = transportStreamId,
    serviceId = serviceId,
    title = name,
    description = description,
    startAt = startAt,
    duration = duration,
    genreLevel1 = genres?.firstOrNull()?.lv1,
    genreLevel2 = genres?.firstOrNull()?.lv2,
    extendedJson = extended?.takeIf { it.isNotEmpty() }?.let(stringMapAdapter::toJson),
    relatedItemsJson = relatedItems?.takeIf { it.isNotEmpty() }?.let(relatedItemsAdapter::toJson),
    audiosJson = audios?.takeIf { it.isNotEmpty() }?.let(programAudiosAdapter::toJson),
)

private fun ServiceEntity.toModel() =
    Service(
        id,
        networkId,
        transportStreamId,
        serviceId,
        name,
        ChannelType(channelType),
        channel,
        remoteControlKeyId,
        logoId,
    )

private fun ProgramEntity.toModel() =
    Program(
        id = id,
        eventId = eventId,
        networkId = networkId,
        transportStreamId = transportStreamId,
        serviceId = serviceId,
        title = title ?: "番組情報なし",
        description = description.orEmpty(),
        startAt = Instant.ofEpochMilli(startAt),
        endAt = Instant.ofEpochMilli(startAt + duration),
        primaryGenre = genreLevel1?.let { ProgramGenre(it, genreLevel2 ?: 0) },
        extended = extendedJson?.let(stringMapAdapter::fromJson).orEmpty(),
        relatedPrograms =
            relatedItemsJson?.let(relatedItemsAdapter::fromJson).orEmpty().mapNotNull {
                val type = it.type ?: return@mapNotNull null
                val relatedServiceId = it.serviceId ?: return@mapNotNull null
                val relatedEventId = it.eventId ?: return@mapNotNull null
                RelatedProgram(
                    type = type,
                    networkId = it.networkId ?: networkId,
                    transportStreamId = it.transportStreamId ?: transportStreamId,
                    serviceId = relatedServiceId,
                    eventId = relatedEventId,
                )
            },
        audios =
            audiosJson
                ?.let(programAudiosAdapter::fromJson)
                .orEmpty()
                .mapNotNull(ProgramAudioDto::toModel),
    )

/**
 * Keeps each service's home programmes: still airing or upcoming, oldest first, up to
 * [HOME_PROGRAMS_PER_SERVICE] entries covering the current and next programmes.
 */
internal fun selectHomePrograms(
    programsByService: Map<Pair<Int, Int>, List<ProgramDto>>,
    now: Long,
): Map<Pair<Int, Int>, List<ProgramDto>> =
    programsByService
        .mapValues { (_, programs) ->
            programs
                .asSequence()
                .filter { it.startAt + it.duration > now }
                .sortedBy { it.startAt }
                .take(HOME_PROGRAMS_PER_SERVICE)
                .toList()
        }.filterValues { it.isNotEmpty() }

private fun ProgramAudioDto.toModel(): ProgramAudio? =
    ProgramAudio(
        componentType = componentType ?: return null,
        componentTag = componentTag ?: return null,
        isMain = isMain ?: false,
        languages = langs.orEmpty(),
    )

private val stringMapAdapter: JsonAdapter<Map<String, String>> =
    Moshi.Builder().build().adapter(
        Types.newParameterizedType(Map::class.java, String::class.java, String::class.java),
    )

private val relatedItemsAdapter: JsonAdapter<List<RelatedItemDto>> =
    Moshi
        .Builder()
        .add(KotlinJsonAdapterFactory())
        .build()
        .adapter(
            Types.newParameterizedType(List::class.java, RelatedItemDto::class.java),
        )

private val programAudiosAdapter: JsonAdapter<List<ProgramAudioDto>> =
    Moshi
        .Builder()
        .add(KotlinJsonAdapterFactory())
        .build()
        .adapter(
            Types.newParameterizedType(List::class.java, ProgramAudioDto::class.java),
        )

private val MirakurunSettings.cacheKey: String
    get() = url.trim().trimEnd('/').lowercase()

private val selectableServiceTypes = setOf(0x01, 0xA1, 0xA5, 0xAD)
private const val PROGRAM_BATCH_SIZE = 500
private const val HOME_PROGRAMS_PER_SERVICE = 2
private const val HOME_QUERY_REFRESH_MILLIS = 60_000L
private const val AUTO_REFRESH_DELAY_MILLIS = 1_500L
private const val PROGRAM_REQUEST_CONCURRENCY = 4
private const val CACHE_MAX_AGE_MILLIS = 15 * 60 * 1000L
private const val EVENT_RETRY_INITIAL_MILLIS = 1_000L
private const val EVENT_RETRY_MAX_MILLIS = 60_000L
private const val EVENT_STABLE_CONNECTION_MILLIS = 60_000L
private const val EVENT_HISTORY_LIMIT = 100
private val refreshMutex = Mutex()
private val channelTypeRefreshedAt = mutableMapOf<Pair<String, String>, Long>()
