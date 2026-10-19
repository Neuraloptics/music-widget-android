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
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        for (id in ids) renderWidget(context, manager, id)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        manager: AppWidgetManager,
        widgetId: Int,
        newOptions: Bundle
    ) {
        renderWidget(context, manager, widgetId)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_PLAY_PAUSE -> sendMediaKey(context, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            ACTION_NEXT       -> sendMediaKey(context, KeyEvent.KEYCODE_MEDIA_NEXT)
            ACTION_PREV       -> sendMediaKey(context, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            ACTION_UPDATE -> {
                val mgr = AppWidgetManager.getInstance(context)
                val ids = mgr.getAppWidgetIds(
                    ComponentName(context, MusicWidgetProvider::class.java)
                )
                for (id in ids) renderWidget(context, mgr, id)
            }
        }
    }

    private fun renderWidget(context: Context, manager: AppWidgetManager, id: Int) {
        val p       = context.getSharedPreferences("mw_prefs", Context.MODE_PRIVATE)
        val title   = p.getString("title",  "No music playing") ?: "No music playing"
        val artist  = p.getString("artist", "") ?: ""
        val playing = p.getBoolean("playing", false)

        // Layout responsivo segun el ancho del widget
        val options   = manager.getAppWidgetOptions(id)
        val minWidth  = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250)
        val layoutRes = if (minWidth < 200) R.layout.widget_music_small else R.layout.widget_music

        val v = RemoteViews(context.packageName, layoutRes)
        v.setTextViewText(R.id.songTitle,  title)
        v.setTextViewText(R.id.artistName, artist)
        v.setImageViewResource(R.id.btnPlayPause,
            if (playing) R.drawable.ic_pause else R.drawable.ic_play)

        // Album art
        val artFile = File(context.filesDir, "album_art.png")
        if (artFile.exists()) {
            BitmapFactory.decodeFile(artFile.absolutePath)?.let {
                v.setImageViewBitmap(R.id.albumArt, it)
            } ?: v.setImageViewResource(R.id.albumArt, R.drawable.ic_music_note)
        } else {
            v.setImageViewResource(R.id.albumArt, R.drawable.ic_music_note)
        }

        // Colores dinamicos Monet en API 31+
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

        // PendingIntents
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
