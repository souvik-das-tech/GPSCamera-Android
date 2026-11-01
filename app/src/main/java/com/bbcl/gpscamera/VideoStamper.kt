// Turns a recorded video into the final one. While recording, a [StampTrack] draws the stamp
// (with the same code as the photo stamp) each time its text or map changes, e.g. when the
// minute ticks over or the address arrives. Afterwards Media3 Transformer (on-device, free)
// burns those images into the frames, the position goes into the MP4 location atom, and a
// thumbnail is saved for the in-app gallery.
package com.bbcl.gpscamera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.container.Mp4LocationData
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.TextureOverlay
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.InAppMp4Muxer
import androidx.media3.transformer.Transformer
import com.google.common.collect.ImmutableList
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Width the stamp images are drawn at; the overlay scales them to the video's width. */
private const val STAMP_WIDTH = 1080

/** A stamp image, shown from [atUs] (microseconds into the video) until the next one. */
private class StampFrame(val atUs: Long, val png: String)

/** The stamp images of one recording, each with the time into the video it starts at. */
class StampTrack(context: Context) {
    private val dir = File(context.cacheDir, "stamps_${System.nanoTime()}").apply { mkdirs() }
    val started = System.currentTimeMillis()
    private val frames = mutableListOf<StampFrame>()
    private val drawer = Executors.newSingleThreadExecutor()
    private var lastKey: String? = null
    private var drawn = 0
    var latitude: Double? = null
        private set
    var longitude: Double? = null
        private set

    /** The stamp the recording started with; its event goes on the top-left badge throughout. */
    var first: StampInfo? = null
        private set

    /** Records the stamp shown [atMs] into the video; draws a new image only if it changed. */
    fun add(s: StampInfo, atMs: Long) {
        if (first == null) first = s
        if (s.hasFix && latitude == null) {
            latitude = s.latitude
            longitude = s.longitude
        }
        val key = stampLines(s).joinToString("\n") + "|${System.identityHashCode(s.map)}|${s.company}|${System.identityHashCode(s.logo)}"
        if (key == lastKey) return
        lastKey = key
        val n = drawn++
        drawer.execute {
            runCatching {
                val file = File(dir, "stamp_$n.png")
                val bmp = renderStamp(s, STAMP_WIDTH)
                file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bmp.recycle()
                synchronized(frames) { frames += StampFrame(atMs * 1000, file.path) }
            }
        }
    }

    /** Waits for the last stamp image, then burns the stamps into [input] and writes [output]. Blocking. */
    fun burnInto(context: Context, input: File, output: File, thumb: File) {
        drawer.shutdown()
        drawer.awaitTermination(1, TimeUnit.MINUTES)
        val event = first?.let { renderEventStrip(it, STAMP_WIDTH) }?.let { bmp ->
            File(dir, "event.png").also { f -> f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        }
        val list = synchronized(frames) { frames.sortedBy { it.atUs } }
        var error: Throwable? = null
        val done = CountDownLatch(1)
        // Transformer must be created and driven from a thread with a Looper.
        Handler(Looper.getMainLooper()).post {
            try {
                export(context, input, output, list, event?.path, latitude, longitude) { e ->
                    error = e
                    done.countDown()
                }
            } catch (e: Exception) {
                error = e
                done.countDown()
            }
        }
        done.await()
        error?.let { throw it }
        saveThumbnail(output, thumb)
    }

    fun discard() {
        drawer.shutdownNow()
        dir.deleteRecursively()
    }
}

/**
 * Covers the whole frame with a transparent image holding the stamp along the bottom and the
 * event badge along the top, so both land in the same place whatever way the overlay is scaled
 * to the frame.
 */
private class StampOverlay(
    private val frames: List<StampFrame>,
    private val event: String?,
    private val width: Int,
    private val height: Int,
) : BitmapOverlay() {
    private var shown = -1
    private var bitmap: Bitmap? = null

    override fun getBitmap(presentationTimeUs: Long): Bitmap {
        val i = frames.indexOfLast { it.atUs <= presentationTimeUs }.coerceAtLeast(0)
        if (i != shown || bitmap == null) {
            bitmap = render(frames[i].png)
            shown = i
        }
        return bitmap!!
    }

    private fun render(path: String): Bitmap {
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        event?.let(BitmapFactory::decodeFile)?.let { badge ->
            canvas.drawBitmap(badge, null, Rect(0, 0, width, badge.height * width / badge.width), paint)
            badge.recycle()
        }
        val panel = BitmapFactory.decodeFile(path) ?: return out
        val h = panel.height * width / panel.width
        canvas.drawBitmap(panel, null, Rect(0, height - h, width, height), paint)
        panel.recycle()
        return out
    }
}

/** Width and height of the video as it is displayed (rotation applied). */
private fun displaySize(path: String): Pair<Int, Int> {
    val r = MediaMetadataRetriever()
    try {
        r.setDataSource(path)
        val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)!!.toInt()
        val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)!!.toInt()
        val rotation = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        return if (rotation % 180 != 0) Pair(h, w) else Pair(w, h)
    } finally {
        r.release()
    }
}

private fun saveThumbnail(video: File, thumb: File) {
    val r = MediaMetadataRetriever()
    try {
        r.setDataSource(video.path)
        val frame = r.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return
        val w = 360
        val scaled = Bitmap.createScaledBitmap(frame, w, frame.height * w / frame.width, true)
        thumb.parentFile?.mkdirs()
        FileOutputStream(thumb).use { scaled.compress(Bitmap.CompressFormat.JPEG, 85, it) }
    } catch (_: Exception) {
        // The gallery shows a placeholder instead.
    } finally {
        r.release()
    }
}

/** Runs on the main thread; calls [done] (with the error, if any) when the export has finished. */
private fun export(
    context: Context,
    input: File,
    output: File,
    frames: List<StampFrame>,
    event: String?,
    lat: Double?,
    lon: Double?,
    done: (Throwable?) -> Unit,
) {
    val effects = if (frames.isEmpty()) {
        Effects.EMPTY
    } else {
        val (w, h) = displaySize(input.path)
        val overlay = OverlayEffect(ImmutableList.of<TextureOverlay>(StampOverlay(frames, event, w, h)))
        Effects(ImmutableList.of(), ImmutableList.of<Effect>(overlay))
    }
    val item = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(input))).setEffects(effects).build()
    val builder = Transformer.Builder(context)
        .setVideoMimeType(MimeTypes.VIDEO_H264)
        .addListener(object : Transformer.Listener {
            override fun onCompleted(composition: Composition, exportResult: ExportResult) = done(null)

            override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                output.delete()
                done(exportException)
            }
        })
    if (lat != null && lon != null) {
        builder.setMuxerFactory(InAppMp4Muxer.Factory { entries -> entries.add(Mp4LocationData(lat.toFloat(), lon.toFloat())) })
    }
    builder.build().start(item, output.path)
}
