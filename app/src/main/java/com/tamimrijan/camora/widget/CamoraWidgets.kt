package com.tamimrijan.camora.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Size
import android.view.View
import android.widget.RemoteViews
import com.tamimrijan.camora.CamoraApp
import com.tamimrijan.camora.MainActivity
import com.tamimrijan.camora.R
import com.tamimrijan.camora.camera.CameraMode
import kotlinx.coroutines.runBlocking
import java.util.concurrent.Executors

/** How widgets and app shortcuts open Camora: a camera mode, or a screen. */
object Launch {
    const val ACTION = "com.tamimrijan.camora.action.LAUNCH"
    const val EXTRA_MODE = "camora.mode"
    const val EXTRA_OPEN = "camora.open"
    const val OPEN_LATEST = "latest"
    const val OPEN_GALLERY = "gallery"

    fun intent(ctx: Context, mode: CameraMode? = null, open: String? = null): Intent =
        Intent(ctx, MainActivity::class.java).apply {
            // A distinct action per target keeps the PendingIntents apart.
            action = "$ACTION.${mode?.name ?: open ?: "CAMERA"}"
            mode?.let { putExtra(EXTRA_MODE, it.name) }
            open?.let { putExtra(EXTRA_OPEN, it) }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }

    fun pending(ctx: Context, code: Int, mode: CameraMode? = null, open: String? = null): PendingIntent =
        PendingIntent.getActivity(ctx, code, intent(ctx, mode, open), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
}

/** 1x1: a shutter button that opens the camera ready to shoot. */
class QuickShotWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        val v = RemoteViews(ctx.packageName, R.layout.widget_quick)
        v.setOnClickPendingIntent(android.R.id.background, Launch.pending(ctx, 10, CameraMode.PHOTO))
        mgr.updateAppWidget(ids, v)
    }
}

/** 1x1: straight into the front camera. */
class SelfieWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        val v = RemoteViews(ctx.packageName, R.layout.widget_selfie)
        v.setOnClickPendingIntent(android.R.id.background, Launch.pending(ctx, 11, CameraMode.SELFIE))
        mgr.updateAppWidget(ids, v)
    }
}

/** 4x1: one tap into Portrait, Photo, Selfie or Pro. */
class ModesWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        val v = RemoteViews(ctx.packageName, R.layout.widget_modes)
        v.setOnClickPendingIntent(R.id.mode_portrait, Launch.pending(ctx, 12, CameraMode.PORTRAIT))
        v.setOnClickPendingIntent(R.id.mode_photo, Launch.pending(ctx, 13, CameraMode.PHOTO))
        v.setOnClickPendingIntent(R.id.mode_selfie, Launch.pending(ctx, 14, CameraMode.SELFIE))
        v.setOnClickPendingIntent(R.id.mode_pro, Launch.pending(ctx, 15, CameraMode.PRO))
        mgr.updateAppWidget(ids, v)
    }
}

/** 2x2: the newest Camora photo (tap to view) with a shutter button. */
class LastPhotoWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        val done = goAsync()
        Widgets.worker.execute {
            try { Widgets.renderLastPhoto(ctx.applicationContext, mgr, ids) } finally { done.finish() }
        }
    }
}

object Widgets {
    internal val worker = Executors.newSingleThreadExecutor()

    /** Called whenever Camora's photos change (new photo, edit, delete). */
    fun refreshLastPhoto(ctx: Context) {
        val app = ctx.applicationContext
        val mgr = AppWidgetManager.getInstance(app) ?: return
        val ids = runCatching { mgr.getAppWidgetIds(ComponentName(app, LastPhotoWidget::class.java)) }.getOrNull()
        if (ids == null || ids.isEmpty()) return
        worker.execute { runCatching { renderLastPhoto(app, mgr, ids) } }
    }

    internal fun renderLastPhoto(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        val latest = runCatching { runBlocking { CamoraApp.instance.media.query() }.firstOrNull() }.getOrNull()
        val bmp = latest?.let { runCatching { ctx.contentResolver.loadThumbnail(it.uri, Size(480, 480), null) }.getOrNull() }
        val v = RemoteViews(ctx.packageName, R.layout.widget_last)
        if (bmp != null) {
            v.setImageViewBitmap(R.id.last_photo, bmp)
            v.setViewVisibility(R.id.last_photo, View.VISIBLE)
            v.setViewVisibility(R.id.last_empty, View.GONE)
            v.setOnClickPendingIntent(android.R.id.background, Launch.pending(ctx, 20, open = Launch.OPEN_LATEST))
        } else {
            v.setViewVisibility(R.id.last_photo, View.GONE)
            v.setViewVisibility(R.id.last_empty, View.VISIBLE)
            v.setTextViewText(R.id.last_empty_text, "No photos yet")
            v.setOnClickPendingIntent(android.R.id.background, Launch.pending(ctx, 20, CameraMode.PHOTO))
        }
        v.setOnClickPendingIntent(R.id.last_shutter, Launch.pending(ctx, 21, CameraMode.PHOTO))
        mgr.updateAppWidget(ids, v)
    }
}