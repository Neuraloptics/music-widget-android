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
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.util.SizeF
import android.view.KeyEvent
import android.view.View
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
        const val ACTION_SHUFFLE    = "com.musicwidget.SHUFFLE"
        const val ACTION_REPEAT     = "com.musicwidget.REPEAT"

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

        // setShuffleMode/setRepeatMode via reflection: no rompe build en APIs viejas
        private fun applyShuffle(context: Context, mode: Int) {
            val ctrl = MusicListenerService.activeController ?: return
            try {
                val tc = ctrl.transportControls
                tc.javaClass
                    .getMethod("setShuffleMode", java.lang.Integer.TYPE)
                    .invoke(tc, mode)
            } catch (e: Exception) { /* el player no lo soporta */ }
        }

        private fun applyRepeat(context: Context, mode: Int) {
            val ctrl = MusicListenerService.activeController ?: return
            try {
                val tc = ctrl.transportControls
                tc.javaClass
                    .getMethod("setRepeatMode", java.lang.Integer.TYPE)
                    .invoke(tc, mode)
            } catch (e: Exception) { /* el player no lo soporta */ }
        }

        fun launchMusicApp(context: Context, preferredPackage: String?) {
            val pm = context.packageManager
            val candidates = buildList {
                preferredPackage?.let { add(it) }
                addAll(listOf(
                    "com.prism.music",
                    "com.google.android.apps.youtube.music",
                    "app.revanced.android.apps.youtube.music",
                    "com.vanced.android.apps.youtube.music",
                    "com.spotify.music",
                    "com.dd3boh.outertune",
                    "com.dd3boh.outertune.debug",
                    "app.opentune",
                    "com.opentune",
                    "com.tana404.opentune",
                    "com.abhiram.opentune",
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
            // último recurso: preguntar al sistema qué app de música hay
            try {
                val probe = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MUSIC)
                for (info in pm.queryIntentActivities(probe, 0)) {
                    val pkg = info.activityInfo.packageName
                    pm.getLaunchIntentForPackage(pkg)?.let { intent ->
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                        return
                    }
                }
            } catch (e: Exception) { }
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
                val hasSession = prefs.getBoolean("has_session", false)
                val ctrl = MusicListenerService.activeController
                if (ctrl != null && hasSession) {
                    try {
                        val state = ctrl.playbackState?.state
                        if (state == PlaybackState.STATE_PLAYING) {
                            ctrl.transportControls.pause()
                        } else {
                            ctrl.transportControls.play()
                        }
                    } catch (e: Exception) {
                        sendMediaKey(context, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                    }
                } else {
                    // sin sesión activa no hay a quién mandarle la tecla:
                    // abrimos directamente la app de música
                    launchMusicApp(context, prefs.getString("music_package", null))
                }
            }
            ACTION_NEXT     -> sendMediaKey(context, KeyEvent.KEYCODE_MEDIA_NEXT)
            ACTION_PREV     -> sendMediaKey(context, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            ACTION_OPEN_APP -> launchMusicApp(context, prefs.getString("music_package", null))
            ACTION_SHUFFLE  -> {
                val next = !prefs.getBoolean("shuffle", false)
                prefs.edit().putBoolean("shuffle", next).apply()
                applyShuffle(context, if (next) 1 else 0)
                refreshWidgets(context)
            }
            ACTION_REPEAT   -> {
                val next = (prefs.getInt("repeat", 0) + 1) % 3
                prefs.edit().putInt("repeat", next).apply()
                applyRepeat(context, next)
                refreshWidgets(context)
            }
            ACTION_UPDATE   -> refreshWidgets(context)
        }
    }

    private fun refreshWidgets(context: Context) {
        val mgr = AppWidgetManager.getInstance(context)
        val ids = mgr.getAppWidgetIds(ComponentName(context, MusicWidgetProvider::class.java))
        for (id in ids) renderWidget(context, mgr, id)
    }

    private fun renderWidget(context: Context, manager: AppWidgetManager, id: Int) {
        // pantalla de permiso: siempre en layout full independiente del tamaño
        if (!hasNotificationAccess(context)) {
            val v = RemoteViews(context.packageName, R.layout.widget_music)
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

        if (Build.VERSION.SDK_INT >= 31) {
            // API 31+: RemoteViews responsivo con múltiples layouts
            val views = RemoteViews(mapOf(
                SizeF(100f,  50f) to buildViews(context, R.layout.widget_music_micro),
                SizeF(160f,  55f) to buildViews(context, R.layout.widget_music_small),
                SizeF(300f,  60f) to buildViews(context, R.layout.widget_music_small),
                SizeF(250f, 110f) to buildViews(context, R.layout.widget_music),
                SizeF(340f, 120f) to buildViews(context, R.layout.widget_music)
            ))
            manager.updateAppWidget(id, views)
        } else {
            val options   = manager.getAppWidgetOptions(id)
            val minWidth  = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250)
            val minHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 100)
            val layoutRes = when {
                minWidth  < 140               -> R.layout.widget_music_micro
                minWidth  < 250 || minHeight < 90 -> R.layout.widget_music_small
                else                          -> R.layout.widget_music
            }
            manager.updateAppWidget(id, buildViews(context, layoutRes))
        }
    }

    private fun buildViews(context: Context, layoutRes: Int): RemoteViews {
        val v = RemoteViews(context.packageName, layoutRes)
        val p       = context.getSharedPreferences("mw_prefs", Context.MODE_PRIVATE)
        val title   = p.getString("title",  "No music playing") ?: "No music playing"
        val artist  = p.getString("artist", "") ?: ""
        val album   = p.getString("album",  "") ?: ""
        val playing = p.getBoolean("playing", false)
        val shuffle = p.getBoolean("shuffle", false)
        val repeat  = p.getInt("repeat", 0)
        val duration = p.getLong("duration", -1L)
        val position = p.getLong("position", 0L)

        val subtitle = when {
            artist.isBlank() -> album
            album.isBlank() || album == artist -> artist
            else -> "$artist • $album"
        }

        v.setOnClickPendingIntent(R.id.widgetRoot, null)
        v.setTextViewText(R.id.songTitle,  title)
        v.setTextViewText(R.id.artistName, subtitle)
        v.setImageViewResource(R.id.btnPlayPause,
            if (playing) R.drawable.ic_pause else R.drawable.ic_play)
        v.setImageViewResource(R.id.btnRepeat,
            if (repeat == 2) R.drawable.ic_repeat_one else R.drawable.ic_repeat)

        // barra ondulada: snapshot (sin timer). Si no hay duración se muestra la onda vacía.
        v.setViewVisibility(R.id.songProgress, View.VISIBLE)
        if (duration > 0) {
            val max = duration.toInt().coerceAtLeast(1)
            val prog = position.coerceIn(0L, duration).toInt()
            v.setProgressBar(R.id.songProgress, max, prog, false)
        } else {
            v.setProgressBar(R.id.songProgress, 100, 0, false)
        }

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

        val accent: Int
        val accentOn: Int
        val dim: Int
        if (Build.VERSION.SDK_INT >= 31) {
            val textPrimary   = context.getColor(android.R.color.system_neutral1_50)
            val textSecondary = context.getColor(android.R.color.system_neutral2_300)
            accent   = context.getColor(android.R.color.system_accent1_200)
            accentOn = context.getColor(android.R.color.system_accent1_900)
            dim      = textSecondary
            v.setTextColor(R.id.songTitle,  textPrimary)
            v.setTextColor(R.id.artistName, textSecondary)
        } else {
            accent   = 0xFFFFFFFF.toInt()
            accentOn = 0xFF000000.toInt()
            dim      = 0x66FFFFFF
        }
        v.setInt(R.id.btnPrev,      "setColorFilter", accentOn)
        v.setInt(R.id.btnNext,      "setColorFilter", accentOn)
        v.setInt(R.id.btnPlayPause, "setColorFilter", accentOn)
        v.setInt(R.id.btnShuffle,   "setColorFilter", if (shuffle) accent else dim)
        v.setInt(R.id.btnRepeat,    "setColorFilter", if (repeat != 0) accent else dim)

        fun pi(req: Int, action: String) = PendingIntent.getBroadcast(
            context, req,
            Intent(context, MusicWidgetProvider::class.java).apply { this.action = action },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        v.setOnClickPendingIntent(R.id.btnPlayPause, pi(0, ACTION_PLAY_PAUSE))
        v.setOnClickPendingIntent(R.id.btnPrev,      pi(1, ACTION_PREV))
        v.setOnClickPendingIntent(R.id.btnNext,      pi(2, ACTION_NEXT))
        v.setOnClickPendingIntent(R.id.btnShuffle,   pi(4, ACTION_SHUFFLE))
        v.setOnClickPendingIntent(R.id.btnRepeat,    pi(5, ACTION_REPEAT))

        // tocar el texto también abre la app (más fácil en pantallas chicas)
        v.setOnClickPendingIntent(R.id.songTitle,  openAppPi)
        v.setOnClickPendingIntent(R.id.artistName, openAppPi)

        // accesibilidad / user friendly
        v.setContentDescription(R.id.btnPlayPause, if (playing) "Pausar" else "Reproducir")
        v.setContentDescription(R.id.btnPrev, "Anterior")
        v.setContentDescription(R.id.btnNext, "Siguiente")
        v.setContentDescription(R.id.btnShuffle,
            if (shuffle) "Aleatorio activado" else "Aleatorio desactivado")
        v.setContentDescription(R.id.btnRepeat, when (repeat) {
            1 -> "Repetir todo"
            2 -> "Repetir una canción"
            else -> "Repetir desactivado"
        })
        v.setContentDescription(R.id.albumArt, "Abrir app de música")

        return v
    }
}
