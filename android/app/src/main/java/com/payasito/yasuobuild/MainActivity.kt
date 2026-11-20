package com.payasito.yasuobuild

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/** Pantalla principal: activar/apagar la burbuja y, abajo, la app web completa (para cargar la clave, ver la build, etc.). */
class MainActivity : Activity() {

    private lateinit var web: WebView
    private lateinit var estado: TextView
    private lateinit var btn: Button
    private var subida: ValueCallback<Array<Uri>>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dp = resources.displayMetrics.density
        val raiz = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF0B0F17.toInt())
        }
        val barra = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((12 * dp).toInt(), (10 * dp).toInt(), (12 * dp).toInt(), (10 * dp).toInt())
            setBackgroundColor(0xFF131A26.toInt())
        }
        estado = TextView(this).apply {
            setTextColor(0xFFE6ECF5.toInt()); textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        }
        btn = Button(this).apply { setOnClickListener { alternar() } }
        barra.addView(estado); barra.addView(btn)

        web = Web.crear(this).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f)
            webChromeClient = object : WebChromeClient() {
                override fun onShowFileChooser(w: WebView, cb: ValueCallback<Array<Uri>>, p: FileChooserParams): Boolean {
                    subida?.onReceiveValue(null); subida = cb
                    return try { startActivityForResult(p.createIntent(), REQ_ARCHIVO); true }
                    catch (e: Exception) { subida = null; false }
                }
            }
        }
        raiz.addView(barra); raiz.addView(web)
        setContentView(raiz)

        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
    }

    override fun onResume() { super.onResume(); pintar() }

    private fun pintar() {
        if (CaptureService.activo) {
            estado.text = "Burbuja activa. En la pantalla de carga tocá la burbuja de Yasuo."
            btn.text = "Apagar"
        } else {
            estado.text = "Activá la burbuja para escanear la pantalla de carga con un toque."
            btn.text = "Activar burbuja"
        }
    }

    private fun alternar() {
        if (CaptureService.activo) {
            startService(Intent(this, CaptureService::class.java).setAction(CaptureService.STOP))
            estado.postDelayed({ pintar() }, 300)
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Permití \"Mostrar sobre otras apps\" para Yasuo Build y volvé.", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        Toast.makeText(this, "Elegí \"Pantalla completa\" y tocá Empezar.", Toast.LENGTH_LONG).show()
        val mpm = getSystemService(MediaProjectionManager::class.java)
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURA)
    }

    @Deprecated("Activity clásica")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_ARCHIVO -> {
                subida?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data)); subida = null
            }
            REQ_CAPTURA -> {
                if (resultCode != RESULT_OK || data == null) {
                    Toast.makeText(this, "Sin permiso de captura no puedo leer la pantalla.", Toast.LENGTH_LONG).show()
                    return
                }
                val i = Intent(this, CaptureService::class.java)
                    .setAction(CaptureService.START)
                    .putExtra(CaptureService.EXTRA_CODE, resultCode)
                    .putExtra(CaptureService.EXTRA_DATA, data)
                startForegroundService(i)
                Toast.makeText(this, "Listo. Abrí Wild Rift y tocá la burbuja en la pantalla de carga.", Toast.LENGTH_LONG).show()
                estado.postDelayed({ pintar() }, 400)
            }
        }
    }

    @Deprecated("Activity clásica")
    override fun onBackPressed() { if (web.canGoBack()) web.goBack() else super.onBackPressed() }

    companion object { const val REQ_CAPTURA = 10; const val REQ_ARCHIVO = 11 }
}
