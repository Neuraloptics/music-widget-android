package com.musicwidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.view.KeyEvent
import android.widget.RemoteViews
import androidx.core.app.NotificationManagerCompat
import java.io.File

class MusicWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_PLAY_PAUSE = "com.musicwidget.PLAY_PAUSE"
        const val ACTION_NEXT       = "com.musicwidget.NEXT"
        const val ACTION_PREV       = "com.musicwidget.PREV"
        const val ACTION_UPDATE     = "com.musicwidget.UPDATE"
        const val ACTION_OPEN_APP   = "com.musicwidget.OPEN_APP"

        fun sendMediaKey(context: Context, keyCode: Int) {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val t  = SystemClock.uptimeMillis()
            am.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, keyCode, 0))
            am.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP,   keyCode, 0))
        }

        fun hasNotificationAccess(context: Context): Boolean =
            NotificationManagerCompat
                .getEnabledListenerPackages(context)
                .contains(context.packageName)

        fun launchMusicApp(context: Context, preferredPackage: String?) {
            val pm = context.packageManager
            val candidates = buildList {
                preferredPackage?.let { add(it) }
                addAll(listOf(
                    "com.google.android.apps.youtube.music",
                    "app.revanced.android.apps.youtube.music",
                    "com.vanced.android.apps.youtube.music",
                    "com.spotify.music",
                    "com.dd3boh.outertune",
                    "com.dd3boh.outertune.debug",
                    "app.opentune", "com.opentune",
                    "code.name.monkey.retromusic",
                    "com.maxmpz.audioplayer",
                    "org.videolan.vlc"
                ))
            }.distinct()
            for (pkg in candidates) {
                try {
                    pm.getLaunchIntentForPackage(pkg)?.let { intent ->
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                        return
                    }
                } catch (e: Exception) { continue }
            }
            try {
                context.startActivity(Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_APP_MUSIC)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        for (id in ids) renderWidget(context, manager, id)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context, manager: AppWidgetManager, widgetId: Int, newOptions: android.os.Bundle
    ) {
        renderWidget(context, manager, widgetId)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val prefs = context.getSharedPreferences("mw_prefs", Context.MODE_PRIVATE)
        when (intent.action) {
            ACTION_PLAY_PAUSE -> {
                val hasSession = prefs.getBoolean("has_session", false)
                val ctrl = MusicListenerService.activeController

                if (ctrl != null && hasSession) {
                    // FIX: TransportControls en vez de KeyEvent
                    // KeyEvent puede hacer que la app salte al frente — TransportControls no
                    try {
                        val state = ctrl.playbackState?.state
                        if (state == PlaybackState.STATE_PLAYING) {
                            ctrl.transportControls.pause()
                        } else {
                            ctrl.transportControls.play()
                        }
                    } catch (e: Exception) {
                        // controller invalidado, fallback
                        sendMediaKey(context, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                    }
                } else {
                    sendMediaKey(context, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                    if (!hasSession) {
                        launchMusicApp(context, prefs.getString("music_package", null))
                    }
                }
            }
            ACTION_NEXT     -> sendMediaKey(context, KeyEvent.KEYCODE_MEDIA_NEXT)
            ACTION_PREV     -> sendMediaKey(context, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            ACTION_OPEN_APP -> launchMusicApp(context, prefs.getString("music_package", null))
            ACTION_UPDATE   -> {
                val mgr = AppWidgetManager.getInstance(context)
                val ids = mgr.getAppWidgetIds(ComponentName(context, MusicWidgetProvider::class.java))
                for (id in ids) renderWidget(context, mgr, id)
            }
        }
    }

    private fun renderWidget(context: Context, manager: AppWidgetManager, id: Int) {
        val options   = manager.getAppWidgetOptions(id)
        val minWidth  = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250)
        val layoutRes = if (minWidth < 200) R.layout.widget_music_small else R.layout.widget_music
        val v = RemoteViews(context.packageName, layoutRes)

        v.setOnClickPendingIntent(R.id.widgetRoot, null)

        if (!hasNotificationAccess(context)) {
            v.setTextViewText(R.id.songTitle,  "tap para activar")
            v.setTextViewText(R.id.artistName, "necesita acceso a notificaciones")
            val pi = PendingIntent.getActivity(
                context, 99,
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            v.setOnClickPendingIntent(R.id.widgetRoot, pi)
            manager.updateAppWidget(id, v)
            return
        }

        val p       = context.getSharedPreferences("mw_prefs", Context.MODE_PRIVATE)
        val title   = p.getString("title",  "No music playing") ?: "No music playing"
        val artist  = p.getString("artist", "") ?: ""
        val playing = p.getBoolean("playing", false)

        v.setTextViewText(R.id.songTitle,  title)
        v.setTextViewText(R.id.artistName, artist)
        v.setImageViewResource(R.id.btnPlayPause,
            if (playing) R.drawable.ic_pause else R.drawable.ic_play)

        val artFile = File(context.filesDir, "album_art.png")
        if (artFile.exists()) {
            BitmapFactory.decodeFile(artFile.absolutePath)?.let {
                v.setImageViewBitmap(R.id.albumArt, it)
            } ?: v.setImageViewResource(R.id.albumArt, R.drawable.ic_music_note)
        } else {
            v.setImageViewResource(R.id.albumArt, R.drawable.ic_music_note)
        }

        val openAppPi = PendingIntent.getBroadcast(
            context, 3,
            Intent(context, MusicWidgetProvider::class.java).apply { action = ACTION_OPEN_APP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        v.setOnClickPendingIntent(R.id.albumArt, openAppPi)

        if (Build.VERSION.SDK_INT >= 31) {
            val textPrimary   = context.getColor(android.R.color.system_neutral1_50)
            val textSecondary = context.getColor(android.R.color.system_neutral2_300)
            val accent        = context.getColor(android.R.color.system_accent1_200)
            val accentOn      = context.getColor(android.R.color.system_accent1_900)
            v.setTextColor(R.id.songTitle,  textPrimary)
            v.setTextColor(R.id.artistName, textSecondary)
            v.setInt(R.id.btnPrev,      "setColorFilter", accent)
            v.setInt(R.id.btnNext,      "setColorFilter", accent)
            v.setInt(R.id.btnPlayPause, "setColorFilter", accentOn)
        }

        fun pi(req: Int, action: String) = PendingIntent.getBroadcast(
            context, req,
            Intent(context, MusicWidgetProvider::class.java).apply { this.action = action },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        v.setOnClickPendingIntent(R.id.btnPlayPause, pi(0, ACTION_PLAY_PAUSE))
        v.setOnClickPendingIntent(R.id.btnPrev,      pi(1, ACTION_PREV))
        v.setOnClickPendingIntent(R.id.btnNext,      pi(2, ACTION_NEXT))

        manager.updateAppWidget(id, v)
    }
}
