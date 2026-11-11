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
import android.os.Handler
import android.os.SystemClock
import android.provider.Settings
import android.util.SizeF
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
        const val ACTION_SHUFFLE    = "com.musicwidget.SHUFFLE"
        const val ACTION_REPEAT     = "com.musicwidget.REPEAT"
        const val ACTION_TEST       = "com.musicwidget.TEST_INFLATE"
        const val ACTION_LIKE       = "com.musicwidget.LIKE"

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

        private fun ctrlInt(ctrl: android.media.session.MediaController?, method: String): Int? {
            if (ctrl == null) return null
            return try {
                ctrl.javaClass.getMethod(method).invoke(ctrl) as? Int
            } catch (e: Exception) { null }
        }

        private fun customActions(ctrl: android.media.session.MediaController?): List<String> {
            if (ctrl == null) return emptyList()
            return try {
                ctrl.playbackState?.customActions?.map { it.action } ?: emptyList()
            } catch (e: Exception) { emptyList() }
        }

        // Devuelve (accion para cambiar, estaLikeado) o null si el player no soporta like.
        private fun likeInfo(ctrl: android.media.session.MediaController?): Pair<String, Boolean>? {
            val names = customActions(ctrl).filter {
                it.contains("like", true) || it.contains("favorit", true) ||
                it.contains("fav", true) || it.contains("thumbs", true) ||
                it.contains("heart", true)
            }
            if (names.isEmpty()) return null
            val remove = names.firstOrNull {
                it.contains("remove", true) || it.contains("unlike", true) ||
                it.contains("dislike", true) || it.contains("unfavorite", true)
            }
            if (remove != null) return remove to true
            val add = names.firstOrNull {
                it.contains("add", true) || it.contains("like", true) ||
                it.contains("favorite", true) || it.contains("thumbs up", true)
            }
            return (add ?: names.first()) to false
        }

        // 1) custom action del player (Prism, etc). 2) setShuffleMode/setRepeatMode (Media3, Spotify, YTM).
        private fun applyShuffle(context: Context, mode: Int) {
            val ctrl = MusicListenerService.activeController ?: return
            val target = customActions(ctrl).firstOrNull { it.contains("shuffle", true) }
            if (target != null) {
                try { ctrl.transportControls.sendCustomAction(target, null); return } catch (e: Exception) { }
            }
            try {
                val tc = ctrl.transportControls
                tc.javaClass.getMethod("setShuffleMode", java.lang.Integer.TYPE).invoke(tc, mode)
            } catch (e: Exception) { }
        }

        private fun applyRepeat(context: Context, mode: Int) {
            val ctrl = MusicListenerService.activeController ?: return
            val target = customActions(ctrl).firstOrNull { it.contains("repeat", true) }
            if (target != null) {
                try { ctrl.transportControls.sendCustomAction(target, null); return } catch (e: Exception) { }
            }
            try {
                val tc = ctrl.transportControls
                tc.javaClass.getMethod("setRepeatMode", java.lang.Integer.TYPE).invoke(tc, mode)
            } catch (e: Exception) { }
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
                    // feedback inmediato del icono; el service confirma despues
                    prefs.edit().putBoolean("playing", !prefs.getBoolean("playing", false)).apply()
                    refreshWidgets(context)
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
                val cur = ctrlInt(MusicListenerService.activeController, "getShuffleMode")
                    ?.let { it != 0 } ?: prefs.getBoolean("shuffle", false)
                val next = !cur
                prefs.edit().putBoolean("shuffle", next).apply()
                applyShuffle(context, if (next) 1 else 0)
                refreshWidgets(context)
            }
            ACTION_REPEAT   -> {
                val cur = ctrlInt(MusicListenerService.activeController, "getRepeatMode")
                    ?: prefs.getInt("repeat", 0)
                val next = (cur + 1) % 3
                prefs.edit().putInt("repeat", next).apply()
                applyRepeat(context, next)
                refreshWidgets(context)
            }
            ACTION_LIKE     -> {
                val ctrl = MusicListenerService.activeController
                val info = likeInfo(ctrl)
                if (info != null) {
                    val newLiked = !info.second
                    try { ctrl?.transportControls?.sendCustomAction(info.first, null) } catch (e: Exception) { }
                    prefs.edit()
                        .putBoolean("liked", newLiked)
                        .putLong("liked_ts", System.currentTimeMillis())
                        .apply()
                    try {
                        java.io.File(context.filesDir, "like_debug.txt").writeText(
                            "sent=" + info.first + "
wasLiked=" + info.second +
                            "
newLiked=" + newLiked + "
custom=" + customActions(ctrl)
                        )
                    } catch (e: Exception) { }
                    refreshWidgets(context)
                    Handler(context.mainLooper).postDelayed({ refreshWidgets(context) }, 900)
                }
            }
            ACTION_UPDATE   -> refreshWidgets(context)
            ACTION_TEST     -> runInflateTest(context)
        }
    }

    private fun runInflateTest(context: Context) {
        val sb = StringBuilder()
        val layouts = listOf(
            R.layout.widget_music_small,
            R.layout.widget_music_wide,
            R.layout.widget_music_xwide,
            R.layout.widget_music_micro
        )
        for (res in layouts) {
            try {
                val v = buildViews(context, res)
                v.apply(context, android.widget.FrameLayout(context))
                sb.append("OK buildViews+apply res=").append(res).append("\n")
            } catch (t: Throwable) {
                sb.append("FAIL res=").append(res).append(": ")
                  .append(t.javaClass.name).append(": ").append(t.message).append("\n")
                sb.append(t.stackTraceToString()).append("\n")
            }
        }
        try {
            java.io.File(context.filesDir, "inflate_test.txt").writeText(sb.toString())
        } catch (t: Throwable) { }
    }

    private fun refreshWidgets(context: Context) {
        val mgr = AppWidgetManager.getInstance(context)
        val ids = mgr.getAppWidgetIds(ComponentName(context, MusicWidgetProvider::class.java))
        for (id in ids) renderWidget(context, mgr, id)
    }

    private fun renderWidget(context: Context, manager: AppWidgetManager, id: Int) {
        // pantalla de permiso: siempre en layout full independiente del tamaño
        if (!hasNotificationAccess(context)) {
            val v = RemoteViews(context.packageName, R.layout.widget_music_small)
            v.setTextViewText(R.id.songTitle,  "tap para activar")
            v.setTextViewText(R.id.artistName, "necesita acceso a notificaciones")
            v.setTextViewText(R.id.albumName, "")
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
                SizeF(120f, 55f) to buildViews(context, R.layout.widget_music_micro),
                SizeF(180f, 55f) to buildViews(context, R.layout.widget_music_small),
                SizeF(240f, 55f) to buildViews(context, R.layout.widget_music_wide),
                SizeF(300f, 55f) to buildViews(context, R.layout.widget_music_xwide)
            ))
            manager.updateAppWidget(id, views)
        } else {
            val options   = manager.getAppWidgetOptions(id)
            val minWidth  = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250)
            val layoutRes = when {
                minWidth < 155 -> R.layout.widget_music_micro
                minWidth < 215 -> R.layout.widget_music_small
                minWidth < 275 -> R.layout.widget_music_wide
                else           -> R.layout.widget_music_xwide
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
        val ctrl    = MusicListenerService.activeController
        val shuffle = ctrlInt(ctrl, "getShuffleMode")?.let { it != 0 } ?: p.getBoolean("shuffle", false)
        val repeat  = ctrlInt(ctrl, "getRepeatMode") ?: p.getInt("repeat", 0)

        v.setOnClickPendingIntent(R.id.widgetRoot, null)
        v.setTextViewText(R.id.songTitle,  title)
        v.setTextViewText(R.id.artistName, artist)
        v.setTextViewText(R.id.albumName,  album)
        v.setImageViewResource(R.id.btnPlayPause,
            if (playing) R.drawable.ic_pause else R.drawable.ic_play)
        v.setImageViewResource(R.id.btnRepeat,
            if (repeat == 2) R.drawable.ic_repeat_one else R.drawable.ic_repeat)

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
        v.setInt(R.id.btnPrev,      "setColorFilter", accent)
        v.setInt(R.id.btnNext,      "setColorFilter", accent)
        v.setInt(R.id.btnPlayPause, "setColorFilter", accentOn)
        v.setInt(R.id.btnShuffle,   "setColorFilter", if (shuffle) accent else dim)
        v.setInt(R.id.btnRepeat,    "setColorFilter", if (repeat != 0) accent else dim)
        v.setTextColor(R.id.albumName, dim)

        val like = likeInfo(ctrl)
        val likedTs = p.getLong("liked_ts", 0L)
        val overrideActive = (System.currentTimeMillis() - likedTs) in 0L..1500L
        val liked = if (overrideActive) p.getBoolean("liked", false)
                    else (like?.second ?: p.getBoolean("liked", false))
        if (like != null) {
            v.setImageViewResource(R.id.btnLike,
                if (liked) R.drawable.ic_heart_filled else R.drawable.ic_heart_outline)
            v.setInt(R.id.btnLike, "setColorFilter", if (liked) 0xFFFF6B8A.toInt() else dim)
            v.setOnClickPendingIntent(R.id.btnLike, PendingIntent.getBroadcast(
                context, 6,
                Intent(context, MusicWidgetProvider::class.java).apply { action = ACTION_LIKE },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        } else {
            v.setImageViewResource(R.id.btnLike, R.drawable.ic_heart_outline)
            v.setInt(R.id.btnLike, "setColorFilter", dim)
            v.setOnClickPendingIntent(R.id.btnLike, null)
        }

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
        v.setContentDescription(R.id.btnLike, when {
            like == null -> "Like no disponible"
            liked -> "Quitar like"
            else -> "Dar like"
        })

        return v
    }
}
