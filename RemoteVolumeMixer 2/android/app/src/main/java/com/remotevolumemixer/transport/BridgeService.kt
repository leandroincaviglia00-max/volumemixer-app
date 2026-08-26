package com.remotevolumemixer.transport

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.remotevolumemixer.MainActivity
import com.remotevolumemixer.R
import com.remotevolumemixer.RvmApplication
import com.remotevolumemixer.data.ConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Servizio in foreground che tiene vivo il socket USB anche quando l'app
 * non e' in primo piano. Nessuna rete, nessun wake lock aggressivo.
 */
class BridgeService : Service() {

    private companion object {
        const val TAG = "RVM/Service"
        const val CHANNEL_ID = "usb_bridge"
        const val NOTIFICATION_ID = 42
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.notification_waiting)))

        val repository = (application as RvmApplication).container.repository
        repository.ensureStarted()

        serviceScope.launch {
            repository.state
                .map { it.connection }
                .distinctUntilChanged()
                .collect { connection ->
                    val text = when (connection) {
                        ConnectionState.Connected -> getString(R.string.notification_connected)
                        else -> getString(R.string.notification_waiting)
                    }
                    notify(text)
                }
        }

        Log.i(TAG, "USB bridge service started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.i(TAG, "USB bridge service stopping")
        serviceScope.cancel()
        (application as RvmApplication).container.repository.shutdown()
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_description)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        return builder
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pending)
            .setOngoing(true)
            .build()
    }

    private fun notify(text: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }
}
