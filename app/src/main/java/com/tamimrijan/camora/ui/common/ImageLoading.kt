package com.tamimrijan.camora.ui.common

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.LruCache
import android.util.Size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object ImageLoading {
    private val thumbs = object : LruCache<String, Bitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }

    suspend fun thumbnail(ctx: Context, uri: Uri, px: Int = 320): Bitmap? {
        val key = "$uri@$px"
        thumbs.get(key)?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching { ctx.contentResolver.loadThumbnail(uri, Size(px, px), null) }.getOrNull()?.also { thumbs.put(key, it) }
        }
    }

    fun invalidate() = thumbs.evictAll()

    /**
     * Decodes an image (content:// or file://) upright (EXIF-aware) and downsampled so its
     * long side is at most [maxSide]. [mutable] gives a software bitmap the engine can edit.
     */
    suspend fun decode(ctx: Context, uri: Uri, maxSide: Int, mutable: Boolean = false): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            val src = if (uri.scheme == "file") ImageDecoder.createSource(File(uri.path!!)) else ImageDecoder.createSource(ctx.contentResolver, uri)
            ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
                val long = maxOf(info.size.width, info.size.height)
                if (long > maxSide) {
                    val s = maxSide.toFloat() / long
                    decoder.setTargetSize(maxOf(1, (info.size.width * s).toInt()), maxOf(1, (info.size.height * s).toInt()))
                }
                if (mutable) {
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    decoder.isMutableRequired = true
                }
            }.let { b -> if (mutable && b.config != Bitmap.Config.ARGB_8888) b.copy(Bitmap.Config.ARGB_8888, true) else b }
        }.getOrNull()
    }

    /** Full-size dimensions of an image without decoding it. */
    suspend fun size(ctx: Context, uri: Uri): Size? = withContext(Dispatchers.IO) {
        runCatching {
            var out: Size? = null
            val src = if (uri.scheme == "file") ImageDecoder.createSource(File(uri.path!!)) else ImageDecoder.createSource(ctx.contentResolver, uri)
            ImageDecoder.decodeBitmap(src) { d, info, _ ->
                out = info.size
                d.setTargetSize(1, 1)
            }
            out
        }.getOrNull()
    }
}

@Composable
fun rememberThumbnail(uri: Uri, px: Int = 320): State<Bitmap?> {
    val ctx = LocalContext.current
    return produceState<Bitmap?>(null, uri) { value = ImageLoading.thumbnail(ctx, uri, px) }
}

@Composable
fun rememberDecoded(uri: Uri?, maxSide: Int): State<Bitmap?> {
    val ctx = LocalContext.current
    return produceState<Bitmap?>(null, uri, maxSide) { value = uri?.let { ImageLoading.decode(ctx, it, maxSide) } }
}
