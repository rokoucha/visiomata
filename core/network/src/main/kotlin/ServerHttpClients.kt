package net.rokoucha.visiomata.network

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Shares connections and authenticated cookies for one server and credential set. */
object ServerHttpClients {
    private data class SessionKey(
        val apiRoot: String,
        val authorization: String?,
    )

    private val sessions = ConcurrentHashMap<SessionKey, ServerSession>()

    internal fun clear() = sessions.clear()

    fun get(
        apiRoot: String,
        username: String? = null,
        password: String? = null,
        bearerToken: String? = null,
    ): OkHttpClient {
        val normalizedRoot = apiRoot.trimEnd('/')
        val authorization =
            when {
                !bearerToken.isNullOrEmpty() -> {
                    "Bearer $bearerToken"
                }

                !username.isNullOrEmpty() -> {
                    val token =
                        Base64
                            .getEncoder()
                            .encodeToString("$username:${password.orEmpty()}".toByteArray())
                    "Basic $token"
                }

                else -> {
                    null
                }
            }
        return sessions
            .computeIfAbsent(SessionKey(normalizedRoot, authorization)) { key ->
                ServerSession(key.apiRoot, key.authorization)
            }.client
    }
}

private class ServerSession(
    private val apiRoot: String,
    authorization: String?,
) {
    private val versionUrl =
        Request
            .Builder()
            .url("$apiRoot/version")
            .build()
            .url
    private val cookies = InMemoryCookieJar()
    private val bootstrapClient =
        OkHttpClient
            .Builder()
            .cookieJar(cookies)
            .apply {
                authorization?.let { value ->
                    addInterceptor { chain ->
                        chain.proceed(
                            chain
                                .request()
                                .newBuilder()
                                .header("Authorization", value)
                                .build(),
                        )
                    }
                }
            }.build()
    private val warmupLock = ReentrantLock()

    @Volatile private var warmedUp = false

    val client: OkHttpClient =
        bootstrapClient
            .newBuilder()
            .addInterceptor { chain ->
                val request = chain.request()
                if (request.url == versionUrl) {
                    executeVersionRequest { chain.proceed(request) }
                } else {
                    ensureWarm()
                    chain.proceed(request)
                }
            }.build()

    private fun executeVersionRequest(execute: () -> Response): Response =
        warmupLock.withLock {
            val response = execute()
            if (response.isSuccessful) warmedUp = true
            response
        }

    private fun ensureWarm() {
        if (warmedUp) return
        warmupLock.withLock {
            if (warmedUp) return
            val request = Request.Builder().url(versionUrl).build()
            bootstrapClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("Server returned HTTP ${response.code} for /api/version")
                }
                warmedUp = true
            }
        }
    }
}

private class InMemoryCookieJar : CookieJar {
    private data class Key(
        val name: String,
        val domain: String,
        val path: String,
    )

    private val cookies = linkedMapOf<Key, Cookie>()

    @Synchronized
    override fun saveFromResponse(
        url: HttpUrl,
        cookies: List<Cookie>,
    ) {
        val now = System.currentTimeMillis()
        cookies.forEach { cookie ->
            val key = Key(cookie.name, cookie.domain, cookie.path)
            if (cookie.expiresAt <= now) {
                this.cookies.remove(key)
            } else {
                this.cookies[key] = cookie
            }
        }
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        cookies.entries.removeAll { it.value.expiresAt <= now }
        return cookies.values.filter { it.matches(url) }
    }
}
