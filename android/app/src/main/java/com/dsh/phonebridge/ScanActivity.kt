package com.dsh.phonebridge

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Camera
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 扫电脑设置页上的配对二维码。
 *
 * 只用系统自带的 Camera1 API + zxing core 解帧，不引 CameraX / ML Kit，
 * 免得 APK 变大、也免得在国内机型上依赖 GMS。扫到的 payload 形如
 * `dsh-phone://pair?v=1&c=<8位码>&p=<端口>[&w=<ws地址>|&h=<主机>]`，
 * 解析后以 Intent extra 交回 SettingsActivity 填表。
 */
class ScanActivity : AppCompatActivity(), SurfaceHolder.Callback, Camera.PreviewCallback {

    private lateinit var preview: SurfaceView
    private lateinit var hint: TextView
    private var camera: Camera? = null
    private var previewWidth = 0
    private var previewHeight = 0

    private val reader = MultiFormatReader()
    private val busy = AtomicBoolean(false)
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var handled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_scan)
        preview = findViewById(R.id.scanSurface)
        hint = findViewById(R.id.scanHint)
        preview.holder.addCallback(this)

        reader.setHints(
            mapOf(
                DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                DecodeHintType.TRY_HARDER to true,
            ),
        )

        findViewById<Button>(R.id.cancelScanButton).setOnClickListener { finish() }
    }

    override fun onResume() {
        super.onResume()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            hint.text = "没有摄像头权限：请回到设置页「权限与安全 → 摄像头」打开"
        }
    }

    override fun onPause() {
        releaseCamera()
        super.onPause()
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    // ------------------------------------------------------------- camera glue
    override fun surfaceCreated(holder: SurfaceHolder) = openCamera(holder)

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

    override fun surfaceDestroyed(holder: SurfaceHolder) = releaseCamera()

    private fun openCamera(holder: SurfaceHolder) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        releaseCamera()
        try {
            val cam = Camera.open() ?: run {
                hint.text = "没有可用的摄像头"
                return
            }
            val params = cam.parameters
            val sizes = params.supportedPreviewSizes
            // 720p 左右足够解二维码，帧率还稳；找不到就用最小的一档
            val size = sizes.firstOrNull { it.width * it.height <= 1280 * 720 } ?: sizes.firstOrNull()
            if (size != null) {
                params.setPreviewSize(size.width, size.height)
                previewWidth = size.width
                previewHeight = size.height
            }
            if (params.supportedFocusModes?.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE) == true) {
                params.focusMode = Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE
            }
            cam.parameters = params
            cam.setPreviewDisplay(holder)
            cam.setPreviewCallback(this)
            cam.startPreview()
            camera = cam
            hint.text = "把二维码放进取景框"
        } catch (error: Exception) {
            hint.text = "打开摄像头失败：${error.message}"
        }
    }

    private fun releaseCamera() {
        val cam = camera ?: return
        camera = null
        try {
            cam.setPreviewCallback(null)
            cam.stopPreview()
        } catch (_: Throwable) {
        }
        cam.release()
    }

    // ------------------------------------------------------------- frame decode
    override fun onPreviewFrame(data: ByteArray?, cam: Camera?) {
        if (data == null || handled) return
        if (!busy.compareAndSet(false, true)) return
        val width = previewWidth
        val height = previewHeight
        if (width <= 0 || height <= 0) {
            busy.set(false)
            return
        }
        val frame = data.copyOf()
        worker.execute {
            try {
                val source = PlanarYUVLuminanceSource(frame, width, height, 0, 0, width, height, false)
                val bitmap = BinaryBitmap(HybridBinarizer(source))
                val result = reader.decodeWithState(bitmap)
                val text = result?.text
                if (!text.isNullOrBlank()) onDecoded(text)
            } catch (_: Throwable) {
                // 没扫到不是错误，下一帧继续
            } finally {
                busy.set(false)
            }
        }
    }

    private fun onDecoded(text: String) {
        if (handled) return
        val parsed = parsePairing(text)
        main.post {
            if (handled) return@post
            if (parsed == null) {
                hint.text = "扫到的不是手机桥配对码：$text"
                return@post
            }
            handled = true
            setResult(
                RESULT_OK,
                Intent()
                    .putExtra(EXTRA_CODE, parsed.code)
                    .putExtra(EXTRA_PORT, parsed.port)
                    .putExtra(EXTRA_WS, parsed.ws)
                    .putExtra(EXTRA_HOST, parsed.host),
            )
            finish()
        }
    }

    private data class Pairing(
        val code: String,
        val port: String,
        val ws: String,
        val host: String,
    )

    private fun parsePairing(text: String): Pairing? {
        val uri = runCatching { Uri.parse(text) }.getOrNull() ?: return null
        if (uri.scheme != "dsh-phone" || uri.host != "pair") return null
        val code = uri.getQueryParameter("c")?.trim().orEmpty()
        if (!code.matches(Regex("\\d{8}"))) return null
        val port = uri.getQueryParameter("p")?.trim().orEmpty()
        val ws = uri.getQueryParameter("w")?.trim().orEmpty()
        val host = uri.getQueryParameter("h")?.trim().orEmpty()
        return Pairing(code, port, ws, host)
    }

    companion object {
        const val EXTRA_CODE = "code"
        const val EXTRA_PORT = "port"
        const val EXTRA_WS = "ws"
        const val EXTRA_HOST = "host"
    }
}
