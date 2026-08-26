package com.remotevolumemixer.data

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.Collections

/**
 * Cache delle icone reali delle applicazioni Windows.
 *
 * Le icone arrivano dal PC come PNG base64, una sola volta per chiave, e vengono
 * salvate su disco: al riavvio dell'app compaiono immediatamente.
 * Finche' un'icona non e' disponibile la UI disegna il proprio riquadro di
 * fallback (mai un'immagine rotta).
 */
class IconStore(
    private val context: Context,
    private val scope: CoroutineScope
) {
    private companion object {
        const val TAG = "RVM/Icons"
    }

    private val _icons = MutableStateFlow<Map<String, ImageBitmap>>(emptyMap())
    val icons: StateFlow<Map<String, ImageBitmap>> = _icons.asStateFlow()

    private val requested = Collections.synchronizedSet(mutableSetOf<String>())

    private val directory: File by lazy {
        File(context.filesDir, "icons").apply { mkdirs() }
    }

    /**
     * Assicura che l'icona sia disponibile: prima il disco, poi il PC.
     * [request] viene invocata solo se serve davvero chiedere il PNG via USB.
     */
    fun ensure(iconKey: String, request: (String) -> Unit) {
        if (iconKey.isBlank() || iconKey == "__master__") return
        if (_icons.value.containsKey(iconKey)) return
        if (!requested.add(iconKey)) return

        scope.launch(Dispatchers.IO) {
            val cached = loadFromDisk(iconKey)
            if (cached != null) {
                publish(iconKey, cached)
            } else {
                request(iconKey)
            }
        }
    }

    fun onIconReceived(iconKey: String, base64Png: String?) {
        if (iconKey.isBlank()) return
        if (base64Png.isNullOrBlank()) {
            Log.d(TAG, "No icon available for $iconKey, keeping the fallback tile")
            return
        }

        scope.launch(Dispatchers.IO) {
            try {
                val bytes = Base64.decode(base64Png, Base64.DEFAULT)
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@launch
                runCatching { File(directory, "$iconKey.png").writeBytes(bytes) }
                publish(iconKey, bitmap.asImageBitmap())
            } catch (t: Throwable) {
                Log.w(TAG, "Could not decode the icon for $iconKey: ${t.message}")
            }
        }
    }

    /** Alla riconnessione le richieste fallite possono essere ritentate. */
    fun resetPendingRequests() {
        val available = _icons.value.keys
        synchronized(requested) {
            requested.retainAll(available)
        }
    }

    private fun publish(iconKey: String, bitmap: ImageBitmap) {
        _icons.value = _icons.value + (iconKey to bitmap)
    }

    private fun loadFromDisk(iconKey: String): ImageBitmap? {
        val file = File(directory, "$iconKey.png")
        if (!file.exists()) return null
        return try {
            BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap()
        } catch (t: Throwable) {
            Log.d(TAG, "Cached icon unreadable for $iconKey: ${t.message}")
            null
        }
    }
}
