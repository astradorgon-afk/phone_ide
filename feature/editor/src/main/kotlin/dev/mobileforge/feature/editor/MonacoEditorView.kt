package dev.mobileforge.feature.editor

import android.annotation.SuppressLint
import android.os.Build
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import dev.mobileforge.feature.editor.bridge.MonacoBridge

/**
 * Hosts Monaco in a WebView.
 *
 * Content is served through [WebViewAssetLoader] on https://appassets.androidplatform.net,
 * NOT from a file:// URL. That is a functional requirement, not a preference: a file:// origin
 * is opaque, which breaks the same-origin policy, XHR and worker construction that Monaco's
 * AMD loader and language services depend on. It is also the approach Android's own
 * documentation prescribes for local content (ADR-003).
 *
 * The WebView is locked down to match: no file access, no content-provider access, no
 * geolocation, no JS-opened windows, and a request interceptor that answers ONLY from bundled
 * assets — any URL the asset loader does not recognise is refused outright rather than fetched.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun MonacoEditorView(
    bridge: MonacoBridge,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val webViewStatus = remember { WebViewCompatibility.check(context) }

    // Checked BEFORE the WebView is created. Monaco on an old engine fails with a bare
    // SyntaxError in the console and a blank pane — the user is owed the actual reason.
    if (!webViewStatus.canRunEditor) {
        UnsupportedWebViewNotice(status = webViewStatus, modifier = modifier)
        return
    }

    DisposableEffect(bridge) {
        onDispose { bridge.detach() }
    }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            val assetLoader = WebViewAssetLoader.Builder()
                .setDomain(ASSET_DOMAIN)
                .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
                .build()

            WebView(context).apply {
                settings.apply {
                    // Monaco is a JavaScript application; this is unavoidable and is the
                    // reason the bridge surface is kept to a single validated method.
                    javaScriptEnabled = true

                    // Everything below is switched OFF deliberately. Monaco needs none of it,
                    // and each one is a way for hostile page content to reach beyond the view.
                    allowFileAccess = false
                    allowContentAccess = false
                    @Suppress("DEPRECATION")
                    allowFileAccessFromFileURLs = false
                    @Suppress("DEPRECATION")
                    allowUniversalAccessFromFileURLs = false
                    javaScriptCanOpenWindowsAutomatically = false
                    setGeolocationEnabled(false)
                    setSupportMultipleWindows(false)
                    // Web SQL is removed from API 35+, where this call is a deprecated no-op —
                    // but minSdk is 30, so it still disables a real database on older devices.
                    @Suppress("DEPRECATION")
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                        databaseEnabled = false
                    }
                    mediaPlaybackRequiresUserGesture = true

                    // Needed by Monaco's AMD loader and language service workers.
                    domStorageEnabled = true

                    // The editor manages its own layout; browser zoom fights the soft keyboard.
                    builtInZoomControls = false
                    displayZoomControls = false
                    setSupportZoom(false)
                    loadWithOverviewMode = false
                    useWideViewPort = false
                }

                // Soft-keyboard focus.
                //
                // A WebView hosted in an Activity takes Android input focus for itself on
                // touch, and that is what makes the soft keyboard appear when a page's text
                // field is tapped. Compose hosts this WebView inside an AndroidView, whose
                // own focus handling keeps the WebView unfocused: a tap reaches Monaco (its
                // hidden textarea takes DOM focus) but no Android view is ever the IME's
                // served view, so the keyboard never opens and tapping the editor looks like
                // it does nothing. Taking focus explicitly, at the moment of the touch,
                // restores the behaviour a plain Activity gets for free.
                isFocusable = true
                isFocusableInTouchMode = true
                setOnTouchListener { view, _ ->
                    if (!view.hasFocus()) view.requestFocus()
                    false
                }

                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView,
                        request: WebResourceRequest,
                    ): WebResourceResponse? = assetLoader.shouldInterceptRequest(request.url)

                    /**
                     * Refuses all navigation. The editor is a single page; a link click or a
                     * script-driven navigation inside untrusted content must never move this
                     * WebView somewhere else, least of all onto the network.
                     */
                    override fun shouldOverrideUrlLoading(
                        view: WebView,
                        request: WebResourceRequest,
                    ): Boolean = true

                    /**
                     * Page-load failures are as silent as JS failures without this: a missing
                     * asset or a blocked request would leave the same blank WebView. Only the
                     * main frame is reported — subresource failures belong to the console.
                     */
                    override fun onReceivedError(
                        view: WebView,
                        request: WebResourceRequest,
                        error: WebResourceError,
                    ) {
                        if (request.isForMainFrame) {
                            bridge.reportConsole(
                                "Editor page failed to load (${error.errorCode}): " +
                                    "${request.url}",
                                isError = true,
                            )
                        }
                    }
                }

                // Console capture: without this, a Monaco boot failure is a permanently blank
                // WebView — no fallback text, no banner, nothing in logcat. Observed on real
                // hardware as "I can't type anything". Errors surface as banner events;
                // everything lands in logcat for diagnosis.
                webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                        bridge.reportConsole(
                            message.message() ?: return true,
                            isError = message.messageLevel() == ConsoleMessage.MessageLevel.ERROR,
                        )
                        return true
                    }
                }

                addJavascriptInterface(bridge, JS_INTERFACE_NAME)
                bridge.attach(this)

                loadUrl("https://$ASSET_DOMAIN/assets/editor/index.html")
            }
        },
        onRelease = { webView ->
            bridge.detach()
            webView.removeJavascriptInterface(JS_INTERFACE_NAME)
            webView.destroy()
        },
    )
}

/**
 * Shown instead of a blank editor when the device's WebView cannot run Monaco.
 *
 * States the engine version found, the version needed, and the one action that fixes it.
 * This is the difference between "the app is broken" and "update Android System WebView".
 */
@Composable
private fun UnsupportedWebViewNotice(
    status: WebViewStatus,
    modifier: Modifier = Modifier,
) {
    val detail = when (status) {
        is WebViewStatus.TooOld ->
            "This device has Android System WebView ${status.versionName} " +
                "(Chromium ${status.majorVersion}). The editor needs Chromium " +
                "${WebViewCompatibility.MINIMUM_CHROMIUM_MAJOR} or newer."

        WebViewStatus.Missing ->
            "No Android System WebView provider is installed on this device, so the editor " +
                "has no engine to run in."

        else -> "The editor could not start."
    }

    val action = when (status) {
        is WebViewStatus.TooOld ->
            "Update \"Android System WebView\" from the Play Store, then reopen the file. " +
                "WebView updates separately from Android itself, so this is fixable without " +
                "a system update."

        WebViewStatus.Missing ->
            "Install \"Android System WebView\" from the Play Store."

        else -> null
    }

    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "The editor cannot run here",
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = detail,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        action?.let {
            Spacer(Modifier.height(12.dp))
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.height(16.dp))
        Text(
            text = "File browsing and everything else in the IDE still work.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** Reserved by Android for exactly this purpose; never a domain the user might own. */
private const val ASSET_DOMAIN = "appassets.androidplatform.net"

/** Must match the object name used in feature/editor/src/main/assets/editor/bridge.js. */
private const val JS_INTERFACE_NAME = "MobileForgeBridge"
