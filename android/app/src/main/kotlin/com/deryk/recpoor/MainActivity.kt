package com.deryk.recpoor

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.media.projection.MediaProjectionConfig
import android.os.Build
import androidx.annotation.NonNull
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.os.Bundle

class MainActivity: FlutterActivity() {
    private val CHANNEL = "com.deryk.recpoor/screen_record"
    private var methodChannel: MethodChannel? = null
    private val REQUEST_MEDIA_PROJECTION = 1001

    private lateinit var mediaProjectionManager: MediaProjectionManager
    private var pendingBitrate = 8000000 // Default 8Mbps

    private val recordingStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.deryk.recpoor.ACTION_RECORDING_STOPPED") {
                methodChannel?.invokeMethod("recordingStopped", null)
            } else if (intent?.action == "com.deryk.recpoor.ACTION_RECORDING_STARTED") {
                methodChannel?.invokeMethod("recordingStarted", null)
            }
        }
    }

    private var pendingNavigation: String? = null

    override fun configureFlutterEngine(@NonNull flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        methodChannel = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL)
        methodChannel?.setMethodCallHandler { call, result ->
            if (call.method == "startRecording") {
                val bitrate = call.argument<Int>("bitrate")
                if (bitrate != null) {
                    pendingBitrate = bitrate
                    // Save to prefs for Tile/Transparent activity
                    getSharedPreferences("com.deryk.recpoor.prefs", Context.MODE_PRIVATE)
                        .edit()
                        .putInt("selected_bitrate", bitrate)
                        .apply()
                }
                startRecording()
                result.success(null)
            } else if (call.method == "stopRecording") {
                stopRecording()
                result.success(null)
            } else if (call.method == "getRecordingStatus") {
                val prefs = getSharedPreferences("com.deryk.recpoor.prefs", Context.MODE_PRIVATE)
                val isRecording = prefs.getBoolean("is_recording", false)
                result.success(isRecording)
            } else if (call.method == "openFileManager") {
                val path = call.argument<String>("path")
                if (path != null) {
                    openFileManager(path)
                    result.success(null)
                } else {
                    result.error("INVALID_PATH", "Path cannot be null", null)
                }
            } else if (call.method == "getVideoMeta") {
                val path = call.argument<String>("path")
                if (path != null) {
                    val meta = getVideoMetadata(path)
                    result.success(meta)
                } else {
                    result.error("INVALID_PATH", "Path cannot be null", null)
                }
            } else if (call.method == "getPendingNavigation") {
                result.success(pendingNavigation)
                pendingNavigation = null
            } else {
                result.notImplemented()
            }
        }
        
        checkIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        checkIntent(intent)
    }

    private fun checkIntent(intent: Intent?) {
        val nav = intent?.getStringExtra("navigate_to")
        if (nav == "recordings") {
            pendingNavigation = nav
            methodChannel?.invokeMethod("navigateToRecordings", null)
        }
    }

    private fun getVideoMetadata(path: String): Map<String, Any?> {
        val retriever = android.media.MediaMetadataRetriever()
        val result = HashMap<String, Any?>()
        try {
            val file = java.io.File(path)
            if (!file.exists()) return result
            
            retriever.setDataSource(path)
            
            // FPS
            var fps: Double? = null
            // METADATA_KEY_CAPTURE_FRAMERATE = 25
            val captureFps = retriever.extractMetadata(25) 
            if (captureFps != null) {
                fps = captureFps.toDoubleOrNull()
            }
            
            if (fps == null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                 val frameCount = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)
                 val duration = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                 if (frameCount != null && duration != null) {
                     val count = frameCount.toDoubleOrNull()
                     val ms = duration.toDoubleOrNull()
                     if (count != null && ms != null && ms > 0) {
                         fps = (count * 1000.0) / ms
                     }
                 }
            }
            result["fps"] = fps

            // Resolution
            val width = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
            val height = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
            
            if (width != null) result["width"] = width.toIntOrNull()
            if (height != null) result["height"] = height.toIntOrNull()

        } catch (e: Exception) {
            // Ignore
        } finally {
            try {
                retriever.release()
            } catch (e: Exception) {}
        }
        return result
    }

    private fun openFileManager(path: String) {
        try {
            val file = java.io.File(path)
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this,
                "${applicationContext.packageName}.fileprovider",
                file
            )

            val intent = Intent(Intent.ACTION_VIEW)
            intent.setDataAndType(uri, "resource/folder")
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            intent.setPackage("com.google.android.documentsui")
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            
            try {
                startActivity(intent)
            } catch (e: Exception) {
                // Try without specific package if specific fails (or maybe it is com.android.documentsui)
                intent.setPackage(null)
                startActivity(intent)
            }
        } catch (e: Exception) {
            // Fallback to strict Downloads
             try {
                val fallback = Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS)
                fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(fallback)
            } catch (e2: Exception) {
                // Ignore
            }
        }
    }



    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        
        val filter = IntentFilter().apply {
            addAction("com.deryk.recpoor.ACTION_RECORDING_STOPPED")
            addAction("com.deryk.recpoor.ACTION_RECORDING_STARTED")
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(recordingStatusReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(recordingStatusReceiver, filter)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(recordingStatusReceiver)
        } catch (e: Exception) {
            // Ignore
        }
    }

    override fun onResume() {
        super.onResume()
    }

    private fun startRecording() {
        val intent = mediaProjectionManager.createScreenCaptureIntent()
        startActivityForResult(intent, REQUEST_MEDIA_PROJECTION)
    }

    private fun stopRecording() {
        val intent = Intent(this, ScreenRecordService::class.java)
        stopService(intent)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_MEDIA_PROJECTION) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                val serviceIntent = Intent(this, ScreenRecordService::class.java).apply {
                    putExtra("resultCode", resultCode)
                    putExtra("data", data)
                    putExtra("bitrate", pendingBitrate)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }
                methodChannel?.invokeMethod("recordingStarted", null)
            }
        }
    }

    private val recordingStoppedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.deryk.recpoor.ACTION_RECORDING_STOPPED") {
                methodChannel?.invokeMethod("recordingStopped", null)
            }
        }
    }


}
