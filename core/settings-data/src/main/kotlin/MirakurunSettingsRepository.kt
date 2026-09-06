package net.rokoucha.visiomata.settings.data

import android.content.Context
import androidx.preference.PreferenceManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class MirakurunSettingsRepository(
    context: Context,
) {
    private val preferences = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
    private val secretCipher = KeystoreSecretCipher()
    private val mutableSettings = MutableStateFlow(read())

    val settings: StateFlow<MirakurunSettings> = mutableSettings.asStateFlow()

    fun update(value: MirakurunSettings) {
        mutableSettings.value = value
        preferences
            .edit()
            .putString(KEY_URL, value.url)
            .putString(KEY_AUTH_TYPE, value.authenticationType.name)
            .putString(KEY_USERNAME, value.username)
            .putEncryptedSecret(KEY_PASSWORD_ENCRYPTED, value.password)
            .putEncryptedSecret(KEY_BEARER_TOKEN_ENCRYPTED, value.bearerToken)
            .putString(KEY_MPEG2_PLAYBACK_MODE, value.mpeg2PlaybackMode.name)
            .putBoolean(KEY_DEINTERLACE_ENABLED, value.deinterlaceEnabled)
            .putBoolean(KEY_DATA_BROADCASTING_ENABLED, value.dataBroadcastingEnabled)
            .putBoolean(KEY_DATA_BROADCASTING_INTERNET_ENABLED, value.dataBroadcastingInternetEnabled)
            .putBoolean(KEY_USE_MAHIRON_DATA_BROADCAST_API, value.useMahironDataBroadcastApi)
            .putString(KEY_POSTAL_CODE, value.postalCode)
            .apply()
    }

    private fun read(): MirakurunSettings {
        val authenticationType =
            preferences
                .getString(KEY_AUTH_TYPE, null)
                ?.let { stored -> AuthenticationType.entries.firstOrNull { it.name == stored } }
                ?: AuthenticationType.None
        val mpeg2PlaybackMode =
            preferences
                .getString(KEY_MPEG2_PLAYBACK_MODE, null)
                ?.let { stored -> Mpeg2PlaybackMode.entries.firstOrNull { it.name == stored } }
                ?: Mpeg2PlaybackMode.Auto
        return MirakurunSettings(
            url = preferences.getString(KEY_URL, "").orEmpty(),
            authenticationType = authenticationType,
            username = preferences.getString(KEY_USERNAME, "").orEmpty(),
            password = readEncryptedSecret(KEY_PASSWORD_ENCRYPTED),
            bearerToken = readEncryptedSecret(KEY_BEARER_TOKEN_ENCRYPTED),
            mpeg2PlaybackMode = mpeg2PlaybackMode,
            deinterlaceEnabled = preferences.getBoolean(KEY_DEINTERLACE_ENABLED, true),
            dataBroadcastingEnabled = preferences.getBoolean(KEY_DATA_BROADCASTING_ENABLED, true),
            dataBroadcastingInternetEnabled =
                preferences.getBoolean(
                    KEY_DATA_BROADCASTING_INTERNET_ENABLED,
                    false,
                ),
            useMahironDataBroadcastApi = preferences.getBoolean(KEY_USE_MAHIRON_DATA_BROADCAST_API, true),
            postalCode = preferences.getString(KEY_POSTAL_CODE, "").orEmpty(),
        )
    }

    private fun readEncryptedSecret(key: String): String =
        preferences
            .getString(key, null)
            ?.let { encrypted -> secretCipher.decrypt(encrypted, key) }
            .orEmpty()

    private fun android.content.SharedPreferences.Editor.putEncryptedSecret(
        key: String,
        value: String,
    ): android.content.SharedPreferences.Editor =
        if (value.isEmpty()) {
            remove(key)
        } else {
            putString(key, secretCipher.encrypt(value, key))
        }

    private companion object {
        const val KEY_URL = "mirakurun.url"
        const val KEY_AUTH_TYPE = "mirakurun.authentication_type"
        const val KEY_USERNAME = "mirakurun.username"
        const val KEY_PASSWORD_ENCRYPTED = "mirakurun.password.encrypted"
        const val KEY_BEARER_TOKEN_ENCRYPTED = "mirakurun.bearer_token.encrypted"
        const val KEY_MPEG2_PLAYBACK_MODE = "playback.mpeg2_playback_mode"
        const val KEY_DEINTERLACE_ENABLED = "playback.deinterlace_enabled"
        const val KEY_DATA_BROADCASTING_ENABLED = "playback.data_broadcasting_enabled"
        const val KEY_DATA_BROADCASTING_INTERNET_ENABLED =
            "playback.data_broadcasting_internet_enabled"
        const val KEY_USE_MAHIRON_DATA_BROADCAST_API = "playback.use_mahiron_data_broadcast_api"
        const val KEY_POSTAL_CODE = "playback.postal_code"
    }
}
