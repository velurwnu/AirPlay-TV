package com.amusic.tv

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Message
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.webkit.WebViewClientCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject

/**
 * Thin shell around music.apple.com.
 *
 * The site does everything: Apple ID sign-in, data, DRM playback. We inject a
 * TV layer (assets/web: tv.css + tv.js) that restyles it flat and light,
 * adds a tab bar and D-pad navigation, and presses the site's own buttons.
 * The Apple ID login can be saved on the device (encrypted) and is filled into
 * Apple's sign-in form by fill.js. No developer token needed.
 */
class MainActivity : Activity() {

    private lateinit var root: FrameLayout
    private lateinit var web: WebView
    private var popup: WebView? = null
    private val bundle by lazy { buildBundle() }
    private val logins by lazy { LoginStore(this) }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY

        root = FrameLayout(this)
        web = TvWebView(this).apply {
            setBackgroundColor(Color.BLACK)
            isFocusable = true
            isFocusableInTouchMode = true
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
        }
        configure(web)
        root.addView(web, ViewGroup.LayoutParams(MATCH, MATCH))
        setContentView(root)

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(web, true)
        }

        // Inject the TV UI as early as possible on every music.apple.com page.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(web, bundle, setOf(SITE))
        }
        // Fills the saved login into Apple's sign-in frames (the sheet and the login form inside it).
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(web, asset("fill.js"), LOGIN_ORIGINS)
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            // From our tab bar: the keyboard for a focused sign-in field, the «Войти» button.
            WebViewCompat.addWebMessageListener(web, "tvHost", setOf(SITE)) { _, message, _, isMainFrame, _ ->
                val msg = runCatching { JSONObject(message.data ?: "") }.getOrNull() ?: return@addWebMessageListener
                if (msg.optBoolean("keyboard")) showKeyboard()
                if (msg.optBoolean("signin") && isMainFrame) {
                    if (logins.load() == null) showLoginDialog { openSignIn() } else openSignIn()
                }
            }
            // The login itself goes only to Apple's own sign-in origins.
            WebViewCompat.addWebMessageListener(web, "tvLogin", LOGIN_ORIGINS) { _, message, _, _, reply ->
                when (message.data) {
                    "has" -> reply.postMessage(JSONObject().put("has", logins.load() != null).toString())
                    "get" -> reply.postMessage(logins.load()?.let {
                        JSONObject().put("email", it.email).put("password", it.password)
                    }?.toString() ?: "{}")
                    "keyboard" -> showKeyboard()
                }
            }
        }
        web.webViewClient = SiteClient()
        web.webChromeClient = Chrome()
        if (savedInstanceState == null) web.loadUrl(START_URL) else web.restoreState(savedInstanceState)
        web.requestFocus()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configure(view: WebView) = with(view.settings) {
        javaScriptEnabled = true
        domStorageEnabled = true
        mediaPlaybackRequiresUserGesture = false
        javaScriptCanOpenWindowsAutomatically = true
        setSupportMultipleWindows(true)
        cacheMode = WebSettings.LOAD_DEFAULT
        allowFileAccess = false
        allowContentAccess = false
        // Desktop Chrome UA: the site serves the full web player instead of "get the app".
        userAgentString = DESKTOP_UA
    }

    private fun asset(name: String) = assets.open("web/$name").bufferedReader().use { it.readText() }

    /** Our web UI (CSS + JS from assets) as one script that boots once per document. */
    private fun buildBundle(): String {
        val css = JSONObject.quote(asset("tv.css"))
        val js = listOf("config.js", "tv.js").joinToString("\n;\n") { asset(it) }
        return """
            (function () {
              if (window.top !== window || window.__tvui) return;
              window.__tvui = true;
              var booted = false;
              function boot() {
                if (booted || !document.head || !document.body) return;
                booted = true;
                var st = document.createElement('style');
                st.textContent = $css;
                document.head.appendChild(st);
                $js
              }
              // Called again from onPageFinished in case none of the events below reached us.
              window.__tvboot = boot;
              document.addEventListener('DOMContentLoaded', boot);
              document.addEventListener('readystatechange', boot);
              window.addEventListener('load', boot);
              var poll = setInterval(function () { boot(); if (booted) clearInterval(poll); }, 250);
              boot();
            })();
        """.trimIndent()
    }

    private inner class SiteClient : WebViewClientCompat() {
        // Fallback for old WebViews without document-start scripts (the bundle guards against double boot).
        override fun onPageFinished(view: WebView, url: String) {
            if (Uri.parse(url).host == SITE_HOST)
                view.evaluateJavascript("$bundle\n;window.__tvboot && window.__tvboot();", null)
        }
    }

    private inner class Chrome : WebChromeClient() {
        // Widevine DRM for Apple Music streams.
        override fun onPermissionRequest(request: PermissionRequest) {
            val allowed = request.resources.filter { it == PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID }
            if (allowed.isNotEmpty()) request.grant(allowed.toTypedArray()) else request.deny()
        }

        // Apple ID sign-in opens in a new window.
        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
            closePopup()
            val child = TvWebView(this@MainActivity).apply {
                configure(this)
                setBackgroundColor(Color.WHITE)
                webViewClient = WebViewClientCompat()
                webChromeClient = object : WebChromeClient() {
                    override fun onCloseWindow(window: WebView) = closePopup()
                }
            }
            CookieManager.getInstance().setAcceptThirdPartyCookies(child, true)
            root.addView(child, ViewGroup.LayoutParams(MATCH, MATCH))
            child.requestFocus()
            popup = child
            (resultMsg.obj as WebView.WebViewTransport).webView = child
            resultMsg.sendToTarget()
            return true
        }
    }

    /** Opens the on-screen keyboard for the field the page has just focused. */
    private fun showKeyboard() {
        // No web.requestFocus() here: Chrome takes it as focusing the main frame and pulls the
        // focus out of the sign-in iframe. Explicit request (flags 0), so the TV keyboard takes
        // the remote just like after pressing OK on the field.
        web.postDelayed({
            if (web.hasFocus()) getSystemService(InputMethodManager::class.java).showSoftInput(web, 0)
        }, 200)
    }

    private fun openSignIn() = web.evaluateJavascript("window.tvOpenSignIn && window.tvOpenSignIn()", null)

    /**
     * Asks for the Apple ID e-mail and password once and keeps them encrypted on the device.
     * Shown by «Войти» when nothing is saved, and by the remote's Menu key to change or forget it.
     */
    private fun showLoginDialog(then: (() -> Unit)? = null) {
        val saved = logins.load()
        fun field(hint: String, type: Int, value: String?) = EditText(this).apply {
            this.hint = hint
            setSingleLine()   // before inputType: it would reset the password masking
            inputType = type
            imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_ACTION_NEXT
            setText(value ?: "")
        }
        val email = field("Почта Apple ID", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, saved?.email)
        val password = field("Пароль", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD, saved?.password)
        password.imeOptions = (password.imeOptions and EditorInfo.IME_MASK_ACTION.inv()) or EditorInfo.IME_ACTION_DONE
        val pad = (24 * resources.displayMetrics.density).toInt()
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(email)
            addView(password)
        }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Вход в Apple ID")
            .setMessage("Данные сохранятся только на этом устройстве в зашифрованном виде и будут подставляться при входе.")
            .setView(form)
            .setPositiveButton("Сохранить") { _, _ ->
                val e = email.text.toString().trim()
                if (e.isNotEmpty() && password.text.isNotEmpty()) logins.save(LoginStore.Login(e, password.text.toString()))
                web.evaluateJavascript("window.tvLoginChanged && window.tvLoginChanged()", null)
                then?.invoke()
            }
            .setNegativeButton("Отмена") { _, _ -> then?.invoke() }
            .apply {
                if (saved != null) setNeutralButton("Забыть") { _, _ ->
                    logins.clear()
                    web.evaluateJavascript("window.tvLoginChanged && window.tvLoginChanged()", null)
                }
            }
            .show()
        email.requestFocus()
    }

    /** WebView whose keyboard never switches to the full-screen landscape editor. */
    private class TvWebView(context: android.content.Context) : WebView(context) {
        override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
            val ic = super.onCreateInputConnection(outAttrs)
            outAttrs.imeOptions = outAttrs.imeOptions or
                EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            return ic
        }
    }

    private fun closePopup() {
        popup?.let {
            root.removeView(it)
            it.destroy()
        }
        popup = null
        web.requestFocus()
    }

    // Back: popup history -> close popup -> let the web UI handle it -> exit.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
            popup?.let {
                if (it.canGoBack()) it.goBack() else closePopup()
                return true
            }
            web.evaluateJavascript("window.tvBack ? window.tvBack() : false") { handled ->
                if (handled != "true") finish()
            }
            return true
        }
        if (event.keyCode == KeyEvent.KEYCODE_BACK) return true
        // Menu on the remote: change or forget the saved Apple ID login.
        if (event.keyCode == KeyEvent.KEYCODE_MENU) {
            if (event.action == KeyEvent.ACTION_UP) showLoginDialog()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onPause() {
        super.onPause()
        CookieManager.getInstance().flush()   // keep the Apple ID session
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    override fun onDestroy() {
        closePopup()
        web.destroy()
        super.onDestroy()
    }

    companion object {
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val SITE_HOST = "music.apple.com"
        private const val SITE = "https://$SITE_HOST"
        private const val START_URL = "$SITE/"
        private val LOGIN_ORIGINS = setOf(SITE, "https://idmsa.apple.com")
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/129.0.0.0 Safari/537.36"
    }
}
