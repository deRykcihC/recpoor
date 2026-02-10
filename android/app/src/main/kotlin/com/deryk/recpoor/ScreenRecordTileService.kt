package com.deryk.recpoor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi

@RequiresApi(Build.VERSION_CODES.N)
class ScreenRecordTileService : TileService() {

    private val recordingReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            updateTile()
        }
    }

    override fun onStartListening() {
        super.onStartListening()
        val filter = IntentFilter().apply {
            addAction("com.deryk.recpoor.ACTION_RECORDING_STARTED")
            addAction("com.deryk.recpoor.ACTION_RECORDING_STOPPED")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(recordingReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(recordingReceiver, filter)
        }
        updateTile()
    }

    override fun onStopListening() {
        super.onStopListening()
        unregisterReceiver(recordingReceiver)
    }

    override fun onClick() {
        if (isLocked) {
           unlockAndRun { handleClick() }
        } else {
            handleClick()
        }
    }

    private fun handleClick() {
        val prefs = getSharedPreferences("com.deryk.recpoor.prefs", Context.MODE_PRIVATE)
        val isRecording = prefs.getBoolean("is_recording", false)

        if (isRecording) {
            // Stop recording
            val intent = Intent(this, ScreenRecordService::class.java).apply {
                action = "ACTION_STOP"
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } else {
            // Start recording -> Open Transparent Activity
            val intent = Intent(this, TransparentCaptureActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val pendingIntent = android.app.PendingIntent.getActivity(
                    this, 
                    0, 
                    intent, 
                    android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
                )
                startActivityAndCollapse(pendingIntent)
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        }
    }

    private fun updateTile() {
        val prefs = getSharedPreferences("com.deryk.recpoor.prefs", Context.MODE_PRIVATE)
        val isRecording = prefs.getBoolean("is_recording", false)
        
        qsTile?.apply {
            state = if (isRecording) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            label = if (isRecording) "Stop Rec" else "Screen Rec"
            icon = android.graphics.drawable.Icon.createWithResource(this@ScreenRecordTileService, R.mipmap.ic_launcher)
            updateTile()
        }
    }
}
