package net.rokoucha.visiomata.mirakurun

import net.rokoucha.visiomata.mirakurun.api.ChannelsApi
import net.rokoucha.visiomata.mirakurun.api.ConfigApi
import net.rokoucha.visiomata.mirakurun.api.EventsApi
import net.rokoucha.visiomata.mirakurun.api.IptvApi
import net.rokoucha.visiomata.mirakurun.api.JobApi
import net.rokoucha.visiomata.mirakurun.api.LogApi
import net.rokoucha.visiomata.mirakurun.api.ProgramsApi
import net.rokoucha.visiomata.mirakurun.api.ServicesApi
import net.rokoucha.visiomata.mirakurun.api.StatusApi
import net.rokoucha.visiomata.mirakurun.api.StreamApi
import net.rokoucha.visiomata.mirakurun.api.TunersApi
import net.rokoucha.visiomata.mirakurun.api.VersionApi
import okhttp3.Call
import okhttp3.OkHttpClient
import java.util.Base64
import net.rokoucha.visiomata.mahiron.api.DocumentsApi as MahironDocumentsApi
import net.rokoucha.visiomata.mahiron.api.ServicesApi as MahironServicesApi
import net.rokoucha.visiomata.mahiron.api.VersionApi as MahironVersionApi
import net.rokoucha.visiomata.mahiron.model.Version as DetectedVersion

enum class ServerKind { MIRAKURUN, MAHIRON }

class MirakurunApi internal constructor(
    baseUrl: String,
    client: Call.Factory,
) {
    val channels = ChannelsApi(baseUrl, client)
    val config = ConfigApi(baseUrl, client)
    val events = EventsApi(baseUrl, client)
    val iptv = IptvApi(baseUrl, client)
    val jobs = JobApi(baseUrl, client)
    val log = LogApi(baseUrl, client)
    val programs = ProgramsApi(baseUrl, client)
    val services = ServicesApi(baseUrl, client)
    val status = StatusApi(baseUrl, client)
    val streams = StreamApi(baseUrl, client)
    val tuners = TunersApi(baseUrl, client)
    val version = VersionApi(baseUrl, client)
}

class MahironApi internal constructor(
    baseUrl: String,
    client: Call.Factory,
) {
    val documents = MahironDocumentsApi(baseUrl, client)
    val services = MahironServicesApi(baseUrl, client)
}

class ServerConnection internal constructor(
    val version: DetectedVersion,
    val common: MirakurunApi,
    val mahiron: MahironApi?,
) {
    val kind: ServerKind = if (mahiron == null) ServerKind.MIRAKURUN else ServerKind.MAHIRON
}

object MirakurunConnector {
    suspend fun connect(
        baseUrl: String,
        username: String? = null,
        password: String? = null,
        bearerToken: String? = null,
        callFactory: Call.Factory? = null,
    ): ServerConnection {
        val apiBaseUrl = normalizeApiBaseUrl(baseUrl)
        val client = callFactory ?: createHttpClient(username, password, bearerToken)
        val common = MirakurunApi(apiBaseUrl, client)
        // Mahiron's Version schema is a strict superset of Mirakurun's and retains
        // the optional discriminator while still accepting a Mirakurun response.
        val version = MahironVersionApi(apiBaseUrl, client).checkVersion()
        val mahiron = if (isMahiron(version)) MahironApi(apiBaseUrl, client) else null
        return ServerConnection(version, common, mahiron)
    }

    internal fun isMahiron(version: DetectedVersion): Boolean = version.server.equals("mahiron", ignoreCase = true)

    internal fun normalizeApiBaseUrl(value: String): String {
        val root = value.trim().trimEnd('/')
        require(root.startsWith("http://") || root.startsWith("https://")) {
            "baseUrl must use http or https"
        }
        return if (root.endsWith("/api")) root else "$root/api"
    }

    private fun createHttpClient(
        username: String?,
        password: String?,
        bearerToken: String?,
    ): OkHttpClient =
        OkHttpClient
            .Builder()
            .apply {
                if (!bearerToken.isNullOrEmpty()) {
                    addInterceptor { chain ->
                        chain.proceed(
                            chain
                                .request()
                                .newBuilder()
                                .header("Authorization", "Bearer $bearerToken")
                                .build(),
                        )
                    }
                } else if (!username.isNullOrEmpty()) {
                    val token =
                        Base64
                            .getEncoder()
                            .encodeToString("$username:${password.orEmpty()}".toByteArray())
                    addInterceptor { chain ->
                        chain.proceed(
                            chain
                                .request()
                                .newBuilder()
                                .header("Authorization", "Basic $token")
                                .build(),
                        )
                    }
                }
            }.build()
}
