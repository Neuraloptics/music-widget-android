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

    override fun onNotificationPosted(sbn: StatusBarNotification?) = refresh()
    override fun onNotificationRemoved(sbn: StatusBarNotification?) = refresh()

    private fun refresh() {
        try {
            handleControllers(sessionMgr?.getActiveSessions(
                ComponentName(this, MusicListenerService::class.java)
            ))
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun handleControllers(controllers: List<MediaController>?) {
        val ctrl = controllers?.firstOrNull()
        if (ctrl == null) { persist("No music playing", "", false, null); return }

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
