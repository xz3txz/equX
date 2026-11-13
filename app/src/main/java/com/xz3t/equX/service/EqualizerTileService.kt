package com.xz3t.equX.service

import com.xz3t.equX.data.EquXLog
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.xz3t.equX.R

class EqualizerTileService : TileService() {

    private var equalizerService: EqualizerService? = null
    private var isBound = false

    // Подключение к нашей фоновой службе для управления звуком
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? EqualizerService.LocalBinder
            equalizerService = binder?.getService()
            isBound = true
            updateTileState() // Обновляем вид кнопки при подключении
            EquXLog.d("equX_TileService", "Успешно подключено к EqualizerService")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            equalizerService = null
            isBound = false
            EquXLog.d("equX_TileService", "Отключено от EqualizerService")
        }
    }

    override fun onStartListening() {
        super.onStartListening()
        // При открытии шторки привязываемся к запущенной службе, чтобы узнать актуальный статус звука
        val intent = Intent(this, EqualizerService::class.java)
        bindService(intent, connection, BIND_AUTO_CREATE)
    }

    override fun onStopListening() {
        super.onStopListening()
        if (isBound) {
            unbindService(connection)
            isBound = false
        }
    }

    override fun onClick() {
        super.onClick()
        val service = equalizerService ?: return

        // Меняем статус обработки звука на противоположный
        val newState = !service.isAudioProcessingEnabled()
        service.setAudioProcessingEnabled(newState)

        // Мгновенно обновляем графическое состояние плитки в шторке
        updateTileState()
        EquXLog.d("equX_TileService", "Клик по плитке. Новый статус обработки: $newState")
    }

    /**
     * Обновление визуального состояния плитки (Активна/Выключена)
     */
    private fun updateTileState() {
        val tile = qsTile ?: return
        val isEnabled = equalizerService?.isAudioProcessingEnabled() ?: true

        if (isEnabled) {
            tile.state = Tile.STATE_ACTIVE
            tile.label = getString(R.string.tile_on)
        } else {
            tile.state = Tile.STATE_INACTIVE
            tile.label = getString(R.string.tile_off)
        }
        tile.updateTile() // Отправляем команду системе перерисовать кнопку
    }
}
