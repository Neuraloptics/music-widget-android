package com.musicwidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.KeyEvent
import android.widget.RemoteViews
import java.io.File

class MusicWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_PLAY_PAUSE = "com.musicwidget.PLAY_PAUSE"
        const val ACTION_NEXT       = "com.musicwidget.NEXT"
        const val ACTION_PREV       = "com.musicwidget.PREV"
        const val ACTION_UPDATE     = "com.musicwidget.UPDATE"

        fun sendMediaKey(context: Context, keyCode: Int) {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val t  = SystemClock.uptimeMillis()
            am.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, keyCode, 0))
            am.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP,   keyCode, 0))
        }

        fun hasNotificationAccess(context: Context): Boolean {
            val cn = ComponentName(context, MusicListenerService::class.java)
            val enabled = Settings.Secure.getString(
                context.contentResolver, "enabled_notification_listeners"
            ) ?: return false
            return enabled.contains(cn.flattenToString())
        }

        /** Intenta lanzar la ultima app de musica detectada, o un fallback conocido */
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
                    "app.opentune",
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
        }
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        for (id in ids) renderWidget(context, manager, id)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context, manager: AppWidgetManager, widgetId: Int, newOptions: Bundle
    ) {
        renderWidget(context, manager, widgetId)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val prefs = context.getSharedPreferences("mw_prefs", Context.MODE_PRIVATE)
        when (intent.action) {
            ACTION_PLAY_PAUSE -> {
                sendMediaKey(context, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                // Si no habia musica sonando, intentar revivir la app
                if (!prefs.getBoolean("playing", false)) {
                    launchMusicApp(context, prefs.getString("music_package", null))
                }
            }
            ACTION_NEXT -> sendMediaKey(context, KeyEvent.KEYCODE_MEDIA_NEXT)
            ACTION_PREV -> sendMediaKey(context, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            ACTION_UPDATE -> {
                val mgr = AppWidgetManager.getInstance(context)
                val ids = mgr.getAppWidgetIds(ComponentName(context, MusicWidgetProvider::class.java))
                for (id in ids) renderWidget(context, mgr, id)
            }
        }
    }

    private fun renderWidget(context: Context, manager: AppWidgetManager, id: Int) {
        val p       = context.getSharedPreferences("mw_prefs", Context.MODE_PRIVATE)
        val options  = manager.getAppWidgetOptions(id)
        val minWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250)
        val layoutRes = if (minWidth < 200) R.layout.widget_music_small else R.layout.widget_music
        val v = RemoteViews(context.packageName, layoutRes)

        // Sin permiso: widget guia al usuario
        if (!hasNotificationAccess(context)) {
            v.setTextViewText(R.id.songTitle,  "tap para activar")
            v.setTextViewText(R.id.artistName, "necesita acceso a notificaciones")
            val settingsIntent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val pi = PendingIntent.getActivity(
                context, 99, settingsIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            v.setOnClickPendingIntent(R.id.widgetRoot, pi)
            manager.updateAppWidget(id, v)
            return
        }

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

        // Monet en API 31+
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
