package net.rokoucha.visiomata.playback.bml

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Trace
import android.util.Base64
import android.util.Log
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

private const val appAssetsHost = "appassets.androidplatform.net"
private const val startUrl = "https://$appAssetsHost/web-bml/index.html"

// WebView construction needs independent browser settings and host callbacks.
@Suppress("LongParameterList")
@SuppressLint("SetJavaScriptEnabled")
internal class BmlWebView(
    context: Context,
    private val messageSource: BmlMessageSource,
    private val postalCode: String,
    private val internetAccessEnabled: Boolean,
    private val acceptsKeyFocus: Boolean,
    private val lowMemoryMode: Boolean,
    private val onBmlInvisibleChanged: (Boolean) -> Unit,
    private val onBmlUsedKeyGroupsChanged: (Set<String>) -> Unit,
    private val onBmlVideoRectChanged: (left: Float, top: Float, width: Float, height: Float) -> Unit,
) : WebView(context) {
    private val released = AtomicBoolean(false)

    @Volatile private var active = false
    private var pageLoaded = false

    @Volatile private var generation = 0
    private val networkBridge =
        BmlNetworkBridge(context, internetAccessEnabled) { requestId, response ->
            post {
                if (!released.get()) {
                    evaluateJavascript(
                        "window.AndroidBmlNetworkResponse?.(" +
                            "${JSONObject.quote(requestId)},${JSONObject.quote(response)})",
                        null,
                    )
                }
            }
        }

    init {
        setBackgroundColor(Color.TRANSPARENT)
        // PlayerView's SurfaceView remains below this sibling. The web bundle's own
        // HTMLVideoElement is disabled in index.html; only the transparent BML graphics plane is
        // rendered here.
        // Keep the WebView on a stable hardware layer on regular devices. Low-RAM devices avoid
        // retaining a full-screen layer and let the compositor manage it to reduce graphics use.
        if (!lowMemoryMode) setLayerType(View.LAYER_TYPE_HARDWARE, null)
        isFocusable = acceptsKeyFocus
        isFocusableInTouchMode = acceptsKeyFocus
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        // BML positions text in fixed pixel boxes. System font scaling enlarges only the text
        // and clips it; the graphics plane is already scaled as a whole to fit the player.
        settings.textZoom = 100
        if (lowMemoryMode) settings.cacheMode = WebSettings.LOAD_NO_CACHE
        settings.mediaPlaybackRequiresUserGesture = false
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        addJavascriptInterface(networkBridge, "AndroidBmlNetwork")
        addJavascriptInterface(
            object {
                @Volatile private var lastInvisible: Boolean? = null
                private var lastVideoLeft = Double.NaN
                private var lastVideoTop = Double.NaN
                private var lastVideoWidth = Double.NaN
                private var lastVideoHeight = Double.NaN

                @JavascriptInterface
                fun onInvisibleChanged(invisible: Boolean) {
                    if (lastInvisible == invisible) return
                    lastInvisible = invisible
                    Log.d("BmlBridge", "invisible=$invisible")
                    val expectedGeneration = generation
                    post {
                        if (released.get() || !active || generation != expectedGeneration) return@post
                        // The embedded page is transparent and web-bml already moves its logical video
                        // plane in response to this event. Do not mutate WebView alpha/visibility here:
                        // that would invalidate the entire hardware layer.
                        onBmlInvisibleChanged(invisible)
                    }
                }

                @JavascriptInterface
                fun onUsedKeyListChanged(usedKeyList: String) {
                    val groups =
                        usedKeyList
                            .split(Regex("[\\s,]+"))
                            .filter(String::isNotBlank)
                            .toSet()
                    Log.d("BmlBridge", "usedKeyList=$usedKeyList groups=$groups")
                    val expectedGeneration = generation
                    post {
                        if (!released.get() && active && generation == expectedGeneration) {
                            onBmlUsedKeyGroupsChanged(groups)
                        }
                    }
                }

                @JavascriptInterface
                @Synchronized
                fun onVideoRectChanged(
                    left: Double,
                    top: Double,
                    width: Double,
                    height: Double,
                ) {
                    if (
                        lastVideoLeft == left &&
                        lastVideoTop == top &&
                        lastVideoWidth == width &&
                        lastVideoHeight == height
                    ) {
                        return
                    }
                    lastVideoLeft = left
                    lastVideoTop = top
                    lastVideoWidth = width
                    lastVideoHeight = height
                    Log.d("BmlBridge", "videoRect=$left,$top ${width}x$height")
                    val expectedGeneration = generation
                    post {
                        if (released.get() || !active || generation != expectedGeneration) return@post
                        Trace.beginSection("BML video rect callback")
                        try {
                            onBmlVideoRectChanged(
                                left.toFloat(),
                                top.toFloat(),
                                width.toFloat(),
                                height.toFloat(),
                            )
                        } finally {
                            Trace.endSection()
                        }
                    }
                }
            },
            "AndroidBml",
        )
        webChromeClient =
            object : WebChromeClient() {
                override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                    Log.d(
                        "BmlConsole",
                        "${message.messageLevel()} ${message.message()} (${message.sourceId()}:${message.lineNumber()})",
                    )
                    return true
                }
            }
        webViewClient =
            object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest,
                ): WebResourceResponse? {
                    if (request.url.host != appAssetsHost) {
                        val isHttp = request.url.scheme == "http" || request.url.scheme == "https"
                        return if (internetAccessEnabled || !isHttp) null else blockedNetworkResponse()
                    }
                    val assetPath = request.url.path?.removePrefix("/") ?: return null
                    if (!assetPath.startsWith("web-bml/")) return null
                    val mimeType =
                        when {
                            assetPath.endsWith(".html") -> "text/html"
                            assetPath.endsWith(".js") -> "application/javascript"
                            assetPath.endsWith(".woff2") -> "font/woff2"
                            else -> "application/octet-stream"
                        }
                    return runCatching {
                        WebResourceResponse(mimeType, "UTF-8", context.assets.open(assetPath))
                    }.getOrNull()
                }

                override fun onPageFinished(
                    view: WebView,
                    url: String,
                ) {
                    if (released.get()) return
                    pageLoaded = true
                    if (postalCode.length == 7 && postalCode.all(Char::isDigit)) {
                        view.evaluateJavascript(
                            "localStorage.setItem('nvram_prefix=receiverinfo%2Fzipcode', btoa('$postalCode'))",
                            null,
                        )
                    } else {
                        view.evaluateJavascript(
                            "localStorage.removeItem('nvram_prefix=receiverinfo%2Fzipcode')",
                            null,
                        )
                    }
                    view.evaluateJavascript(
                        """
                        (() => {
                          const root = document.getElementById('data-broadcasting-browser');
                          const fit = () => {
                            const w = parseFloat(root.style.width) || 960;
                            const h = parseFloat(root.style.height) || 540;
                            const scale = Math.min(innerWidth / w, innerHeight / h);
                            root.style.left = ((innerWidth - w * scale) / 2) + 'px';
                            root.style.top = ((innerHeight - h * scale) / 2) + 'px';
                            root.style.transform = 'scale(' + scale + ')';
                          };
                          new MutationObserver(fit).observe(root, {attributes: true});
                          addEventListener('resize', fit);
                          fit();
                        })();
                        """.trimIndent(),
                        null,
                    )
                    if (active) attachConsumer()
                    if (active && acceptsKeyFocus) view.requestFocus()
                }
            }
        loadUrl(startUrl)
    }

    fun setActive(active: Boolean) {
        if (released.get() || this.active == active) return
        this.active = active
        generation++
        if (active) {
            networkBridge.start()
            if (pageLoaded) attachConsumer()
        } else {
            messageSource.setConsumer(null)
            networkBridge.stop()
        }
    }

    fun reloadForNewStream() {
        if (released.get()) return
        messageSource.setConsumer(null)
        pageLoaded = false
        generation++
        networkBridge.stop()
        if (active) networkBridge.start()
        reload()
    }

    private fun attachConsumer() {
        val expectedGeneration = generation
        messageSource.setConsumer { messages ->
            if (released.get() || !active || generation != expectedGeneration) return@setConsumer
            post {
                if (released.get() || !active || generation != expectedGeneration) return@post
                Trace.beginSection("BML dispatch JS")
                try {
                    evaluateJavascript("window.AndroidBmlMessages?.($messages)", null)
                } finally {
                    Trace.endSection()
                }
            }
        }
    }

    fun release() {
        if (!released.compareAndSet(false, true)) return
        active = false
        messageSource.setConsumer(null)
        networkBridge.release()
        stopLoading()
        removeJavascriptInterface("AndroidBml")
        removeJavascriptInterface("AndroidBmlNetwork")
        webChromeClient = null
        webViewClient = WebViewClient()
        removeAllViews()
        destroy()
    }

    fun dispatchRemoteKey(
        key: String,
        isDown: Boolean,
    ) {
        if (released.get() || !active) return
        val eventType = if (isDown) "keydown" else "keyup"
        val safeKey = key.replace("\\", "\\\\").replace("'", "\\'")
        evaluateJavascript(
            "window.dispatchEvent(new KeyboardEvent('$eventType', {key: '$safeKey', bubbles: true}))",
            null,
        )
    }
}

// WebView construction needs independent browser settings and host callbacks.
@Suppress("LongParameterList")
@SuppressLint("SetJavaScriptEnabled")
internal fun createBmlWebView(
    context: Context,
    messageSource: BmlMessageSource,
    postalCode: String,
    internetAccessEnabled: Boolean = false,
    acceptsKeyFocus: Boolean = true,
    lowMemoryMode: Boolean = false,
    onBmlInvisibleChanged: (Boolean) -> Unit,
    onBmlUsedKeyGroupsChanged: (Set<String>) -> Unit = {},
    onBmlVideoRectChanged: (left: Float, top: Float, width: Float, height: Float) -> Unit,
): BmlWebView =
    BmlWebView(
        context = context,
        messageSource = messageSource,
        postalCode = postalCode,
        internetAccessEnabled = internetAccessEnabled,
        acceptsKeyFocus = acceptsKeyFocus,
        lowMemoryMode = lowMemoryMode,
        onBmlInvisibleChanged = onBmlInvisibleChanged,
        onBmlUsedKeyGroupsChanged = onBmlUsedKeyGroupsChanged,
        onBmlVideoRectChanged = onBmlVideoRectChanged,
    )

private fun blockedNetworkResponse() = WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", emptyMap(), null)

/** Native HTTP transport avoids depending on broadcaster servers opting into browser CORS. */
@SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager")
private class BmlNetworkBridge(
    context: Context,
    private val enabled: Boolean,
    private val deliverResponse: (requestId: String, response: String) -> Unit,
) {
    private val connectivityManager =
        context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private val released = AtomicBoolean(false)
    private val active = AtomicBoolean(false)
    private val generation = AtomicInteger()
    private val executor = Executors.newFixedThreadPool(4)
    private val connections = mutableSetOf<HttpURLConnection>()

    fun start() {
        if (enabled && !released.get()) active.set(true)
    }

    fun stop() {
        active.set(false)
        generation.incrementAndGet()
        synchronized(connections) {
            connections.forEach(HttpURLConnection::disconnect)
            connections.clear()
        }
    }

    @JavascriptInterface
    fun isConnected(): Boolean {
        if (!enabled || !active.get()) return false
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    @JavascriptInterface
    fun request(
        requestId: String,
        method: String,
        uri: String,
        bodyBase64: String?,
    ) {
        if (released.get() || !active.get()) return
        val expectedGeneration = generation.get()
        try {
            executor.execute {
                val response = performRequest(method, uri, bodyBase64, expectedGeneration)
                if (isCurrent(expectedGeneration)) deliverResponse(requestId, response)
            }
        } catch (_: RejectedExecutionException) {
            // release() won the race with a JavaScript request.
        }
    }

    fun release() {
        if (released.compareAndSet(false, true)) {
            stop()
            executor.shutdownNow()
        }
    }

    private fun performRequest(
        method: String,
        uri: String,
        bodyBase64: String?,
        expectedGeneration: Int,
    ): String {
        if (!enabled || !isCurrent(expectedGeneration) || method !in setOf("GET", "POST")) {
            return errorResponse()
        }
        val url = runCatching { URL(uri) }.getOrNull() ?: return errorResponse()
        if (url.protocol != "http" && url.protocol != "https") return errorResponse()
        val requestBody =
            if (method == "POST") {
                Base64.decode(bodyBase64.orEmpty(), Base64.DEFAULT).also { body ->
                    if (body.size > MAX_POST_BYTES) return errorResponse()
                }
            } else {
                null
            }

        return runCatching {
            val connection =
                (url.openConnection() as HttpURLConnection).apply {
                    // Temporary compatibility measure for legacy broadcaster endpoints. Keep this scoped to
                    // BML traffic; never install these objects as HttpsURLConnection global defaults.
                    if (this is HttpsURLConnection) {
                        sslSocketFactory = insecureSslSocketFactory
                        hostnameVerifier = insecureHostnameVerifier
                    }
                    requestMethod = method
                    connectTimeout = 5_000
                    readTimeout = 8_000
                    instanceFollowRedirects = true
                    setRequestProperty("Accept", "*/*")
                    setRequestProperty("Accept-Language", "ja")
                    setRequestProperty("Pragma", "no-cache")
                    if (method == "POST") {
                        doOutput = true
                        setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                        setFixedLengthStreamingMode(checkNotNull(requestBody).size)
                    }
                }
            synchronized(connections) { connections.add(connection) }
            try {
                if (!isCurrent(expectedGeneration)) return errorResponse()
                requestBody?.let { body ->
                    connection.outputStream.use { it.write(body) }
                    if (!isCurrent(expectedGeneration)) return errorResponse()
                }
                val statusCode = connection.responseCode
                val stream = if (statusCode >= 400) connection.errorStream else connection.inputStream
                val response = stream?.use { it.readNBytes(MAX_RESPONSE_BYTES + 1) } ?: byteArrayOf()
                if (response.size > MAX_RESPONSE_BYTES) return errorResponse()
                val headers = JSONObject()
                connection.headerFields.forEach { (name, values) ->
                    if (name != null && values != null) headers.put(name, values.joinToString(", "))
                }
                JSONObject()
                    .put("statusCode", statusCode)
                    .put("headers", headers)
                    .put("response", Base64.encodeToString(response, Base64.NO_WRAP))
                    .toString()
            } finally {
                synchronized(connections) { connections.remove(connection) }
                connection.disconnect()
            }
        }.getOrElse { errorResponse() }
    }

    private fun errorResponse(): String = JSONObject().put("error", true).toString()

    private fun isCurrent(expectedGeneration: Int): Boolean =
        active.get() && !released.get() && generation.get() == expectedGeneration

    private companion object {
        const val MAX_POST_BYTES = 4 * 1024 + "Denbun=".length
        const val MAX_RESPONSE_BYTES = 10 * 1024 * 1024

        val insecureHostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
        val insecureSslSocketFactory =
            SSLContext.getInstance("TLS").run {
                init(
                    null,
                    arrayOf<TrustManager>(
                        object : X509TrustManager {
                            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

                            override fun checkClientTrusted(
                                chain: Array<X509Certificate>?,
                                authType: String?,
                            ) = Unit

                            override fun checkServerTrusted(
                                chain: Array<X509Certificate>?,
                                authType: String?,
                            ) = Unit
                        },
                    ),
                    SecureRandom(),
                )
                socketFactory
            }
    }
}
