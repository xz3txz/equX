package com.xz3t.equX.service

import com.xz3t.equX.data.EquXLog
import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.xz3t.equX.data.MediaAppScanner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MediaNotificationService : NotificationListenerService() {

    data class MediaSessionInfo(
        val packageName: String,
        val isPlaying: Boolean,
        val state: Int,
    )

    companion object {
        var onMediaSessionStateChanged: (() -> Unit)? = null

        fun getActiveMediaSessions(context: Context): List<MediaSessionInfo> {
            return try {
                val component = ComponentName(context, MediaNotificationService::class.java)
                val manager = context.getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager
                val sessions = manager.getActiveSessions(component)
                sessions.map { controller ->
                    val playbackState = controller.playbackState
                    val state = playbackState?.state ?: PlaybackState.STATE_NONE
                    val isPlaying = state == PlaybackState.STATE_PLAYING
                    MediaSessionInfo(controller.packageName, isPlaying, state)
                }
            } catch (e: SecurityException) {
                Log.w("equX_MediaListener", "Нет прав NotificationListenerService (MediaSessionManager)")
                emptyList()
            } catch (e: Exception) {
                Log.e("equX_MediaListener", "Ошибка получения активных сессий: ${e.message}")
                emptyList()
            }
        }

        /**
         * Возвращает пакет активного плеера.
         *
         * На прошивках vivo PlaybackState из MediaSession ненадёжен: фоновый плеер может
         * оставаться в STATE_PLAYING, хотя его звук на паузе, а реально играющее приложение
         * может отдавать STATE_STOPPED. Поэтому главный источник — «media key event session»
         * (то, что система считает текущим медиаплеером для медиакнопок). Это публичный API,
         * аналог «Media button session» из dumpsys.
         */
        fun getActiveMediaPackage(context: Context): String? {
            val sessions = getActiveMediaSessions(context)
            val playingPkg = sessions.firstOrNull { it.isPlaying }?.packageName
            val mediaKeyPkg = getMediaKeyEventSessionPackage(context)

            // 1. Медиа-кнопка ушла на другой плеер, чем «играющий» — система только что
            //    переключила активного плеера. Побеждает медиа-кнопка (быстро и надёжно).
            if (mediaKeyPkg != null && playingPkg != null && mediaKeyPkg != playingPkg) {
                return mediaKeyPkg
            }

            // 2. Честный STATE_PLAYING.
            if (playingPkg != null) return playingPkg

            // 3. YouTube-кейс на vivo: плеер играет, но отдаёт STATE_STOPPED.
            //    Доверяем медиа-кнопке, только если её сессия НЕ на паузе. Иначе это
            //    залипший плеер на паузе, а реально играет приложение без MediaSession.
            if (mediaKeyPkg != null) {
                val mediaKeyPaused = sessions.any { it.packageName == mediaKeyPkg && it.state == PlaybackState.STATE_PAUSED }
                if (!mediaKeyPaused) return mediaKeyPkg
            }

            return null
        }

        private fun getMediaKeyEventSessionPackage(context: Context): String? {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
            return try {
                val manager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
                manager.getMediaKeyEventSessionPackageName()
            } catch (e: Exception) {
                Log.w("equX_MediaListener", "Не удалось получить media key session: ${e.message}")
                null
            }
        }
    }

    private var sessionListener: MediaSessionManager.OnActiveSessionsChangedListener? = null

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var lastTriggerMs = 0L

    override fun onListenerConnected() {
        super.onListenerConnected()
        EquXLog.i("equX_MediaListener", "=== NotificationListenerService успешно подключен ===")
        registerMediaSessionListener()

        // Прогреваем динамический список медиаплееров заранее, чтобы к моменту первого
        // уведомления он уже был доступен. TTL сканера — 60 секунд.
        serviceScope.launch {
            while (true) {
                runCatching { MediaAppScanner(this@MediaNotificationService).scanInstalledMediaApps() }
                delay(60_000L)
            }
        }
    }

    private fun registerMediaSessionListener() {
        try {
            val manager = getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager
            val component = ComponentName(this, MediaNotificationService::class.java)

            sessionListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
                EquXLog.i("equX_MediaListener", "--> Изменение активных MediaSession (всего: ${controllers?.size ?: 0}):")
                controllers?.forEach { controller ->
                    val state = controller.playbackState?.state ?: PlaybackState.STATE_NONE
                    val isPlaying = state == PlaybackState.STATE_PLAYING
                    val title = controller.metadata?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE)
                    EquXLog.i("equX_MediaListener", "   - Pkg: ${controller.packageName}, Playing=$isPlaying, State=$state, Title=$title")
                }

                onMediaSessionStateChanged?.invoke()
            }

            manager.addOnActiveSessionsChangedListener(sessionListener!!, component)

            // Первоначальное логирование сессий при подключении
            val currentSessions = manager.getActiveSessions(component)
            EquXLog.i("equX_MediaListener", "Текущие активные MediaSession при старте (всего: ${currentSessions.size}):")
            currentSessions.forEach { controller ->
                val state = controller.playbackState?.state ?: PlaybackState.STATE_NONE
                val isPlaying = state == PlaybackState.STATE_PLAYING
                val title = controller.metadata?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE)
                EquXLog.i("equX_MediaListener", "   - Pkg: ${controller.packageName}, Playing=$isPlaying, State=$state, Title=$title")
            }
        } catch (e: Exception) {
            Log.e("equX_MediaListener", "Ошибка подписки на MediaSessionManager: ${e.message}")
        }
    }

    /**
     * Источник истины «кто сейчас играет» на прошивках, где MediaSessionManager
     * не отдаёт STATE_PLAYING: медиа-уведомление плеера.
     *
     * Читаем ТОЛЬКО packageName и системные метаданные уведомления (категория,
     * наличие MediaSession-токена). Содержимое (extras/title/text) НЕ читаем.
     *
     * Список «это медиа-плеер» берётся динамически из MediaAppScanner — это тот же
     * источник, который формирует галочки в меню «Приложения». Никакого хардкода пакетов.
     */
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        val pkg = sbn.packageName ?: return

        if (isMediaPackage(sbn, pkg)) {
            EquXLog.i("equX_MediaListener", "Медиа-уведомление от: $pkg")
            notifyStateChanged()
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn == null) return
        notifyStateChanged()
    }

    private fun isMediaPackage(sbn: StatusBarNotification, pkg: String): Boolean {
        // 1. Стандартные системные маркеры медиа-уведомления.
        val notification = try { sbn.notification } catch (e: Exception) { null }
        if (notification != null) {
            if (notification.category == Notification.CATEGORY_TRANSPORT) return true
            val hasMediaSession = try {
                notification.extras?.containsKey(Notification.EXTRA_MEDIA_SESSION) == true
            } catch (e: Exception) {
                false
            }
            if (hasMediaSession) return true
        }

        // 2. Динамический список медиаплееров (из сканера / галочек в меню).
        val knownPackages = MediaAppScanner.getCachedPackageNames()
        return pkg in knownPackages
    }

    private fun notifyStateChanged() {
        val now = System.currentTimeMillis()
        if (now - lastTriggerMs < 500L) return
        lastTriggerMs = now
        onMediaSessionStateChanged?.invoke()
    }

    override fun onDestroy() {
        super.onDestroy()
        EquXLog.i("equX_MediaListener", "NotificationListenerService уничтожен")
        serviceScope.cancel()
        try {
            sessionListener?.let {
                val manager = getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager
                manager.removeOnActiveSessionsChangedListener(it)
            }
        } catch (e: Exception) {
            // Освобождение при остановке: доступ к уведомлениям мог быть уже отозван.
        }
    }
}
