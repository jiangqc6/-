package com.jqc.webplayer.browser

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.jqc.webplayer.core.MediaCandidate
import com.jqc.webplayer.core.MediaCollector
import com.jqc.webplayer.core.MediaUrlClassifier
import com.jqc.webplayer.core.SniffSource
import com.jqc.webplayer.data.AppSettings
import com.jqc.webplayer.data.WatchSessionEntity
import org.json.JSONObject

@SuppressLint("SetJavaScriptEnabled")
class BrowserController(
    private val activity: Activity,
    private val settings: AppSettings,
    private val collector: MediaCollector,
    private val listener: Listener,
) {
    interface Listener {
        fun onCandidates(candidates: List<MediaCandidate>)
        fun onTitleChanged(title: String)
        fun onUrlChanged(url: String)
        fun onPageError(message: String?)
        fun onSslError(url: String)
        fun onRenderProcessGone()
        fun onPageFinished()
        fun onMessage(message: String)
        fun onPlaybackProgress(videoKey: String, positionMs: Long, durationMs: Long) {}
        fun onResumeApplied(videoKey: String, positionMs: Long) {}
        fun onPlaybackStateChanged(playing: Boolean) {}
        fun onVideoBuffering(videoKey: String, src: String) {}
        fun onVideoError(videoKey: String, src: String) {}
    }

    val webView: WebView = WebView(activity)

    private var desktopUa: Boolean = settings.desktopUa
    private var blockPopups: Boolean = settings.blockPopups
    private var fullscreenView: View? = null
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null
    private var fullscreenContainer: FrameLayout? = null
    private var previousOrientation: Int = activity.requestedOrientation
    private var destroyed = false

    private val prefs by lazy {
        activity.getSharedPreferences("web_player", Context.MODE_PRIVATE)
    }

    private val snifferScript: String by lazy {
        activity.assets.open("sniffer.js").bufferedReader().use { it.readText() }
    }

    private val gesturesScript: String by lazy {
        activity.assets.open("gestures.js").bufferedReader().use { it.readText() }
    }

    init {
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            javaScriptCanOpenWindowsAutomatically = !settings.blockPopups
            setSupportMultipleWindows(true)
            mediaPlaybackRequiresUserGesture = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = false
            displayZoomControls = false
        }
        applyUserAgent()

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val scheme = request.url.scheme?.lowercase()
                if (scheme == "http" || scheme == "https") return false
                listener.onMessage("已拦截外部链接：$scheme")
                return true
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val url = request.url.toString()
                if (MediaUrlClassifier.detectType(url) != null) {
                    val list = collector.offer(url, SniffSource.REQUEST)
                    activity.runOnUiThread { listener.onCandidates(list) }
                }
                return null
            }

            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                collector.startPage(url)
                injectSniffer()
                listener.onUrlChanged(url)
                listener.onCandidates(collector.snapshot())
                listener.onPageError(null)
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                super.doUpdateVisitedHistory(view, url, isReload)
                collector.updatePage(url)
                listener.onUrlChanged(url)
                listener.onCandidates(collector.snapshot())
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                injectSniffer()
                listener.onTitleChanged(view.title.orEmpty())
                listener.onPageFinished()
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                super.onReceivedError(view, request, error)
                if (request.isForMainFrame) {
                    listener.onPageError("加载失败：${error.description}。请检查网络后重试。")
                }
            }

            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: android.net.http.SslError) {
                handler.cancel()
                listener.onSslError(error.url.orEmpty())
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                listener.onRenderProcessGone()
                return true
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onReceivedTitle(view: WebView, title: String?) {
                listener.onTitleChanged(title.orEmpty())
            }

            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (view == null || callback == null) return
                enterFullscreen(view, callback)
            }

            override fun onHideCustomView() {
                exitFullscreen()
            }

            override fun onCreateWindow(
                view: WebView,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: android.os.Message,
            ): Boolean {
                // 用户主动点出来的新窗口（target=_blank、window.open 的播放按钮）
                // 必须接住：直接丢弃会导致“点了播放没反应”。页面自己弹的广告
                // 窗口没有用户手势，开启拦截时仍然不处理。
                if (blockPopups && !isUserGesture) return false
                val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
                // 不真的弹窗：用一个临时 WebView 接住目标地址，再交给当前页加载。
                val popup = WebView(activity).apply {
                    settings.javaScriptEnabled = true
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            popupView: WebView,
                            request: WebResourceRequest,
                        ): Boolean {
                            val target = request.url.toString()
                            if (target.startsWith("http://") || target.startsWith("https://")) {
                                webView.loadUrl(target)
                            }
                            popupView.post { popupView.destroy() }
                            return true
                        }
                    }
                }
                transport.webView = popup
                resultMsg.sendToTarget()
                return true
            }

            override fun onPermissionRequest(request: PermissionRequest) {
                request.deny()
            }

            override fun onGeolocationPermissionsShowPrompt(
                origin: String,
                callback: GeolocationPermissions.Callback,
            ) {
                callback.invoke(origin, false, false)
            }
        }

        webView.addJavascriptInterface(Bridge(), "JqcBridge")
        webView.setDownloadListener { _, _, _, _, _ ->
            listener.onMessage("第一版不支持下载")
        }
    }

    fun load(url: String) {
        webView.loadUrl(url)
    }

    fun reload() {
        webView.reload()
    }

    fun pause() {
        // 先把网页播放进度上报掉，再停页面音视频：切后台/进播放器都可能很久不回来。
        flushPlayback()
        // 先停掉页面自身的音视频，再暂停 WebView，避免后台继续拉流抢占播放器带宽。
        webView.evaluateJavascript(PAUSE_MEDIA_SCRIPT, null)
        webView.onPause()
    }

    /** 请求网页脚本把视频跳回上次的位置；实际生效时通过 onResumeApplied 回调。 */
    fun applyResume(session: WatchSessionEntity) {
        if (session.videoKey.isBlank()) return
        val key = JSONObject.quote(session.videoKey)
        val position = session.positionMs / 1000.0
        val duration = session.durationMs / 1000.0
        webView.evaluateJavascript(
            "window.__jqcApplyResume && window.__jqcApplyResume($key, $position, $duration)",
            null,
        )
    }

    /** 用户选择从头播放：取消自动续播并把当前视频拉回 0。 */
    fun seekWebToStart(videoKey: String) {
        if (videoKey.isBlank()) return
        webView.evaluateJavascript(
            "window.__jqcSeekStart && window.__jqcSeekStart(${JSONObject.quote(videoKey)})",
            null,
        )
    }

    fun flushPlayback(onDone: (() -> Unit)? = null) {
        webView.evaluateJavascript(
            "window.__jqcFlushProgress && window.__jqcFlushProgress()",
            { onDone?.invoke() },
        )
    }

    /** 影院模式：把正在播放（或面积最大）的网页视频滚到视口中央，配合清屏使用。 */
    fun enterTheater() {
        webView.evaluateJavascript(
            "window.__jqcTheater && window.__jqcTheater()",
            null,
        )
    }

    fun resume() {
        webView.onResume()
    }

    fun goBack() {
        if (webView.canGoBack()) webView.goBack()
    }

    fun canGoBack(): Boolean = webView.canGoBack()

    fun currentUrl(): String = webView.url.orEmpty()

    fun userAgent(): String = webView.settings.userAgentString ?: mobileUserAgent()

    fun setDesktopUa(enabled: Boolean) {
        if (desktopUa == enabled) return
        desktopUa = enabled
        applyUserAgent()
        webView.reload()
    }

    fun setBlockPopups(enabled: Boolean) {
        if (blockPopups == enabled) return
        blockPopups = enabled
        webView.settings.javaScriptCanOpenWindowsAutomatically = !enabled
    }

    fun destroy() {
        if (destroyed) return
        destroyed = true
        if (fullscreenContainer != null) {
            removeFullscreenInternal()
        }
        var tornDown = false
        val teardown = {
            if (!tornDown) {
                tornDown = true
                (webView.parent as? ViewGroup)?.removeView(webView)
                // 延迟到进度上报之后才收尾，此时 Activity 可能已经在销毁，
                // 任何一步失败都不能漏掉最后的 destroy()。
                runCatching {
                    webView.removeJavascriptInterface("JqcBridge")
                    webView.stopLoading()
                    webView.loadUrl("about:blank")
                    webView.clearHistory()
                    webView.removeAllViews()
                }
                runCatching { webView.destroy() }
            }
        }
        // 离开浏览器页（返回首页/重建）时视频可能刚播放到一半：先把当前进度
        // 通过 __jqcFlushProgress 上报，等脚本执行完再销毁。evaluateJavascript
        // 是异步的，立刻 destroy() 会把最后一次上报吞掉。
        runCatching { flushPlayback(teardown) }
        // 页面崩溃或 JS 无响应时回调不会来，兜底强制收尾，避免 WebView 泄漏。
        webView.postDelayed(teardown, FLUSH_TIMEOUT_MS)
    }

    private fun injectSniffer() {
        webView.evaluateJavascript(snifferScript, null)
        webView.evaluateJavascript(gesturesScript, null)
    }

    private fun mobileUserAgent(): String =
        WebSettings.getDefaultUserAgent(activity).replace("; wv", "")

    private fun applyUserAgent() {
        webView.settings.userAgentString = if (desktopUa) DESKTOP_UA else mobileUserAgent()
    }

    private fun dp(value: Int): Int =
        (value * activity.resources.displayMetrics.density).toInt()

    private fun fullscreenButton(label: String, onClick: () -> Unit): TextView = TextView(activity).apply {
        text = label
        maxLines = 1
        setTextColor(Color.WHITE)
        textSize = 13f
        gravity = Gravity.CENTER
        setPadding(dp(14), dp(8), dp(14), dp(8))
        background = GradientDrawable().apply {
            setColor(0xA6000000.toInt())
            cornerRadius = dp(20).toFloat()
        }
        elevation = dp(4).toFloat()
        isClickable = true
        setOnClickListener { onClick() }
        // 横屏时刘海可能正好在左上/右上角，把按钮往里让开。
        ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
            val cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            (v.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
                lp.topMargin = dp(14) + cutout.top
                if (lp.gravity and Gravity.END == Gravity.END) {
                    lp.rightMargin = dp(14) + cutout.right
                } else {
                    lp.leftMargin = dp(14) + cutout.left
                }
                v.layoutParams = lp
            }
            insets
        }
    }

    private fun fullscreenCoach(): TextView = TextView(activity).apply {
        text = "左右滑动快进/快退 · 长按 2 倍速 · 左上角退出"
        maxLines = 1
        setTextColor(Color.WHITE)
        textSize = 13f
        gravity = Gravity.CENTER
        setPadding(dp(16), dp(10), dp(16), dp(10))
        background = GradientDrawable().apply {
            setColor(0xCC000000.toInt())
            cornerRadius = dp(12).toFloat()
        }
        elevation = dp(4).toFloat()
    }

    private fun showFullscreenCoachOnce(container: FrameLayout) {
        if (prefs.getBoolean(KEY_FULLSCREEN_TIP_SHOWN, false)) return
        prefs.edit().putBoolean(KEY_FULLSCREEN_TIP_SHOWN, true).apply()
        val tip = fullscreenCoach()
        container.addView(
            tip,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL,
            ).apply { topMargin = dp(64) },
        )
        tip.alpha = 0f
        tip.animate().alpha(1f).setDuration(200).start()
        tip.postDelayed({
            tip.animate().alpha(0f).setDuration(400).withEndAction {
                (tip.parent as? ViewGroup)?.removeView(tip)
            }.start()
        }, 3000)
        vibrateShort()
    }

    private fun vibrateShort() {
        try {
            val vibrator = activity.getSystemService(Vibrator::class.java) ?: return
            if (!vibrator.hasVibrator()) return
            vibrator.vibrate(
                VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE)
            )
        } catch (e: Exception) {
            // 没有马达或系统限制震动时忽略，不影响播放。
        }
    }

    /**
     * 按视频本身宽高选方向，竖屏视频不再被强行横过来。
     * 脚本找不到视频尺寸（如跨域播放器）时保持用户当前方向。
     */
    private fun applyVideoOrientation() {
        webView.evaluateJavascript(VIDEO_ASPECT_SCRIPT) { result ->
            if (fullscreenContainer == null) return@evaluateJavascript
            val ratio = result?.trim()?.trim('"')?.toFloatOrNull() ?: return@evaluateJavascript
            if (ratio <= 0f) return@evaluateJavascript
            activity.requestedOrientation = if (ratio < 0.85f) {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            } else {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            }
        }
    }

    /** 全屏内手动旋转：横竖互切，并保持锁定，退出全屏后恢复进入前的方向。 */
    private fun toggleFullscreenOrientation() {
        activity.requestedOrientation =
            if (activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            } else {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            }
    }

    private fun enterFullscreen(view: View, callback: WebChromeClient.CustomViewCallback) {
        if (fullscreenView != null) {
            callback.onCustomViewHidden()
            return
        }
        fullscreenView = view
        fullscreenCallback = callback

        val container = FrameLayout(activity).apply {
            setBackgroundColor(Color.BLACK)
            addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            // 系统栏在全屏时是隐藏的，部分设备/站点又不会给退出入口。
            // 常驻一个轻量的退出按钮，保证任何网页全屏时都退得出来。
            addView(
                fullscreenButton("× 退出全屏") { exitFullscreen() },
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP or Gravity.START,
                ).apply {
                    leftMargin = dp(14)
                    topMargin = dp(14)
                },
            )
            addView(
                fullscreenButton("⟳ 旋转") { toggleFullscreenOrientation() },
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP or Gravity.END,
                ).apply {
                    rightMargin = dp(14)
                    topMargin = dp(14)
                },
            )
        }
        fullscreenContainer = container
        (activity.window.decorView as ViewGroup).addView(
            container,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )

        previousOrientation = activity.requestedOrientation
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.getInsetsController(activity.window, activity.window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        // 全屏后由注入脚本接管长按 2 倍速与左右滑动快进/快退。
        webView.evaluateJavascript(gesturesScript, null)
        webView.evaluateJavascript(
            "window.__jqcGestures && window.__jqcGestures.setAppFullscreen(true)",
            null,
        )
        applyVideoOrientation()
        showFullscreenCoachOnce(container)
    }

    fun isFullscreen(): Boolean = fullscreenContainer != null

    fun exitFullscreen() {
        if (fullscreenContainer == null) return
        val callback = fullscreenCallback
        removeFullscreenInternal()
        // 通知 WebView 页面退出全屏状态；若 WebView 同步回调 onHideCustomView，
        // 此时 fullscreenContainer 已为 null，会直接返回，不会重复移除。
        callback?.onCustomViewHidden()
    }

    private fun removeFullscreenInternal() {
        webView.evaluateJavascript(
            "window.__jqcGestures && window.__jqcGestures.setAppFullscreen(false)",
            null,
        )
        fullscreenContainer?.let {
            (activity.window.decorView as ViewGroup).removeView(it)
        }
        fullscreenContainer = null
        fullscreenView = null
        fullscreenCallback = null
        activity.requestedOrientation = previousOrientation
        activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.getInsetsController(activity.window, activity.window.decorView)
            .show(WindowInsetsCompat.Type.systemBars())
    }

    private inner class Bridge {
        @JavascriptInterface
        fun reportMedia(url: String, hint: String) {
            if (url.length > 4096) return
            if (MediaUrlClassifier.detectType(url) == null) return
            val list = collector.offer(url, SniffSource.JS)
            activity.runOnUiThread { listener.onCandidates(list) }
        }

        @JavascriptInterface
        fun reportPlayback(key: String, positionSec: Double, durationSec: Double) {
            if (key.isBlank() || key.length > 4096) return
            if (!positionSec.isFinite() || !durationSec.isFinite()) return
            if (positionSec < 0 || durationSec <= 0) return
            val positionMs = (positionSec * 1000).toLong()
            val durationMs = (durationSec * 1000).toLong()
            activity.runOnUiThread { listener.onPlaybackProgress(key, positionMs, durationMs) }
        }

        @JavascriptInterface
        fun notifyResumeApplied(key: String, positionSec: Double, durationSec: Double) {
            if (key.isBlank() || key.length > 4096) return
            if (!positionSec.isFinite()) return
            val positionMs = (positionSec * 1000).toLong().coerceAtLeast(0)
            activity.runOnUiThread { listener.onResumeApplied(key, positionMs) }
        }

        @JavascriptInterface
        fun notifyPlaybackState(playing: Boolean) {
            activity.runOnUiThread { listener.onPlaybackStateChanged(playing) }
        }

        @JavascriptInterface
        fun notifyVideoBuffering(key: String, src: String) {
            if (key.isBlank()) return
            activity.runOnUiThread { listener.onVideoBuffering(key, src.take(4096)) }
        }

        @JavascriptInterface
        fun notifyVideoError(key: String, src: String) {
            if (key.isBlank()) return
            activity.runOnUiThread { listener.onVideoError(key, src.take(4096)) }
        }
    }

    companion object {
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

        private const val KEY_FULLSCREEN_TIP_SHOWN = "fullscreen_tip_shown"

        /** 销毁前等待最后一次进度上报的上限；超时则直接收尾。 */
        private const val FLUSH_TIMEOUT_MS = 300L

        // 递归同源 iframe 找显示面积最大的视频，返回宽高比；跨域或还没尺寸时返回 0。
        private const val VIDEO_ASPECT_SCRIPT =
            "(function(){" +
                "function all(w,out,d){if(d>4)return out;" +
                "try{var vs=w.document.querySelectorAll('video');for(var i=0;i<vs.length;i++)out.push(vs[i]);}catch(e){}" +
                "try{var fs=w.frames;for(var j=0;j<fs.length;j++){try{all(fs[j],out,d+1);}catch(e){}}}catch(e){}" +
                "return out;}" +
                "var vs=all(window,[],0),best=null,area=0;" +
                "for(var i=0;i<vs.length;i++){var v=vs[i];var r=v.getBoundingClientRect();" +
                "var a=(r.width||0)*(r.height||0);" +
                "if(a>=area&&v.videoWidth>0&&v.videoHeight>0){area=a;best=v;}}" +
                "return best?(best.videoWidth/best.videoHeight):0;" +
                "})()"

        private const val PAUSE_MEDIA_SCRIPT =
            "(function(){var media=document.querySelectorAll('video,audio');" +
                "for(var i=0;i<media.length;i++){try{media[i].pause()}catch(e){}}})()"
    }
}
