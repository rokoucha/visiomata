package net.rokoucha.visiomata.data

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.transformLatest
import net.rokoucha.visiomata.model.ChannelType
import net.rokoucha.visiomata.model.Program
import net.rokoucha.visiomata.model.ProgramGuide
import net.rokoucha.visiomata.model.ProgramGuideAvailability
import net.rokoucha.visiomata.model.Service
import net.rokoucha.visiomata.settings.data.MirakurunSettings
import java.time.Instant

class ProgramGuideUseCases(
    context: Context,
) {
    private val repository = ProgramGuideRepository(context)

    fun observeHome(settings: MirakurunSettings): Flow<ProgramGuideLoadState> =
        repository.observeWithServiceRefresh(settings)

    fun observeGuide(
        settings: MirakurunSettings,
        channelType: ChannelType,
        startAt: Instant,
        endAt: Instant,
    ): Flow<ProgramGuideWindowLoadState> =
        combine(
            repository.observeGuide(settings, channelType, startAt, endAt),
            flow {
                emit(GuideRefreshResult(isRefreshing = true))
                val result =
                    try {
                        Result.success(repository.refreshGuide(settings, channelType, startAt, endAt))
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (error: Exception) {
                        Result.failure(error)
                    }
                emit(
                    GuideRefreshResult(
                        isRefreshing = false,
                        availability = result.getOrNull(),
                        error = result.exceptionOrNull(),
                    ),
                )
            },
        ) { guide, refresh ->
            ProgramGuideWindowLoadState(
                guide = guide,
                isRefreshing = refresh.isRefreshing,
                availability = refresh.availability,
                refreshError = refresh.error,
            )
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observePlayback(
        settings: MirakurunSettings,
        serviceId: Long,
    ): Flow<PlaybackProgramData?> =
        repository
            .observeServiceWithRefresh(settings, serviceId)
            .transformLatest { loadState ->
                while (true) {
                    val now = Instant.now()
                    val service = loadState.guide.services.firstOrNull { it.id == serviceId }
                    val schedule = service?.let(loadState.guide::schedule).orEmpty()
                    val program =
                        schedule.lastOrNull {
                            !now.isBefore(it.startAt) && now.isBefore(it.endAt)
                        }
                    if (service == null || program == null) {
                        emit(null)
                    } else {
                        val extended =
                            (
                                try {
                                    repository.programExtended(settings, program.id)
                                } catch (cancellation: CancellationException) {
                                    throw cancellation
                                } catch (_: Exception) {
                                    program.extended
                                }
                            ).ifEmpty { program.extended }
                        val logo = repository.logo(settings, service.id, service.logoId)
                        val audios =
                            (
                                try {
                                    repository.programAudios(settings, program.id)
                                } catch (cancellation: CancellationException) {
                                    throw cancellation
                                } catch (_: Exception) {
                                    program.audios
                                }
                            )
                        emit(
                            PlaybackProgramData(
                                service = service,
                                program = program.copy(audios = audios),
                                nextProgram =
                                    schedule.firstOrNull {
                                        !it.startAt.isBefore(program.endAt)
                                    },
                                extended = extended,
                                serviceLogo = logo,
                                observedAt = now,
                            ),
                        )
                    }
                    delay(PLAYBACK_PROGRAM_REFRESH_MILLIS)
                }
            }

    suspend fun selectChannelType(
        settings: MirakurunSettings,
        channelType: ChannelType,
    ) = repository.refreshChannelType(settings, channelType)

    suspend fun refreshHome(
        settings: MirakurunSettings,
        selectedType: ChannelType?,
        force: Boolean,
    ) {
        repository.refreshServices(settings, force)
        selectedType?.let { repository.refreshChannelType(settings, it, force) }
    }

    suspend fun refreshAll(settings: MirakurunSettings) = repository.refresh(settings, force = true)

    suspend fun syncEvents(settings: MirakurunSettings) {
        repository.syncEvents(settings)
    }

    suspend fun programExtended(
        settings: MirakurunSettings,
        programId: Long,
    ): Map<String, String> = repository.programExtended(settings, programId)

    suspend fun logo(
        settings: MirakurunSettings,
        serviceId: Long,
        logoId: Int?,
    ): ByteArray? = repository.logo(settings, serviceId, logoId)
}

data class ProgramGuideWindowLoadState(
    val guide: ProgramGuide,
    val isRefreshing: Boolean,
    val availability: ProgramGuideAvailability? = null,
    val refreshError: Throwable? = null,
)

data class PlaybackProgramData(
    val service: Service,
    val program: Program,
    val nextProgram: Program?,
    val extended: Map<String, String>,
    val serviceLogo: ByteArray?,
    val observedAt: Instant,
)

private data class GuideRefreshResult(
    val isRefreshing: Boolean,
    val availability: ProgramGuideAvailability? = null,
    val error: Throwable? = null,
)

private const val PLAYBACK_PROGRAM_REFRESH_MILLIS = 30_000L
