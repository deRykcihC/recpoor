package com.deryk.recpoor

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.media.projection.MediaProjectionConfig
import android.os.Build
import android.os.Bundle

class TransparentCaptureActivity : Activity() {
    private val REQUEST_MEDIA_PROJECTION = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val intent = if (Build.VERSION.SDK_INT >= 34) {
            mediaProjectionManager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            mediaProjectionManager.createScreenCaptureIntent()
        }
        startActivityForResult(intent, REQUEST_MEDIA_PROJECTION)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_MEDIA_PROJECTION) {
            if (resultCode == RESULT_OK && data != null) {
                // Get bitrate from prefs
                val prefs = getSharedPreferences("com.deryk.recpoor.prefs", Context.MODE_PRIVATE)
                val bitrate = prefs.getInt("selected_bitrate", 8_000_000)

                val serviceIntent = Intent(this, ScreenRecordService::class.java).apply {
                    putExtra("resultCode", resultCode)
                    putExtra("data", data)
                    putExtra("bitrate", bitrate)
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }
            }
            finish()
        }
    }
}
