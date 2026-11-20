package com.payasito.yasuobuild

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.Base64
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.WindowManager
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.max

/**
 * Burbuja flotante + captura de pantalla + ventanita con la build.
 * Solo captura cuando se toca la burbuja. No lee ni toca nada del juego.
 */
class CaptureService : Service() {

    companion object {
        const val START = "start"; const val STOP = "stop"
        const val EXTRA_CODE = "code"; const val EXTRA_DATA = "data"
        @Volatile var activo = false
    }

    private lateinit var wm: WindowManager
    private val main = Handler(Looper.getMainLooper())
    private val hilo = HandlerThread("captura").apply { start() }
    private val fondo = Handler(hilo.looper)

    private var proyeccion: MediaProjection? = null
    private var pantalla: VirtualDisplay? = null
    private var lector: ImageReader? = null
    private var ancho = 0; private var alto = 0

    private var ultimo: android.media.Image? = null   // solo se toca desde el hilo de fondo
    private var ocupado = false

    private var burbuja: ImageView? = null
    private var burbujaLp: WindowManager.LayoutParams? = null
    private var panel: View? = null
    private var panelLp: WindowManager.LayoutParams? = null
    private var web: WebView? = null
    private var webLista = false
    private var pendiente: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WindowManager::class.java)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            STOP -> { stopSelf(); return START_NOT_STICKY }
            START -> arrancar(intent)
        }
        return START_NOT_STICKY
    }

    private fun arrancar(intent: Intent) {
        startForeground(1, aviso(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        val code = intent.getIntExtra(EXTRA_CODE, 0)
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
                            else @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_DATA)
        if (data == null) { stopSelf(); return }
        if (proyeccion != null) { mostrarBurbuja(); return }
        val p = getSystemService(MediaProjectionManager::class.java).getMediaProjection(code, data)
        if (p == null) { stopSelf(); return }
        proyeccion = p
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { main.post { stopSelf() } }
        }, main)
        prepararPantalla()
        mostrarBurbuja()
        activo = true
    }

    /* ---------- Captura ---------- */

    private fun tamReal(): Point {
        val b = wm.currentWindowMetrics.bounds
        return Point(b.width(), b.height())
    }

    /** Crea (o ajusta si el celu giró) la pantalla virtual que copia lo que se ve. Se crea UNA sola vez por permiso. */
    private fun prepararPantalla() {
        val t = tamReal()
        if (pantalla != null && t.x == ancho && t.y == alto) return
        ancho = t.x; alto = t.y
        val dpi = resources.displayMetrics.densityDpi
        val viejo = lector
        val nuevo = ImageReader.newInstance(ancho, alto, PixelFormat.RGBA_8888, 2)
        // Guardo siempre el último cuadro (en el hilo de fondo). Al tocar la burbuja uso ese.
        nuevo.setOnImageAvailableListener({ r ->
            val img = try { r.acquireLatestImage() } catch (e: Exception) { null } ?: return@setOnImageAvailableListener
            ultimo?.close(); ultimo = img
        }, fondo)
        lector = nuevo
        val vd = pantalla
        if (vd == null) {
            pantalla = proyeccion?.createVirtualDisplay("yasuo", ancho, alto, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, nuevo.surface, null, null)
        } else {
            vd.resize(ancho, alto, dpi)
            vd.surface = nuevo.surface
        }
        fondo.post { ultimo?.close(); ultimo = null; viejo?.close() }
    }

    /** Convierte el último cuadro a JPEG chico en base64. Corre en el hilo de fondo. */
    private fun procesarUltimo() {
        val img = ultimo
        ultimo = null
        if (img == null) { main.post { fallo("No llegó la imagen. Tocá de nuevo.") }; return }
        try {
            val pl = img.planes[0]
            val w = img.width; val h = img.height
            val bmpAncho = pl.rowStride / pl.pixelStride
            val crudo = Bitmap.createBitmap(bmpAncho, h, Bitmap.Config.ARGB_8888)
            crudo.copyPixelsFromBuffer(pl.buffer)
            img.close()
            val recorte = if (bmpAncho != w) Bitmap.createBitmap(crudo, 0, 0, w, h) else crudo
            val k = minOf(1f, 1280f / max(w, h))
            val chica = if (k < 1f) Bitmap.createScaledBitmap(recorte, (w * k).toInt(), (h * k).toInt(), true) else recorte
            val out = ByteArrayOutputStream()
            chica.compress(Bitmap.CompressFormat.JPEG, 82, out)
            val b64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
            main.post { listo(b64) }
        } catch (e: Exception) {
            try { img.close() } catch (_: Exception) {}
            main.post { fallo("No pude procesar la captura.") }
        }
    }

    private fun capturar() {
        if (ocupado) return
        ocupado = true
        burbuja?.visibility = View.INVISIBLE
        ocultarPanel()
        // espero a que desaparezca la burbuja para que no salga en la foto
        main.postDelayed({
            try { prepararPantalla() } catch (e: Exception) { fallo("No pude preparar la captura."); return@postDelayed }
            // dejo pasar un instante para que llegue un cuadro sin la burbuja ni la ventanita
            fondo.postDelayed({ procesarUltimo() }, 250)
        }, 300)
    }

    private fun listo(b64: String) {
        ocupado = false
        burbuja?.visibility = View.VISIBLE
        mostrarPanel()
        val js = "window.escanearBase64 && window.escanearBase64('$b64')"
        if (webLista) web?.evaluateJavascript(js, null) else pendiente = js
    }

    private fun fallo(msg: String) {
        ocupado = false
        burbuja?.visibility = View.VISIBLE
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    /* ---------- Burbuja ---------- */

    @SuppressLint("ClickableViewAccessibility")
    private fun mostrarBurbuja() {
        if (burbuja != null) return
        val dp = resources.displayMetrics.density
        val tam = (54 * dp).toInt()
        val v = ImageView(this).apply {
            setImageResource(R.mipmap.ic_launcher)
            alpha = 0.9f
        }
        val lp = WindowManager.LayoutParams(tam, tam,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0; y = (120 * dp).toInt()
        }
        var x0 = 0; var y0 = 0; var tx = 0f; var ty = 0f; var movio = false
        v.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { x0 = lp.x; y0 = lp.y; tx = e.rawX; ty = e.rawY; movio = false; true }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - tx; val dy = e.rawY - ty
                    if (abs(dx) > 12 * dp || abs(dy) > 12 * dp) movio = true
                    if (movio) { lp.x = x0 + dx.toInt(); lp.y = y0 + dy.toInt(); wm.updateViewLayout(v, lp) }
                    true
                }
                MotionEvent.ACTION_UP -> { if (!movio) capturar(); true }
                else -> false
            }
        }
        wm.addView(v, lp)
        burbuja = v; burbujaLp = lp
    }

    /* ---------- Ventanita con la build ---------- */

    private val prefs by lazy { getSharedPreferences("ventanita", MODE_PRIVATE) }
    private val ALFAS = floatArrayOf(1f, 0.85f, 0.7f, 0.55f)
    private var tamIdx = 0; private var alfaIdx = 0

    @SuppressLint("ClickableViewAccessibility")
    private fun crearPanel() {
        val dp = resources.displayMetrics.density
        tamIdx = prefs.getInt("tam", 0); alfaIdx = prefs.getInt("alfa", 0)
        val caja = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF0B0F17.toInt())
        }
        val barra = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xFF131A26.toInt())
        }
        fun boton(txt: String, alTocar: () -> Unit) = TextView(this).apply {
            text = txt; textSize = 17f; setTextColor(0xFFE6ECF5.toInt())
            gravity = Gravity.CENTER
            setPadding((11 * dp).toInt(), (7 * dp).toInt(), (11 * dp).toInt(), (7 * dp).toInt())
            setOnClickListener { alTocar() }
        }
        // el título sirve para arrastrar la ventanita
        val titulo = TextView(this).apply {
            text = "⠿ Yasuo"; setTextColor(0xFF5AD1E6.toInt()); textSize = 14f
            setPadding((10 * dp).toInt(), (7 * dp).toInt(), 0, (7 * dp).toInt())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        var x0 = 0; var y0 = 0; var tx = 0f; var ty = 0f
        titulo.setOnTouchListener { _, e ->
            val lp = panelLp ?: return@setOnTouchListener false
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { x0 = lp.x; y0 = lp.y; tx = e.rawX; ty = e.rawY }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = x0 + (e.rawX - tx).toInt(); lp.y = y0 + (e.rawY - ty).toInt()
                    panel?.let { if (it.parent != null) wm.updateViewLayout(it, lp) }
                }
                MotionEvent.ACTION_UP -> prefs.edit().putInt("x", lp.x).putInt("y", lp.y).apply()
            }
            true
        }
        barra.addView(titulo)
        barra.addView(boton("−") { if (tamIdx > 0) { tamIdx--; guardarYAplicar() } })
        barra.addView(boton("+") { if (tamIdx < 2) { tamIdx++; guardarYAplicar() } })
        barra.addView(boton("◐") { alfaIdx = (alfaIdx + 1) % ALFAS.size; guardarYAplicar() })
        barra.addView(boton("✕") { ocultarPanel() })
        barra.addView(boton("⏻") {
            Toast.makeText(this, "Burbuja apagada", Toast.LENGTH_SHORT).show()
            stopSelf()
        })
        val w = Web.crear(this, panel = true) {
            webLista = true
            pendiente?.let { js -> web?.evaluateJavascript(js, null) }
            pendiente = null
        }
        caja.addView(barra)
        caja.addView(FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f)
            addView(w)
        })
        web = w
        panel = caja
        panelLp = WindowManager.LayoutParams(0, 0,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,   // los toques fuera de la ventanita van al juego
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt("x", -1); y = prefs.getInt("y", -1)
        }
    }

    private fun guardarYAplicar() {
        prefs.edit().putInt("tam", tamIdx).putInt("alfa", alfaIdx).apply()
        mostrarPanel()
    }

    private fun mostrarPanel() {
        if (panel == null) crearPanel()
        val t = tamReal()
        val lp = panelLp!!
        val apaisado = t.x > t.y
        // tres tamaños: chico (por defecto), mediano, grande
        val anchos = if (apaisado) floatArrayOf(0.32f, 0.42f, 0.58f) else floatArrayOf(0.72f, 0.86f, 0.96f)
        val altos = if (apaisado) floatArrayOf(0.70f, 0.85f, 0.95f) else floatArrayOf(0.45f, 0.62f, 0.85f)
        lp.width = (t.x * anchos[tamIdx]).toInt()
        lp.height = (t.y * altos[tamIdx]).toInt()
        lp.alpha = ALFAS[alfaIdx]
        // primera vez: arriba a la derecha. Después, donde la dejaste (sin salirse de la pantalla)
        if (lp.x < 0 || lp.y < 0) { lp.x = t.x - lp.width - (8 * resources.displayMetrics.density).toInt(); lp.y = (t.y - lp.height) / 2 }
        lp.x = lp.x.coerceIn(0, maxOf(0, t.x - lp.width))
        lp.y = lp.y.coerceIn(0, maxOf(0, t.y - lp.height))
        val v = panel!!
        if (v.parent == null) wm.addView(v, lp) else wm.updateViewLayout(v, lp)
        v.visibility = View.VISIBLE
    }

    private fun ocultarPanel() {
        panel?.let { if (it.parent != null) wm.removeView(it) }
    }

    /* ---------- Aviso fijo y cierre ---------- */

    private fun aviso(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("yasuo", "Burbuja de Yasuo Build", NotificationManager.IMPORTANCE_LOW))
        val apagar = PendingIntent.getService(this, 0,
            Intent(this, CaptureService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
        val abrir = PendingIntent.getActivity(this, 1,
            Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, "yasuo")
            .setContentTitle("Burbuja de Yasuo activa")
            .setContentText("Tocá la burbuja en la pantalla de carga.")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(abrir)
            .addAction(Notification.Action.Builder(null, "Apagar", apagar).build())
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        activo = false
        try { burbuja?.let { wm.removeView(it) } } catch (_: Exception) {}
        ocultarPanel()
        web?.destroy()
        pantalla?.release()
        fondo.post { ultimo?.close(); ultimo = null; lector?.close() }
        try { proyeccion?.stop() } catch (_: Exception) {}
        hilo.quitSafely()
        super.onDestroy()
    }
}
