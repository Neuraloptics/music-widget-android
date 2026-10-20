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

    companion object {
        val MUSIC_PACKAGES = setOf(
            "com.spotify.music",
            "com.google.android.apps.youtube.music",
            "app.revanced.android.apps.youtube.music",
            "com.vanced.android.apps.youtube.music",
            "com.soundcloud.android",
            "com.amazon.mp3",
            "com.tidal.music",
            "com.deezer.android",
            "com.apple.android.music",
            "com.pandora.android",
            "code.name.monkey.retromusic",
            "com.maxmpz.audioplayer",
            "com.neutroncode.mp",
            "org.videolan.vlc",
            "com.dd3boh.outertune",
            "com.dd3boh.outertune.debug",
            "app.opentune",
            "com.opentune",
            "com.listenbrainz.android",
            "com.musicplayer.mp3player"
        )
    }

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
        // FIX: notificar al widget que el permiso ya fue otorgado
        sendBroadcast(Intent(this, MusicWidgetProvider::class.java).apply {
            action = MusicWidgetProvider.ACTION_UPDATE
        })
    }

    override fun onListenerDisconnected() {
        sessionMgr?.removeOnActiveSessionsChangedListener(sessionsListener)
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (!tryMediaSession()) tryFromNotification(sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) = refresh()

    private fun refresh() {
        if (!tryMediaSession()) {
            val active = try { activeNotifications } catch (e: Exception) { null }
            active?.firstOrNull { isRealMediaNotification(it) }?.let {
                tryFromNotification(it)
            } ?: persist("No music playing", "", false, null, null, hasSession = false)
        }
    }

    private fun tryMediaSession(): Boolean {
        return try {
            val sessions = sessionMgr?.getActiveSessions(
                ComponentName(this, MusicListenerService::class.java)
            )
            val ctrl = sessions?.firstOrNull() ?: return false
            handleController(ctrl)
            true
        } catch (e: Exception) { false }
    }

    private fun isRealMediaNotification(sbn: StatusBarNotification): Boolean {
        val extras = sbn.notification?.extras ?: return false
        val template = extras.getString("android.template") ?: ""
        return template.contains("MediaStyle", ignoreCase = true)
            || sbn.packageName in MUSIC_PACKAGES
    }

    private fun tryFromNotification(sbn: StatusBarNotification?) {
        sbn ?: return
        if (!isRealMediaNotification(sbn)) return

        val extras = sbn.notification?.extras ?: return
        val title   = extras.getString("android.title") ?: return
        val artist  = extras.getString("android.text") ?: ""
        val playing = (sbn.notification.flags and android.app.Notification.FLAG_ONGOING_EVENT) != 0

        val art: Bitmap? = try {
            val icon = extras.getParcelable<android.graphics.drawable.Icon>("android.largeIcon")
            icon?.loadDrawable(this)?.let { drawable ->
                val bmp = Bitmap.createBitmap(120, 120, Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(bmp)
                drawable.setBounds(0, 0, 120, 120)
                drawable.draw(canvas)
                bmp
            }
        } catch (e: Exception) { null }

        persist(title, artist, playing, art, sbn.packageName, hasSession = true)
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
        persist(title, artist, playing, art, ctrl.packageName, hasSession = true)
    }

    private fun persist(
        title: String, artist: String, playing: Boolean,
        art: Bitmap?, musicPackage: String?, hasSession: Boolean
    ) {
        getSharedPreferences("mw_prefs", Context.MODE_PRIVATE).edit()
            .putString("title",   title)
            .putString("artist",  artist)
            .putBoolean("playing", playing)
            .putBoolean("has_session", hasSession)  // FIX: flag separado para "hay sesion activa"
            .apply { musicPackage?.let { putString("music_package", it) } }
            .apply()

        val artFile = File(filesDir, "album_art.png")
        if (art != null) {
            try { FileOutputStream(artFile).use { art.compress(Bitmap.CompressFormat.PNG, 80, it) } }
            catch (e: Exception) { e.printStackTrace() }
        } else if (!hasSession) { artFile.delete() }

        sendBroadcast(Intent(this, MusicWidgetProvider::class.java).apply {
            action = MusicWidgetProvider.ACTION_UPDATE
        })
    }
}
