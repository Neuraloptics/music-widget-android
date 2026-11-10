package com.musicwidget

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

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

        const val ART_SIZE = 256

        // Referencia al controller activo (usada por el provider para TransportControls)
        var activeController: MediaController? = null
            private set

        // Album art fuera del hilo principal: nunca bloquea el service
        private val artExecutor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "mw-art").apply { isDaemon = true }
        }
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

        // broadcast doble: inmediato + delay 800ms (el permiso puede tardar en reflejarse)
        val makeIntent = { Intent(this, MusicWidgetProvider::class.java).apply {
            action = MusicWidgetProvider.ACTION_UPDATE
        }}
        sendBroadcast(makeIntent())
        Handler(mainLooper).postDelayed({ sendBroadcast(makeIntent()) }, 800)
    }

    override fun onListenerDisconnected() {
        activeController = null
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
                val bmp = Bitmap.createBitmap(ART_SIZE, ART_SIZE, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bmp)
                drawable.setBounds(0, 0, ART_SIZE, ART_SIZE)
                drawable.draw(canvas)
                roundCorners(bmp)
            }
        } catch (e: Exception) { null }

        persist(title, artist, playing, art, sbn.packageName, hasSession = true)
    }

    private fun handleControllers(controllers: List<MediaController>?) {
        val ctrl = controllers?.firstOrNull()
        if (ctrl == null) {
            activeController = null
            refresh()
            return
        }
        handleController(ctrl)
    }

    private fun handleController(ctrl: MediaController) {
        activeController = ctrl
        val meta    = ctrl.metadata
        val title   = meta?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "Unknown"
        val artist  = meta?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                   ?: meta?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST) ?: ""
        val album   = meta?.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: ""
        val state   = ctrl.playbackState
        val playing = state?.state == PlaybackState.STATE_PLAYING
        val position = state?.position ?: 0L
        val duration = meta?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: -1L
        val rawArt  = meta?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                   ?: meta?.getBitmap(MediaMetadata.METADATA_KEY_ART)
        val art     = rawArt?.let { roundCorners(scaleArt(it)) }
        persist(title, artist, playing, art, ctrl.packageName, true, album, position, duration)
    }

    // esquinas redondeadas estilo M3 Expressive (se calcula una vez por canción)
    private fun roundCorners(src: Bitmap): Bitmap {
        val output = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint  = Paint(Paint.ANTI_ALIAS_FLAG)
        val rect   = RectF(0f, 0f, src.width.toFloat(), src.height.toFloat())
        val radius = src.width * 0.14f
        paint.color = 0xFFFFFFFF.toInt()
        canvas.drawRoundRect(rect, radius, radius, paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(src, 0f, 0f, paint)
        return output
    }

    // escala manteniendo aspect ratio, max ART_SIZE de lado
    private fun scaleArt(src: Bitmap): Bitmap {
        val maxSide = maxOf(src.width, src.height)
        if (maxSide <= ART_SIZE) return src
        val scale = ART_SIZE.toFloat() / maxSide
        val w = (src.width * scale).toInt().coerceAtLeast(1)
        val h = (src.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, w, h, true)
    }

    private fun persist(
        title: String, artist: String, playing: Boolean,
        art: Bitmap?, musicPackage: String?, hasSession: Boolean,
        album: String = "", position: Long = 0L, duration: Long = -1L
    ) {
        getSharedPreferences("mw_prefs", Context.MODE_PRIVATE).edit()
            .putString("title",   title)
            .putString("artist",  artist)
            .putString("album",   album)
            .putBoolean("playing", playing)
            .putBoolean("has_session", hasSession)
            .putLong("position", position)
            .putLong("duration", duration)
            .apply { musicPackage?.let { putString("music_package", it) } }
            .apply()

        val artFile = File(filesDir, "album_art.png")
        if (art != null) {
            artExecutor.execute {
                try {
                    val tmp = File(filesDir, "album_art.tmp")
                    FileOutputStream(tmp).use { art.compress(Bitmap.CompressFormat.PNG, 90, it) }
                    if (artFile.exists()) artFile.delete()
                    tmp.renameTo(artFile)
                } catch (e: Exception) { e.printStackTrace() }
            }
        } else if (!hasSession) { artFile.delete() }

        sendBroadcast(Intent(this, MusicWidgetProvider::class.java).apply {
            action = MusicWidgetProvider.ACTION_UPDATE
        })
    }
}
