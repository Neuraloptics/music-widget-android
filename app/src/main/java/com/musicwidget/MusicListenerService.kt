package com.musicwidget

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.io.File
import java.io.FileOutputStream

class MusicListenerService : NotificationListenerService() {

    private var sessionMgr: MediaSessionManager? = null

    private val sessionsListener =
        MediaSessionManager.OnActiveSessionsChangedListener { handleControllers(it) }

    override fun onListenerConnected() {
        super.onListenerConnected()
        sessionMgr = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        try {
            sessionMgr?.addOnActiveSessionsChangedListener(
                sessionsListener,
                ComponentName(this, MusicListenerService::class.java)
            )
            refresh()
        } catch (e: Exception) { e.printStackTrace() }
    }

    override fun onListenerDisconnected() {
        sessionMgr?.removeOnActiveSessionsChangedListener(sessionsListener)
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        // Fallback: si MediaSession no devuelve nada, leemos la notificación directamente
        if (!tryMediaSession()) {
            tryFromNotification(sbn)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        refresh()
    }

    private fun refresh() {
        if (!tryMediaSession()) {
            // intento con notificaciones activas
            val active = try { activeNotifications } catch (e: Exception) { null }
            active?.firstOrNull { isMediaNotification(it) }?.let {
                tryFromNotification(it)
            } ?: persist("No music playing", "", false, null)
        }
    }

    /** Intenta obtener info desde MediaSession. Retorna true si encontró sesión activa. */
    private fun tryMediaSession(): Boolean {
        return try {
            val sessions = sessionMgr?.getActiveSessions(
                ComponentName(this, MusicListenerService::class.java)
            )
            val ctrl = sessions?.firstOrNull() ?: return false
            handleController(ctrl)
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Fallback: extrae metadata desde extras de la notificación (compatible con apps fork). */
    private fun tryFromNotification(sbn: StatusBarNotification?) {
        sbn ?: return
        if (!isMediaNotification(sbn)) return

        val extras = sbn.notification?.extras ?: return
        val title  = extras.getString("android.title") ?: return
        val artist = extras.getString("android.text") ?: ""
        val isPlaying = (sbn.notification.flags and android.app.Notification.FLAG_ONGOING_EVENT) != 0

        val art: Bitmap? = try {
            val icon = extras.getParcelable<android.graphics.drawable.Icon>("android.largeIcon")
            icon?.loadDrawable(this)?.let { drawable ->
                val bmp = android.graphics.Bitmap.createBitmap(120, 120, android.graphics.Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(bmp)
                drawable.setBounds(0, 0, 120, 120)
                drawable.draw(canvas)
                bmp
            }
        } catch (e: Exception) { null }

        persist(title, artist, isPlaying, art)
    }

    private fun isMediaNotification(sbn: StatusBarNotification): Boolean {
        val extras = sbn.notification?.extras ?: return false
        // tiene template de media O tiene título de canción
        val template = extras.getString("android.template") ?: ""
        val hasTitle = extras.getString("android.title") != null
        return template.contains("MediaStyle", ignoreCase = true) || hasTitle
    }

    private fun handleControllers(controllers: List<MediaController>?) {
        val ctrl = controllers?.firstOrNull()
        if (ctrl == null) { refresh(); return }
        handleController(ctrl)
    }

    private fun handleController(ctrl: MediaController) {
        val meta    = ctrl.metadata
        val title   = meta?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "Unknown"
        val artist  = meta?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                   ?: meta?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST) ?: ""
        val playing = ctrl.playbackState?.state == PlaybackState.STATE_PLAYING
        val rawArt  = meta?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                   ?: meta?.getBitmap(MediaMetadata.METADATA_KEY_ART)
        val art     = rawArt?.let { Bitmap.createScaledBitmap(it, 120, 120, true) }
        persist(title, artist, playing, art)
    }

    private fun persist(title: String, artist: String, playing: Boolean, art: Bitmap?) {
        getSharedPreferences("mw_prefs", Context.MODE_PRIVATE).edit()
            .putString("title",   title)
            .putString("artist",  artist)
            .putBoolean("playing", playing)
            .apply()

        val artFile = File(filesDir, "album_art.png")
        if (art != null) {
            try {
                FileOutputStream(artFile).use { art.compress(Bitmap.CompressFormat.PNG, 80, it) }
            } catch (e: Exception) { e.printStackTrace() }
        } else {
            artFile.delete()
        }

        sendBroadcast(Intent(this, MusicWidgetProvider::class.java).apply {
            action = MusicWidgetProvider.ACTION_UPDATE
        })
    }
}
