package com.remotevolumemixer

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import com.remotevolumemixer.data.MixerRepository
import com.remotevolumemixer.data.SettingsStore
import com.remotevolumemixer.transport.BridgeService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Contenitore delle dipendenze: niente framework di DI, solo istanze uniche
 * condivise tra UI e servizio.
 */
class AppContainer(context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val repository: MixerRepository = MixerRepository(context, scope)
    val settings: SettingsStore = SettingsStore(context)
}

class RvmApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }

    /** Avvia il servizio in foreground che ospita il canale USB. */
    fun startBridgeService() {
        val intent = Intent(this, BridgeService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }
}
