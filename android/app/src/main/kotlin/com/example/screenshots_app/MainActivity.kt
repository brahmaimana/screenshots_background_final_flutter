package com.example.screenshots_app

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.MediaScannerConnection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import io.flutter.embedding.android.FlutterFragmentActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import java.io.File
import java.io.FileOutputStream
import java.sql.Date
import java.text.SimpleDateFormat
import java.util.Locale

class MainActivity : FlutterFragmentActivity() {

    private lateinit var methodChannelResult: MethodChannel.Result
    private lateinit var mediaProjectionManager: MediaProjectionManager
    private lateinit var mediaProjection: MediaProjection
    private lateinit var virtualDisplay: VirtualDisplay
    private lateinit var imageReader: ImageReader
    private lateinit var resolver: ContentResolver
    private lateinit var contentValues: ContentValues
    private var mUri: Uri? = null
    private lateinit var handler: Handler
    private lateinit var handlerThread: HandlerThread

    private val REQUIRED_PERMISSIONS = mutableListOf(
        android.Manifest.permission.RECORD_AUDIO
    ).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }.apply {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            add(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }.toTypedArray()

    private var resultLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val data: Intent? = result.data
                startCapture(data)
            }
        }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { isGranted ->
        if (isGranted.containsValue(false)) {
            Toast.makeText(this, "Permission not granted", Toast.LENGTH_LONG).show()
        } else {
            val permissionIntent = mediaProjectionManager.createScreenCaptureIntent()
            resultLauncher.launch(permissionIntent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "location",
                "Location",
                NotificationManager.IMPORTANCE_HIGH
            )
            val notificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }

        mediaProjectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        handlerThread = HandlerThread("ScreenCapture")
        handlerThread.start()
        handler = Handler(handlerThread.looper)
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            "recordPlatform"
        ).setMethodCallHandler { call, result ->
            methodChannelResult = result
            when (call.method) {
                "start" -> requestPermissionLauncher.launch(REQUIRED_PERMISSIONS)
                "stop" -> stopCapture()
                else -> result.notImplemented()
            }
        }
    }

    private fun startCapture(data: Intent?) {
        data?.let {
            mediaProjection = mediaProjectionManager.getMediaProjection(Activity.RESULT_OK, it)

            imageReader = ImageReader.newInstance(
                getScreenWidth(),
                getScreenHeight(),
                PixelFormat.RGBA_8888,
                2
            )

            virtualDisplay = mediaProjection.createVirtualDisplay(
                "ScreenCapture",
                getScreenWidth(),
                getScreenHeight(),
                resources.displayMetrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.surface,
                null,
                handler
            )

            handler.post(captureRunnable)
        }
    }

    private val captureRunnable = object : Runnable {
        override fun run() {
            val image = imageReader.acquireLatestImage()
            image?.let {
                val planes = it.planes
                val buffer = planes[0].buffer
                val pixelStride = planes[0].pixelStride
                val rowStride = planes[0].rowStride
                val rowPadding = rowStride - pixelStride * getScreenWidth()

                val bitmap = Bitmap.createBitmap(                    
                    getScreenWidth() + rowPadding / pixelStride,
                    getScreenHeight(),
                    Bitmap.Config.ARGB_8888
                )
                bitmap.copyPixelsFromBuffer(buffer)
                it.close()

                saveBitmap(bitmap)
            }

            handler.postDelayed(this, 10000) // Capture every 3 seconds
        }
    }

    private fun saveBitmap(bitmap: Bitmap) {
        val filename = generateFileName()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            resolver = contentResolver
            contentValues = ContentValues().apply {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/" + "ScreenCapture")
                put(MediaStore.Images.Media.TITLE, filename)
                put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
                put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis())
            }
            mUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            mUri?.let { uri ->
                resolver.openOutputStream(uri)?.use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
            }
        } else {
            val directory = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                "ScreenCapture"
            )
            if (!directory.exists()) {
                directory.mkdirs()
            }
            val file = File(directory, "$filename.png")
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            MediaScannerConnection.scanFile(
                this,
                arrayOf(file.absolutePath),
                null,
                null
            )
        }
    }

    private fun generateFileName(): String {
        val formatter = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.getDefault())
        val curDate = Date(System.currentTimeMillis())
        return formatter.format(curDate).replace(" ", "")
    }

    private fun getScreenWidth(): Int {
        val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val display = windowManager.defaultDisplay
        val size = android.graphics.Point()
        display.getSize(size)
        return size.x
    }

    private fun getScreenHeight(): Int {
        val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val display = windowManager.defaultDisplay
        val size = android.graphics.Point()
        display.getSize(size)
        return size.y
    }

    private fun stopCapture() {
        handler.removeCallbacks(captureRunnable)
        virtualDisplay.release()
        mediaProjection.stop()
        handlerThread.quitSafely()
    }
}
