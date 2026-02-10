package com.deryk.recpoor

import android.annotation.SuppressLint
import android.app.Service
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.view.Surface
import androidx.annotation.RequiresApi
import java.nio.ByteBuffer

class ScreenRecordService : Service() {
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    
    // Video encoding
    private var videoEncoder: MediaCodec? = null
    private var inputSurface: Surface? = null
    
    // Audio recording (internal audio)
    private var audioRecord: AudioRecord? = null
    private var audioEncoder: MediaCodec? = null
    
    // Muxer
    private var mediaMuxer: MediaMuxer? = null
    private var videoTrackIndex = -1
    private var audioTrackIndex = -1
    private var muxerStarted = false
    
    // Recording state
    private var isRecording = false
    private var videoEncoderThread: Thread? = null
    private var audioRecordThread: Thread? = null
    private var audioEncoderThread: Thread? = null
    
    private var outputFilePath: String = ""
    private var bitrate = 8_000_000 // Default
    
    private val binder = LocalBinder()

    inner class LocalBinder : android.os.Binder() {
        fun getService(): ScreenRecordService = this@ScreenRecordService
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "ACTION_STOP") {
            stopRecording(false)
            stopSelf()
            return START_NOT_STICKY
        } else if (intent?.action == "ACTION_DISCARD") {
            stopRecording(true)
            stopSelf()
            return START_NOT_STICKY
        } else if (intent?.action == "ACTION_SHARE") {
            val path = intent.getStringExtra("path")
            if (path != null) {
                shareRecording(path)
            }
            closeNotificationPanel()
            return START_NOT_STICKY
        } else if (intent?.action == "ACTION_DELETE_FILE") {
            val path = intent.getStringExtra("path")
            if (path != null) {
                deleteRecording(path)
            }
            closeNotificationPanel()
            return START_NOT_STICKY
        }

        createNotificationChannel()
        
        val stopIntent = Intent(this, ScreenRecordService::class.java).apply {
            action = "ACTION_STOP"
        }
        val stopPendingIntent = android.app.PendingIntent.getService(
            this, 0, stopIntent, android.app.PendingIntent.FLAG_IMMUTABLE
        )

        val notification = androidx.core.app.NotificationCompat.Builder(this, "screen_rec_channel")
            .setContentTitle("Screen Recording")
            .setContentText("Recording in progress...")
            .setSmallIcon(R.mipmap.ic_launcher) // Make sure this resource exists or use a robust fallback or system icon
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_DEFAULT)
            .setUsesChronometer(true)
            .setShowWhen(true)
            .setWhen(System.currentTimeMillis())
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPendingIntent)
            
        val discardIntent = Intent(this, ScreenRecordService::class.java).apply {
            action = "ACTION_DISCARD"
        }
        val discardPendingIntent = android.app.PendingIntent.getService(
            this, 1, discardIntent, android.app.PendingIntent.FLAG_IMMUTABLE
        )
        notification.addAction(android.R.drawable.ic_delete, "Delete", discardPendingIntent)

        val builtNotification = notification.build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Needed for Android 14+ specific foreground service type
            try {
                 startForeground(1, builtNotification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            } catch (e: Exception) {
                 // Fallback if type not available or permission issue (though required)
                 startForeground(1, builtNotification)
            }
        } else {
            startForeground(1, builtNotification)
        }

        // Read bitrate
        if (intent?.hasExtra("bitrate") == true) {
            bitrate = intent.getIntExtra("bitrate", 8_000_000)
        }

        val resultCode = intent?.getIntExtra("resultCode", Int.MIN_VALUE) ?: Int.MIN_VALUE
        val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra("data", Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra("data")
        }

        if (resultCode != Int.MIN_VALUE && data != null) {
            val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = projectionManager.getMediaProjection(resultCode, data)
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) { // Android 14
                mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() {
                        stopRecording(false)
                        stopSelf()
                    }
                }, android.os.Handler(mainLooper))
            }
            
            try {
                startRecording()
            } catch (e: Exception) {
                android.util.Log.e("ScreenRecordService", "Error starting recording", e)
                android.widget.Toast.makeText(this, "Error: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
                stopSelf()
            }
        } else {
            android.widget.Toast.makeText(this, "Invalid data, stopping", android.widget.Toast.LENGTH_SHORT).show()
            stopSelf()
        }

        return START_NOT_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = android.app.NotificationChannel(
                "screen_rec_channel",
                "Screen Record Service",
                android.app.NotificationManager.IMPORTANCE_DEFAULT
            )
            val manager = getSystemService(android.app.NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    @SuppressLint("MissingPermission")
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun startRecording() {
        val metrics = resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val dpi = metrics.densityDpi
        
        // Setup output file
        val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
        val recFolder = java.io.File(downloadsDir, "RecPoor")
        if (!recFolder.exists()) {
            recFolder.mkdirs()
        }
        val sdf = java.text.SimpleDateFormat("ddMMyyHHmmss", java.util.Locale.US)
        outputFilePath = "${recFolder.absolutePath}/ScreenRec_${sdf.format(java.util.Date())}.mp4"
        
        // Initialize MediaMuxer
        mediaMuxer = MediaMuxer(outputFilePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        
        // Setup Video Encoder
        setupVideoEncoder(width, height)
        
        // Setup Audio (Internal Audio Capture)
        setupAudioCapture()
        
        // Create Virtual Display for screen capture
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenRecord",
            width, height, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            inputSurface,
            null, null
        )
        
        isRecording = true
        getSharedPreferences("com.deryk.recpoor.prefs", android.content.Context.MODE_PRIVATE)
            .edit()
            .putBoolean("is_recording", true)
            .apply()

        sendBroadcast(Intent("com.deryk.recpoor.ACTION_RECORDING_STARTED").apply {
            setPackage(packageName)
        })
        
        // Start encoder threads
        startVideoEncoderThread()
        startAudioRecordThread()
        startAudioEncoderThread()
    }

    private fun setupVideoEncoder(width: Int, height: Int) {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30) // It is already set to 30
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        
        videoEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = createInputSurface()
            start()
        }
    }

    @SuppressLint("MissingPermission")
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun setupAudioCapture() {
        val projection = mediaProjection ?: return
        
        val audioPlaybackCaptureConfig = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        
        val audioFormat = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(44100)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()
        
        val bufferSize = AudioRecord.getMinBufferSize(44100, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        
        audioRecord = AudioRecord.Builder()
            .setAudioPlaybackCaptureConfig(audioPlaybackCaptureConfig)
            .setAudioFormat(audioFormat)
            .setBufferSizeInBytes(bufferSize * 2)
            .build()
        
        // Setup Audio Encoder
        val audioMediaFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 44100, 2).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
        }
        
        audioEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
            configure(audioMediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            start()
        }
        
        audioRecord?.startRecording()
    }

    private fun startVideoEncoderThread() {
        videoEncoderThread = Thread {
            val bufferInfo = MediaCodec.BufferInfo()
            while (isRecording) {
                val outputBufferIndex = videoEncoder?.dequeueOutputBuffer(bufferInfo, 10000) ?: -1
                
                when {
                    outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        synchronized(this) {
                            videoTrackIndex = mediaMuxer?.addTrack(videoEncoder!!.outputFormat) ?: -1
                            tryStartMuxer()
                        }
                    }
                    outputBufferIndex >= 0 -> {
                        val outputBuffer = videoEncoder?.getOutputBuffer(outputBufferIndex)
                        if (outputBuffer != null && muxerStarted && bufferInfo.size > 0 && bufferInfo.presentationTimeUs > 0) { // Added presentationTime check for safety
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            synchronized(this) {
                                try {
                                    mediaMuxer?.writeSampleData(videoTrackIndex, outputBuffer, bufferInfo)
                                } catch (e: Exception) {
                                    android.util.Log.e("ScreenRecordService", "Error writing video", e)
                                }
                            }
                        }
                        videoEncoder?.releaseOutputBuffer(outputBufferIndex, false)
                    }
                }
            }
        }.apply { start() }
    }

    private fun startAudioRecordThread() {
        audioRecordThread = Thread {
            val bufferSize = 1024 * 2
            val audioData = ByteArray(bufferSize)
            
            while (isRecording) {
                val readBytes = audioRecord?.read(audioData, 0, bufferSize) ?: 0
                if (readBytes > 0) {
                    val inputBufferIndex = audioEncoder?.dequeueInputBuffer(10000) ?: -1
                    if (inputBufferIndex >= 0) {
                        val inputBuffer = audioEncoder?.getInputBuffer(inputBufferIndex)
                        inputBuffer?.clear()
                        inputBuffer?.put(audioData, 0, readBytes)
                        audioEncoder?.queueInputBuffer(inputBufferIndex, 0, readBytes, System.nanoTime() / 1000, 0)
                    }
                }
            }
        }.apply { start() }
    }

    private fun startAudioEncoderThread() {
        audioEncoderThread = Thread {
            val bufferInfo = MediaCodec.BufferInfo()
            while (isRecording) {
                val outputBufferIndex = audioEncoder?.dequeueOutputBuffer(bufferInfo, 10000) ?: -1
                
                when {
                    outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        synchronized(this) {
                            audioTrackIndex = mediaMuxer?.addTrack(audioEncoder!!.outputFormat) ?: -1
                            tryStartMuxer()
                        }
                    }
                    outputBufferIndex >= 0 -> {
                        val outputBuffer = audioEncoder?.getOutputBuffer(outputBufferIndex)
                        if (outputBuffer != null && muxerStarted && bufferInfo.size > 0) {
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            synchronized(this) {
                                try {
                                    mediaMuxer?.writeSampleData(audioTrackIndex, outputBuffer, bufferInfo)
                                } catch (e: Exception) {
                                    android.util.Log.e("ScreenRecordService", "Error writing audio", e)
                                }
                            }
                        }
                        audioEncoder?.releaseOutputBuffer(outputBufferIndex, false)
                    }
                }
            }
        }.apply { start() }
    }

    private fun tryStartMuxer() {
        if (!muxerStarted && videoTrackIndex >= 0 && audioTrackIndex >= 0) {
            mediaMuxer?.start()
            muxerStarted = true
            android.util.Log.d("ScreenRecordService", "Muxer started")
        }
    }

    private fun stopRecording(delete: Boolean = false) {
        if (!isRecording) return
        isRecording = false

        getSharedPreferences("com.deryk.recpoor.prefs", android.content.Context.MODE_PRIVATE)
             .edit()
             .putBoolean("is_recording", false)
             .apply()
        
        // Wait for threads to finish - slightly reduced timeout
        try {
            videoEncoderThread?.join(500)
            audioRecordThread?.join(500)
            audioEncoderThread?.join(500)
        } catch (e: Exception) {
            android.util.Log.e("ScreenRecordService", "Error waiting for threads", e)
        }
        
        // Stop and release audio record
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            android.util.Log.e("ScreenRecordService", "Error stopping audio record", e)
        }
        
        // Signal end of stream to encoders
        try {
            videoEncoder?.signalEndOfInputStream()
        } catch (e: Exception) {
            android.util.Log.e("ScreenRecordService", "Error signaling video EOS", e)
        }
        
        // Stop encoders
        try {
            videoEncoder?.stop()
            videoEncoder?.release()
        } catch (e: Exception) {
            android.util.Log.e("ScreenRecordService", "Error stopping video encoder", e)
        }
        
        try {
            audioEncoder?.stop()
            audioEncoder?.release()
        } catch (e: Exception) {
            android.util.Log.e("ScreenRecordService", "Error stopping audio encoder", e)
        }
        
        // Stop muxer
        try {
            if (muxerStarted) {
                mediaMuxer?.stop()
            }
            mediaMuxer?.release()
        } catch (e: Exception) {
            android.util.Log.e("ScreenRecordService", "Error stopping muxer", e)
        }
        
        // Release virtual display
        try {
            virtualDisplay?.release()
        } catch (e: Exception) {
            android.util.Log.e("ScreenRecordService", "Error releasing virtual display", e)
        }
        
        // Stop projection
        try {
            mediaProjection?.stop()
        } catch (e: Exception) {
            android.util.Log.e("ScreenRecordService", "Error stopping projection", e)
        }
        
        if (delete) {
            try {
                val file = java.io.File(outputFilePath)
                if (file.exists()) {
                    file.delete()
                    android.os.Handler(mainLooper).post {
                         android.widget.Toast.makeText(this, "Recording discarded", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("ScreenRecordService", "Error deleting file", e)
            }
        } else {
            android.util.Log.d("ScreenRecordService", "Recording saved to: $outputFilePath")
            
            // Show "Recording Saved" notification ONLY if not deleted
            val intent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("navigate_to", "recordings")
            }
            
            val pendingIntent = android.app.PendingIntent.getActivity(
                this, 0, intent, android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
            )
            
            val shareIntent = Intent(this, ScreenRecordService::class.java).apply {
                action = "ACTION_SHARE"
                putExtra("path", outputFilePath)
            }
            val sharePendingIntent = android.app.PendingIntent.getService(
                this, 2, shareIntent, android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
            )

            val deleteIntent = Intent(this, ScreenRecordService::class.java).apply {
                action = "ACTION_DELETE_FILE"
                putExtra("path", outputFilePath)
            }
            val deletePendingIntent = android.app.PendingIntent.getService(
                this, 3, deleteIntent, android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
            )

            val notification = androidx.core.app.NotificationCompat.Builder(this, "screen_rec_channel")
                .setContentTitle("Recording Saved")
                .setContentText("Tap to view recordings")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
                .addAction(android.R.drawable.ic_menu_share, "Share", sharePendingIntent)
                .addAction(android.R.drawable.ic_menu_delete, "Delete", deletePendingIntent)
                .build()
                
            val notificationManager = getSystemService(android.app.NotificationManager::class.java)
            notificationManager.notify(2, notification)
        }

        sendBroadcast(Intent("com.deryk.recpoor.ACTION_RECORDING_STOPPED").apply {
            setPackage(packageName)
        })
    }


    private fun shareRecording(path: String) {
        try {
            val file = java.io.File(path)
            if (!file.exists()) return

            val uri = androidx.core.content.FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",
                file
            )

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "video/mp4"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            
            val chooser = Intent.createChooser(shareIntent, "Share Recording").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(chooser)
        } catch (e: Exception) {
            android.util.Log.e("ScreenRecordService", "Error sharing", e)
        }
    }

    private fun deleteRecording(path: String) {
        try {
            val file = java.io.File(path)
            if (file.exists()) {
                file.delete()
                android.widget.Toast.makeText(this, "Recording deleted", android.widget.Toast.LENGTH_SHORT).show()
                val notificationManager = getSystemService(android.app.NotificationManager::class.java)
                notificationManager.cancel(2)
            }
        } catch (e: Exception) {
            android.util.Log.e("ScreenRecordService", "Error deleting file", e)
        }
    }

    private fun closeNotificationPanel() {
        sendBroadcast(Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS))
    }

    override fun onDestroy() {
        super.onDestroy()
        stopRecording(false)
        // Toast might crash if context is gone, but usually OK in onDestroy
        // android.widget.Toast.makeText(this, "Recording saved to Downloads", android.widget.Toast.LENGTH_LONG).show()
    }
}
