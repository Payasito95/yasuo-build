package com.payasito.yasuobuild

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient

/** La app web de siempre (build, plan, clave de Gemini). Si se actualiza en GitHub, la app también. */
object Web {
    const val URL = "https://payasito95.github.io/yasuo-build/"

    @SuppressLint("SetJavaScriptEnabled")
    fun crear(ctx: Context, panel: Boolean = false, alCargar: (() -> Unit)? = null): WebView = WebView(ctx).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.userAgentString = settings.userAgentString + " YasuoApp"
        setBackgroundColor(0xFF0B0F17.toInt())
        webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) { alCargar?.invoke() }
        }
        loadUrl(if (panel) "$URL?panel=1" else URL)
    }
}
