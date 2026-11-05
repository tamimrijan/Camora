package com.tamimrijan.camora.data

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class MediaItem(
    val id: Long,
    val uri: Uri,
    val name: String,
    val dateTaken: Long,
    val width: Int,
    val height: Int,
    val mime: String,
    val sizeBytes: Long,
)

/**
 * Photos are stored locally through MediaStore in DCIM/Camora, so they also appear in
 * Samsung Gallery like any camera output. Nothing is uploaded. Files the app created can
 * be read and deleted without any storage permission on Android 10+.
 */
class MediaRepository(private val context: Context) {
    private val resolver get() = context.contentResolver

    /** Bumped whenever media changes so the gallery and thumbnail refresh. */
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version

    fun notifyChanged() {
        _version.value++
    }

    private val collection get() = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    suspend fun saveFile(file: File, displayName: String, mime: String, dateTaken: Long = System.currentTimeMillis()): Uri =
        withContext(Dispatchers.IO) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_DIR)
                put(MediaStore.MediaColumns.DATE_TAKEN, dateTaken)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(collection, values) ?: error("MediaStore insert failed")
            try {
                resolver.openOutputStream(uri, "w")!!.use { out -> file.inputStream().use { it.copyTo(out, 1 shl 16) } }
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } catch (t: Throwable) {
                resolver.delete(uri, null, null)
                throw t
            }
            notifyChanged()
            uri
        }

    suspend fun query(): List<MediaItem> = withContext(Dispatchers.IO) {
        val out = ArrayList<MediaItem>()
        val proj = arrayOf(
            MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.DATE_TAKEN,
            MediaStore.MediaColumns.DATE_ADDED, MediaStore.MediaColumns.WIDTH, MediaStore.MediaColumns.HEIGHT,
            MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.SIZE,
        )
        resolver.query(
            collection, proj, "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?", arrayOf("$RELATIVE_DIR%"),
            "${MediaStore.MediaColumns.DATE_ADDED} DESC",
        )?.use { c ->
            val iId = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val iName = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val iTaken = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_TAKEN)
            val iAdded = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
            val iW = c.getColumnIndexOrThrow(MediaStore.MediaColumns.WIDTH)
            val iH = c.getColumnIndexOrThrow(MediaStore.MediaColumns.HEIGHT)
            val iMime = c.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            val iSize = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            while (c.moveToNext()) {
                val id = c.getLong(iId)
                val taken = c.getLong(iTaken).takeIf { it > 0 } ?: (c.getLong(iAdded) * 1000)
                out += MediaItem(
                    id, ContentUris.withAppendedId(collection, id), c.getString(iName) ?: "",
                    taken, c.getInt(iW), c.getInt(iH), c.getString(iMime) ?: "image/jpeg", c.getLong(iSize),
                )
            }
        }
        out.sortByDescending { it.dateTaken }
        out
    }

    /**
     * Deletes photos. Returns an IntentSender when Android needs the user's confirmation
     * (files created by an earlier install of the app are no longer "owned" by it).
     */
    suspend fun delete(items: List<MediaItem>): IntentSender? = withContext(Dispatchers.IO) {
        val failed = ArrayList<Uri>()
        for (it in items) {
            try {
                resolver.delete(it.uri, null, null)
            } catch (e: SecurityException) {
                failed += it.uri
            }
        }
        notifyChanged()
        if (failed.isNotEmpty() && Build.VERSION.SDK_INT >= 30) MediaStore.createDeleteRequest(resolver, failed).intentSender else null
    }

    companion object {
        const val RELATIVE_DIR = "DCIM/Camora"
        private val fmt = SimpleDateFormat("yyyyMMdd HHmmss", Locale.US)

        @Synchronized
        fun newName(suffix: String = ""): String = "Camora ${fmt.format(Date())}$suffix"
    }
}
