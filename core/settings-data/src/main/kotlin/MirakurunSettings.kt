package net.rokoucha.visiomata.settings.data

enum class AuthenticationType {
    None,
    Basic,
    Bearer,
}

enum class Mpeg2PlaybackMode {
    Auto,
    ForceTranscode,
    ForceSoftwareDecoder,
    ForceHardwareDecoder,
}

val Mpeg2PlaybackMode.requiresDeviceMpeg2Decoder: Boolean
    get() = this == Mpeg2PlaybackMode.ForceSoftwareDecoder || this == Mpeg2PlaybackMode.ForceHardwareDecoder

data class MirakurunSettings(
    val url: String = "",
    val authenticationType: AuthenticationType = AuthenticationType.None,
    val username: String = "",
    val password: String = "",
    val bearerToken: String = "",
    val mpeg2PlaybackMode: Mpeg2PlaybackMode = Mpeg2PlaybackMode.Auto,
    val deinterlaceEnabled: Boolean = true,
    val dataBroadcastingEnabled: Boolean = true,
    val dataBroadcastingInternetEnabled: Boolean = false,
    val useMahironDataBroadcastApi: Boolean = true,
    val postalCode: String = "",
)
