package net.rokoucha.visiomata.mirakurun

import kotlinx.coroutines.CancellationException
import net.rokoucha.visiomata.settings.data.AuthenticationType
import net.rokoucha.visiomata.settings.data.MirakurunSettings
import net.rokoucha.visiomata.settings.data.Mpeg2PlaybackMode

class PlaybackSessionUseCase(
    private val connectionUseCase: MirakurunConnectionUseCase,
) {
    suspend operator fun invoke(
        settings: MirakurunSettings,
        serviceId: Long,
    ): PlaybackSession {
        val serverKind =
            if (
                settings.dataBroadcastingEnabled && settings.useMahironDataBroadcastApi
            ) {
                try {
                    connectionUseCase.serverKind(settings)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    ServerKind.MIRAKURUN
                }
            } else {
                ServerKind.MIRAKURUN
            }
        val root = settings.url.trim().trimEnd('/')
        val apiRoot = if (root.endsWith("/api")) root else "$root/api"
        return PlaybackSession(
            streamUrl = "$apiRoot/services/$serviceId/stream",
            basicAuthUsername =
                settings.username
                    .takeIf {
                        settings.authenticationType == AuthenticationType.Basic
                    }.orEmpty(),
            basicAuthPassword =
                settings.password
                    .takeIf {
                        settings.authenticationType == AuthenticationType.Basic
                    }.orEmpty(),
            bearerToken =
                settings.bearerToken
                    .takeIf {
                        settings.authenticationType == AuthenticationType.Bearer
                    }.orEmpty(),
            forceMpeg2Transcoding =
                when (settings.mpeg2PlaybackMode) {
                    Mpeg2PlaybackMode.Auto -> null

                    Mpeg2PlaybackMode.ForceTranscode -> true

                    Mpeg2PlaybackMode.ForceSoftwareDecoder,
                    Mpeg2PlaybackMode.ForceHardwareDecoder,
                    -> false
                },
            forceHardwareMpeg2Decoder =
                when (settings.mpeg2PlaybackMode) {
                    Mpeg2PlaybackMode.Auto,
                    Mpeg2PlaybackMode.ForceTranscode,
                    -> null

                    Mpeg2PlaybackMode.ForceSoftwareDecoder -> false

                    Mpeg2PlaybackMode.ForceHardwareDecoder -> true
                },
            deinterlaceEnabled = settings.deinterlaceEnabled,
            dataBroadcastingEnabled = settings.dataBroadcastingEnabled,
            dataBroadcastingInternetEnabled = settings.dataBroadcastingInternetEnabled,
            mahironApiRoot = settings.url.takeIf { serverKind == ServerKind.MAHIRON },
            serviceId = serviceId,
            postalCode = settings.postalCode,
        )
    }
}

data class PlaybackSession(
    val streamUrl: String,
    val basicAuthUsername: String,
    val basicAuthPassword: String,
    val bearerToken: String,
    val forceMpeg2Transcoding: Boolean?,
    val forceHardwareMpeg2Decoder: Boolean?,
    val deinterlaceEnabled: Boolean,
    val dataBroadcastingEnabled: Boolean,
    val dataBroadcastingInternetEnabled: Boolean,
    val mahironApiRoot: String?,
    val serviceId: Long,
    val postalCode: String,
)
