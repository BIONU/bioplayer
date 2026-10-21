package com.bio.bioplayer.ui

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.bio.bioplayer.databinding.ActivityPlayerBinding
import android.app.AlertDialog
import android.widget.Toast
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.HttpDataSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.TrackSelectionDialogBuilder
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import android.content.Intent
import androidx.browser.customtabs.CustomTabsIntent
import android.media.AudioManager
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.WindowManager
import kotlin.math.abs
import kotlin.math.roundToInt
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Fullscreen playback for hls (.m3u8), direct mp4 links, web/embed links,
 * and full support for advanced station fields (desktop, embed, referer, userAgent, headers).
 *
 * "host" stations (per w3u spec: url is a webpage, not a direct stream) are
 * resolved first via a hidden WebView that sniffs the first .m3u8/.mpd
 * request the page makes, then handed off to ExoPlayer. If nothing is
 * sniffed within HOST_RESOLVE_TIMEOUT_MS, falls back to showing the page
 * in a visible WebView so the user can interact with it directly.
 */
@UnstableApi
class PlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URL = "extra_url"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_USER_AGENT = "extra_user_agent"
        const val EXTRA_REFERER = "extra_referer"
        const val EXTRA_SOURCE_FORMAT = "extra_source_format"
        const val EXTRA_EMBED = "extra_embed"         // รองรับฟิลด์ embed: true/false
        const val EXTRA_DESKTOP = "extra_desktop"     // รองรับฟิลด์ desktop: true/false
        const val EXTRA_HEADERS = "extra_headers"     // รองรับฟิลด์ headers (Map<String, String>)
        const val EXTRA_IS_HOST = "extra_is_host"     // รองรับฟิลด์ host/isHost: true/false

        private const val HOST_RESOLVE_TIMEOUT_MS = 12_000L
        // เจอ .m3u8 ตัวแรกแล้วรออีกครู่ เผื่อมีตัวอื่นตามมา (ตัวแรกอาจไม่ใช่สตรีมจริง) แล้วเลือกตัวล่าสุด
        private const val HOST_SETTLE_MS = 2_000L
    }

    private lateinit var binding: ActivityPlayerBinding
    private var player: ExoPlayer? = null
    private var hostSniffer: WebView? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    // สถานะท่าทาง: 0 ยังไม่เริ่ม, -1 ไม่ใช่ท่าที่รองรับ, 1 แสง, 2 เสียง, 3 กรอ
    private var gMode = 0
    private var startBright = 0.5f
    private var startVol = 0
    private var seekStart = 0L
    private var seekTarget = 0L
    private var infoToast: Toast? = null

    private fun originOf(url: String): String? = try {
        val uri = java.net.URI(url)
        if (uri.scheme != null && uri.host != null) "${uri.scheme}://${uri.host}/" else null
    } catch (e: Exception) {
        null
    }

    @SuppressLint("SetTextI18n", "SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val rawFull = intent.getStringExtra(EXTRA_URL) ?: return finish()
        // รองรับ url|User-Agent=...&Referer=...&Origin=... (ค่าหลัง | ชนะค่าจากฟิลด์ของ station)
        val rawUrl = rawFull.substringBefore("|")
        val pipe = parsePipeParams(rawFull)
        val pipeUa = pipe.entries.firstOrNull { it.key.equals("user-agent", true) }?.value
        val pipeRef = pipe.entries.firstOrNull {
            it.key.equals("referer", true) || it.key.equals("referrer", true)
        }?.value
        val pipeHeaders = pipe.filterKeys {
            !it.equals("user-agent", true) && !it.equals("referer", true) && !it.equals("referrer", true)
        }
        val customUserAgent = pipeUa ?: intent.getStringExtra(EXTRA_USER_AGENT)
        val customReferer = pipeRef ?: intent.getStringExtra(EXTRA_REFERER)
        val isEmbed = intent.getBooleanExtra(EXTRA_EMBED, false)
        val isDesktop = intent.getBooleanExtra(EXTRA_DESKTOP, false)
        val isHost = intent.getBooleanExtra(EXTRA_IS_HOST, false)

        // รับค่า headers แบบ Map ถ้ามีส่งเข้ามา
        @Suppress("UNCHECKED_CAST")
        val extraHeaders = intent.getSerializableExtra(EXTRA_HEADERS) as? Map<String, String>
        val customHeaders: Map<String, String>? =
            ((extraHeaders ?: emptyMap()) + pipeHeaders).takeIf { it.isNotEmpty() }

        title = intent.getStringExtra(EXTRA_TITLE)

        // 1. จัดการเรื่อง User-Agent ตามลำดับความสำคัญ (custom -> desktop -> default)
        val defaultUserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36 Wiseplay"
        val userAgent = when {
            !customUserAgent.isNullOrBlank() -> customUserAgent
            isDesktop -> "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
            else -> defaultUserAgent
        }

        // 2. การจัดการลิงก์ YouTube แปลงให้เป็นแบบ embed เต็มจอเพื่อไม่ให้ติดแถบเว็บ/คอมเมนต์
        val sourceFormat = intent.getStringExtra(EXTRA_SOURCE_FORMAT)

        val youtubeVideoId = when {
            rawUrl.contains("youtube.com/watch?v=") -> {
                Uri.parse(rawUrl).getQueryParameter("v")
            }
            rawUrl.contains("youtu.be/") -> {
                rawUrl.substringAfter("youtu.be/").substringBefore("?")
            }
            rawUrl.contains("youtube.com/embed/") -> {
                rawUrl.substringAfter("youtube.com/embed/").substringBefore("?")
            }
            else -> null
        }

        val url = if (!youtubeVideoId.isNullOrBlank()) {
            // ใช้ลิงก์ embed พร้อมพารามิเตอร์เล่นอัตโนมัติและแสดงผลแบบเต็มจอ
            "https://www.youtube.com/embed/$youtubeVideoId?autoplay=1&playsinline=1&controls=1"
        } else {
            rawUrl
        }

        // ลำดับความสำคัญ Referer: custom -> YouTube Default -> โดเมนที่รู้จัก
        val autoReferer = when {
            !customReferer.isNullOrBlank() -> customReferer
            url.contains("youtube", ignoreCase = true) -> "https://dlive.sx/" // บังคับใส่ Referer สำหรับ YouTube อัตโนมัติที่นี่
            url.contains("streamhls1688") || url.contains("workers.dev") -> "https://playlist.streamhls1688.com/"
            else -> null
        }

        when {
            // ================= โหมด Host Resolving =================
            isHost -> {
                startHostFlow(url, userAgent, autoReferer, customHeaders)
            }

            // ================= โหมด WebView (embed / เว็บ / ยูทูป) =================
            isEmbed ||
                url.contains(".html") ||
                url.contains("dlive.sx") ||
                url.contains("youtube", ignoreCase = true) -> {
                val webReferer = autoReferer
                    ?: if (url.contains("youtube", ignoreCase = true)) "https://dlive.sx/" else null
                playWithWebView(url, userAgent, webReferer, customHeaders)
            }

            // ================= .php: ตรวจก่อนว่าตอบกลับเป็นเพลย์ลิสต์ HLS หรือเป็นหน้าเว็บ =================
            url.contains(".php") -> {
                val headers = exoHeaders(url, autoReferer, customHeaders)
                lifecycleScope.launch {
                    val hlsUrl = withContext(Dispatchers.IO) { probeHls(url, userAgent, headers) }
                    if (hlsUrl != null) {
                        playWithExoPlayer(hlsUrl, userAgent, headers)
                    } else {
                        // เป็นหน้าเว็บ (เช่น hd11.php) -> ดักลิงก์ .m3u8 ที่หน้านั้นเรียก ไม่เจอค่อยเปิดเป็นหน้าเว็บ
                        startHostFlow(url, userAgent, autoReferer, customHeaders)
                    }
                }
            }

            // ================= โหมด ExoPlayer ตรงๆ =================
            else -> playWithExoPlayer(url, userAgent, exoHeaders(url, autoReferer, customHeaders))
        }
    }

    /**
     * โหลด pageUrl ใน WebView ที่ซ่อนไว้ (ไม่แสดงผล) แล้วดักจับ request แรก
     * ที่เป็นสตรีม (.m3u8/.mpd) ที่หน้านั้นยิงออกมา. เจอก็เรียก onResolved,
     * ไม่เจอภายใน timeout ก็เรียก onFailed แทน
     */
    @SuppressLint("SetJavaScriptEnabled")
    private fun resolveHostedStream(
        pageUrl: String,
        userAgent: String,
        referer: String?,
        headers: Map<String, String>?,
        onResolved: (String, Map<String, String>) -> Unit,
        onFailed: () -> Unit
    ) {
        val sniffer = WebView(this)
        hostSniffer = sniffer
        (binding.root as ViewGroup).addView(sniffer, ViewGroup.LayoutParams(1, 1))
        sniffer.visibility = View.INVISIBLE

        var resolved = false

        sniffer.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            userAgentString = userAgent
        }
        android.webkit.CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(sniffer, true)
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
            WebSettingsCompat.setRequestedWithHeaderOriginAllowList(sniffer.settings, emptySet())
        }

        fun cleanup() {
            (binding.root as ViewGroup).removeView(sniffer)
            sniffer.destroy()
            hostSniffer = null
        }

        var pendingUrl: String? = null
        var pendingHeaders: Map<String, String>? = null
        var settleScheduled = false

        val timeoutRunnable = Runnable {
            if (!resolved && !settleScheduled) {
                resolved = true
                cleanup()
                onFailed()
            }
        }
        mainHandler.postDelayed(timeoutRunnable, HOST_RESOLVE_TIMEOUT_MS)

        sniffer.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                val reqUrl = request.url.toString()
            if (!resolved && (reqUrl.contains(".m3u8", true) || reqUrl.contains(".mpd", true))) {
                val resolvedHeaders = mutableMapOf<String, String>()
                headers?.let { resolvedHeaders.putAll(it) }
                if (!resolvedHeaders.containsKey("Referer")) {
                    // เลียนแบบ browser จริง: Referer ที่กำหนดมาก่อน ถ้าไม่มีใช้ค่าที่หน้าเว็บส่งตอนขอไฟล์นี้จริง
                    // ถ้ายังไม่มี ใช้ URL เต็มของหน้านั้น
                    resolvedHeaders["Referer"] =
                        referer?.takeIf { it.isNotBlank() } ?: request.requestHeaders["Referer"] ?: pageUrl
                }
                if (!resolvedHeaders.containsKey("Origin")) {
                    request.requestHeaders["Origin"]?.let { resolvedHeaders["Origin"] = it }
                }
                mainHandler.post {
                    if (resolved) return@post
                    pendingUrl = reqUrl
                    pendingHeaders = resolvedHeaders
                    if (!settleScheduled) {
                        settleScheduled = true
                        mainHandler.removeCallbacks(timeoutRunnable)
                        mainHandler.postDelayed({
                            if (!resolved) {
                                resolved = true
                                cleanup()
                                onResolved(pendingUrl!!, pendingHeaders!!)
                            }
                        }, HOST_SETTLE_MS)
                    }
                }
            }
                return super.shouldInterceptRequest(view, request)
            }
        }

        val loadHeaders = mutableMapOf<String, String>()
        headers?.let { loadHeaders.putAll(it) }
        if (!referer.isNullOrBlank()) loadHeaders["Referer"] = referer
        sniffer.loadUrl(pageUrl, loadHeaders)
    }

    // แยกส่วนหลัง | ของ url เป็นคู่ key=value (คั่นด้วย &)
    private fun parsePipeParams(full: String): Map<String, String> {
        val query = full.substringAfter("|", "")
        if (query.isBlank()) return emptyMap()
        val out = linkedMapOf<String, String>()
        query.split("&").forEach { kv ->
            val i = kv.indexOf("=")
            if (i > 0) out[kv.substring(0, i).trim()] = Uri.decode(kv.substring(i + 1))
        }
        return out
    }

    private fun exoHeaders(
        url: String,
        autoReferer: String?,
        customHeaders: Map<String, String>?
    ): Map<String, String> {
        val headers = mutableMapOf<String, String>()
        customHeaders?.let { headers.putAll(it) }
        if (!autoReferer.isNullOrBlank() && !headers.containsKey("Referer")) {
            headers["Referer"] = autoReferer
        }
        if ((url.contains("streamhls1688") || url.contains("workers.dev")) && !headers.containsKey("Origin")) {
            headers["Origin"] = "https://playlist.streamhls1688.com"
        }
        return headers
    }

    // host: url เป็นหน้าเว็บ ไม่ใช่สตรีมตรง -> โหลดในเว็บที่ซ่อนไว้เพื่อดักลิงก์ .m3u8/.mpd แล้วส่งให้ ExoPlayer
    // ถ้าหาไม่เจอภายในเวลาที่กำหนด เปิดหน้าเว็บให้ดูเองแทน
    private fun startHostFlow(
        url: String,
        userAgent: String,
        referer: String?,
        headers: Map<String, String>?
    ) {
        Toast.makeText(this, "กำลังหาลิงก์สตรีม...", Toast.LENGTH_SHORT).show()
        resolveHostedStream(
            pageUrl = url,
            userAgent = userAgent,
            referer = referer,
            headers = headers,
            onResolved = { streamUrl, h -> playWithExoPlayer(streamUrl, userAgent, h) },
            onFailed = {
                Toast.makeText(this, "หาสตรีมอัตโนมัติไม่เจอ เปิดหน้าเว็บแทน", Toast.LENGTH_SHORT).show()
                playWithWebView(url, userAgent, referer, headers)
            }
        )
    }

    // ตรวจ URL ที่ไม่มีนามสกุลชัดเจน (เช่น .php) ว่าตอบกลับเป็นเพลย์ลิสต์ HLS หรือไม่
    // เป็น HLS -> คืน URL สุดท้ายหลังตาม redirect / ไม่ใช่หรือเชื่อมต่อไม่ได้ -> คืน null
    // ตาม redirect เอง เพราะ HttpURLConnection ไม่ตามข้าม http <-> https ให้
    private fun probeHls(startUrl: String, userAgent: String, headers: Map<String, String>): String? {
        var current = startUrl
        try {
            for (i in 0 until 5) {
                val conn = java.net.URL(current).openConnection() as java.net.HttpURLConnection
                try {
                    conn.instanceFollowRedirects = false
                    conn.connectTimeout = 8000
                    conn.readTimeout = 10000
                    conn.setRequestProperty("User-Agent", userAgent)
                    headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }

                    val code = conn.responseCode
                    if (code in 300..399) {
                        val loc = conn.getHeaderField("Location") ?: return null
                        current = java.net.URL(java.net.URL(current), loc).toString()
                        continue
                    }

                    val type = conn.contentType.orEmpty()
                    val buf = ByteArray(512)
                    val n = conn.inputStream.use { it.read(buf) }
                    val head = if (n > 0) String(buf, 0, n) else ""
                    val isHls = type.contains("mpegurl", ignoreCase = true) ||
                        head.trimStart('﻿').trimStart().startsWith("#EXTM3U")
                    return if (isHls) current else null
                } finally {
                    conn.disconnect()
                }
            }
        } catch (e: Exception) {
            return null
        }
        return null
    }

    private fun playWithWebView(
        url: String,
        userAgent: String,
        referer: String?,
        customHeaders: Map<String, String>?
    ) {
        binding.playerView.visibility = View.GONE
        binding.webView.visibility = View.VISIBLE

        val cookieManager = android.webkit.CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(binding.webView, true)

        binding.webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            userAgentString = userAgent
            loadsImagesAutomatically = true
            useWideViewPort = true
            loadWithOverviewMode = true
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
            // allow-list ว่าง = ไม่แนบ X-Requested-With ให้ทุกโดเมน (เลียนแบบเบราว์เซอร์จริง)
            WebSettingsCompat.setRequestedWithHeaderOriginAllowList(binding.webView.settings, emptySet())
        }

        binding.webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: android.webkit.WebResourceRequest
            ): Boolean {
                // บล็อกกระโดดข้ามโดเมนเฉพาะตอนที่หน้าเว็บหลัก (top-level) จะเปลี่ยนไปทั้งหน้า
                // ปล่อยผ่าน iframe ที่ซ้อนอยู่ข้างในเสมอ (เช่น host_wrapper.php -> hd3.php -> dynproclaim.net)
                if (!request.isForMainFrame) return false

                val reqHost = request.url.host ?: return true
                val pageHost = Uri.parse(url).host ?: return false
                val sameSite = reqHost == pageHost || reqHost.endsWith(".$pageHost")
                return !sameSite
            }

            override fun onReceivedHttpError(
                view: WebView,
                request: android.webkit.WebResourceRequest,
                errorResponse: android.webkit.WebResourceResponse
            ) {
                super.onReceivedHttpError(view, request, errorResponse)
                val reqHost = request.url.host ?: ""
                val pageHost = Uri.parse(url).host ?: ""
                val isRelevant = errorResponse.statusCode == 403 &&
                    (reqHost == pageHost || request.url.toString().contains(".m3u8", true) || request.url.toString().contains(".php", true))
                if (isRelevant) {
                    runOnUiThread {
                        Toast.makeText(this@PlayerActivity, "403: ${request.url}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
        binding.webView.webChromeClient = WebChromeClient()

        val finalHeaders = mutableMapOf<String, String>()
        if (!referer.isNullOrBlank()) finalHeaders["Referer"] = referer
        customHeaders?.let { finalHeaders.putAll(it) }

        if (finalHeaders.isNotEmpty()) {
            binding.webView.loadUrl(url, finalHeaders)
        } else {
            binding.webView.loadUrl(url)
        }
    }

    private fun playWithExoPlayer(
        url: String,
        userAgent: String,
        headers: Map<String, String>
    ) {
        binding.webView.visibility = View.GONE
        binding.playerView.visibility = View.VISIBLE

        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(userAgent)
            .setAllowCrossProtocolRedirects(true)
            .apply {
                if (headers.isNotEmpty()) setDefaultRequestProperties(headers)
            }

        val mediaSourceFactory = DefaultMediaSourceFactory(httpDataSourceFactory)

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
            .also { exo ->
                binding.playerView.player = exo
                setupGestures(exo)

                // เล่นไม่ได้ให้บอกสาเหตุ (เช่น HTTP 403) จะได้ไม่เหลือแค่จอดำ
                exo.addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        val http = (error.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode
                        val reason = if (http != null) "HTTP $http" else error.errorCodeName
                        Toast.makeText(this@PlayerActivity, "เล่นไม่ได้: $reason", Toast.LENGTH_LONG).show()
                    }
                })

                binding.playerView.findViewById<View>(androidx.media3.ui.R.id.exo_settings)
                    ?.setOnClickListener { showSettingsMenu(exo) }

                val mimeType = when {
                    url.contains(".m3u8", ignoreCase = true) -> MimeTypes.APPLICATION_M3U8
                    url.contains(".mpd", ignoreCase = true) -> MimeTypes.APPLICATION_MPD
                    url.contains(".mp4", ignoreCase = true) ||
                        url.contains(".mkv", ignoreCase = true) ||
                        url.contains(".ts", ignoreCase = true) -> null
                    else -> MimeTypes.APPLICATION_M3U8
                }

                val mediaItem = MediaItem.Builder()
                    .setUri(url)
                    .apply { mimeType?.let { setMimeType(it) } }
                    .build()

                exo.setMediaItem(mediaItem)
                exo.playWhenReady = true
                exo.prepare()
            }
    }

    // ===== ท่าทางสัมผัส (เฉพาะโหมด ExoPlayer) =====
    // ปัดขึ้น/ลงครึ่งซ้าย = แสง, ครึ่งขวา = เสียง, ปัดซ้าย/ขวาที่ส่วนล่างของจอ = กรอ (ปล่อยนิ้วแล้วค่อยกรอ)
    @SuppressLint("ClickableViewAccessibility")
    private fun setupGestures(exo: ExoPlayer) {
        val audio = getSystemService(AUDIO_SERVICE) as AudioManager
        val maxVol = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                if (e1 == null) return false
                val w = binding.playerView.width
                val h = binding.playerView.height
                if (w == 0 || h == 0) return false
                val tx = e2.x - e1.x
                val ty = e2.y - e1.y

                if (gMode == 0) {
                    gMode = when {
                        abs(tx) > abs(ty) && e1.y > h * 0.7f -> 3
                        abs(ty) > abs(tx) -> if (e1.x < w / 2) 1 else 2
                        else -> -1
                    }
                    startBright = window.attributes.screenBrightness.let { if (it < 0f) 0.5f else it }
                    startVol = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                    seekStart = exo.currentPosition
                }

                when (gMode) {
                    1 -> {
                        val b = (startBright - ty / h).coerceIn(0.01f, 1f)
                        // สร้าง LayoutParams ใหม่แล้วค่อยตั้ง เพื่อให้ระบบเห็นว่าค่าเปลี่ยนจริง
                        val lp = WindowManager.LayoutParams()
                        lp.copyFrom(window.attributes)
                        lp.screenBrightness = b
                        window.attributes = lp
                        showInfo("แสง ${(b * 100).roundToInt()}%")
                    }
                    2 -> {
                        val v = (startVol - ty / h * maxVol).roundToInt().coerceIn(0, maxVol)
                        audio.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0)
                        showInfo("เสียง ${if (maxVol > 0) v * 100 / maxVol else 0}%")
                    }
                    3 -> {
                        val max = if (exo.duration > 0) exo.duration else Long.MAX_VALUE
                        seekTarget = (seekStart + (tx * 100).toLong()).coerceIn(0L, max)
                        showInfo("%d:%02d".format(seekTarget / 60000, seekTarget / 1000 % 60))
                    }
                    else -> return false
                }
                return true
            }
        })

        binding.playerView.setOnTouchListener { _, ev ->
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) gMode = 0
            detector.onTouchEvent(ev)
            if (ev.actionMasked == MotionEvent.ACTION_UP && gMode == 3) exo.seekTo(seekTarget)
            // ถ้าเป็นท่าทางของเรา กินอีเวนต์ไว้ ไม่งั้นปล่อยให้ PlayerView จัดการ (แตะเพื่อโชว์/ซ่อนแถบควบคุม)
            gMode > 0
        }
    }

    // ใช้ Toast ตัวเดียว กดซ้ำแล้วแทนที่ ไม่ให้ต่อคิวสะสม
    private fun showInfo(text: String) {
        infoToast?.cancel()
        infoToast = Toast.makeText(this, text, Toast.LENGTH_SHORT).also { it.show() }
    }

    private fun showSettingsMenu(exo: ExoPlayer) {
        val items = arrayOf("ความละเอียด", "เสียง", "คำบรรยาย", "ความเร็ว", "สัดส่วนภาพ", "ข้อมูลสตรีม")
        AlertDialog.Builder(this)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showTrackDialog("ความละเอียด", exo, C.TRACK_TYPE_VIDEO)
                    1 -> showTrackDialog("เสียง", exo, C.TRACK_TYPE_AUDIO)
                    2 -> showTrackDialog("คำบรรยาย", exo, C.TRACK_TYPE_TEXT)
                    3 -> showSpeedDialog(exo)
                    4 -> showResizeDialog()
                    5 -> showStreamInfo(exo)
                }
            }
            .show()
    }

    private fun showTrackDialog(title: String, exo: ExoPlayer, trackType: Int) {
        if (exo.currentTracks.groups.none { it.type == trackType }) {
            Toast.makeText(this, "สตรีมนี้ไม่มีตัวเลือก$title", Toast.LENGTH_SHORT).show()
            return
        }
        TrackSelectionDialogBuilder(this, title, exo, trackType)
            .setAllowAdaptiveSelections(true)
            .setShowDisableOption(trackType == C.TRACK_TYPE_TEXT)
            .build()
            .show()
    }

    private fun showSpeedDialog(exo: ExoPlayer) {
        val speeds = floatArrayOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
        val labels = speeds.map { if (it == 1f) "ปกติ" else "${it}x" }.toTypedArray()
        AlertDialog.Builder(this)
            .setSingleChoiceItems(labels, speeds.indexOfFirst { it == exo.playbackParameters.speed }) { d, i ->
                exo.setPlaybackSpeed(speeds[i])
                d.dismiss()
            }
            .show()
    }

    private fun showResizeDialog() {
        val modes = intArrayOf(
            AspectRatioFrameLayout.RESIZE_MODE_FIT,
            AspectRatioFrameLayout.RESIZE_MODE_FILL,
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        )
        val labels = arrayOf("พอดีจอ", "ยืดเต็มจอ", "ขยายเต็ม (ตัดขอบ)")
        AlertDialog.Builder(this)
            .setSingleChoiceItems(labels, modes.indexOf(binding.playerView.resizeMode)) { d, i ->
                binding.playerView.resizeMode = modes[i]
                d.dismiss()
            }
            .show()
    }

    private fun showStreamInfo(exo: ExoPlayer) {
        val v = exo.videoFormat
        val a = exo.audioFormat
        val info = buildString {
            append("วิดีโอ: ")
            if (v == null) append("-") else {
                append("${v.width}x${v.height}")
                if (v.bitrate > 0) append(" • ${v.bitrate / 1000} kbps")
                v.codecs?.let { append(" • $it") }
            }
            append("\nเสียง: ")
            if (a == null) append("-") else {
                if (a.channelCount > 0) append("${a.channelCount} ch")
                if (a.bitrate > 0) append(" • ${a.bitrate / 1000} kbps")
                a.codecs?.let { append(" • $it") }
            }
        }
        AlertDialog.Builder(this).setTitle("ข้อมูลสตรีม").setMessage(info).setPositiveButton(android.R.string.ok, null).show()
    }


    private fun openInExternalBrowser(url: String): Boolean {
        return try {
            val customTabsIntent = CustomTabsIntent.Builder()
                .setShowTitle(false)
                .build()
            customTabsIntent.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            customTabsIntent.launchUrl(this, Uri.parse(url))
            finish()
            true
        } catch (e: Exception) {
            false
        }
    }

    override fun onStop() {
        super.onStop()
        player?.release()
        player = null
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        hostSniffer?.let {
            (binding.root as? ViewGroup)?.removeView(it)
            it.destroy()
        }
        if (binding.webView.visibility == View.VISIBLE) {
            binding.webView.loadUrl("about:blank")
            binding.webView.destroy()
        }
        super.onDestroy()
    }
}
