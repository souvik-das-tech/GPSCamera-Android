// Turns captures into the final photos and videos, and keeps them: each one goes into the
// app's own folder (shown in the in-app gallery) and into the phone's gallery, in the
// "Your GPS Camera" album.
package com.bbcl.gpscamera

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Media {
    const val ALBUM = APP_NAME

    /** Folder holding the app's own copies of photos and videos, shown in the in-app gallery. */
    fun photosDir(context: Context) = File(context.filesDir, "photos").apply { mkdirs() }

    /** The gallery thumbnail of a video in [photosDir]. */
    fun thumbFor(context: Context, video: File) = File(context.filesDir, "thumbs/${video.nameWithoutExtension}.jpg")

    fun isVideo(f: File) = f.extension == "mp4"

    /** The image to show for a photo or video in a grid. */
    fun coverImage(context: Context, f: File) = if (isVideo(f)) thumbFor(context, f) else f

    /** Newest first. */
    fun list(context: Context): List<File> = photosDir(context).listFiles().orEmpty()
        .filter { it.extension == "jpg" || isVideo(it) }
        .sortedByDescending { it.name }

    /** GPS_20261020_204600, the name of a photo or video taken at [t] (without extension). */
    fun fileName(t: Long): String = "GPS_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(t))

    /**
     * Makes the final photo from a camera JPEG: upright, with the stamp along the bottom and the
     * event badge in the top-left corner, and the position in the EXIF GPS tags. Blocking.
     */
    fun savePhoto(context: Context, jpeg: ByteArray, rotationDegrees: Int, s: StampInfo): File {
        val src = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, BitmapFactory.Options().apply { inMutable = true })
            ?: error("Could not read the camera image")
        var photo = if (rotationDegrees == 0) src else {
            Bitmap.createBitmap(src, 0, 0, src.width, src.height, Matrix().apply { postRotate(rotationDegrees.toFloat()) }, true)
                .also { if (it !== src) src.recycle() }
        }
        if (!photo.isMutable) photo = photo.copy(Bitmap.Config.ARGB_8888, true)

        val canvas = Canvas(photo)
        val w = photo.width.toFloat()
        val stampH = stampHeight(s, w)
        canvas.save()
        canvas.translate(0f, photo.height - stampH)
        paintStamp(canvas, w, stampH, s)
        canvas.restore()
        paintEventBadge(canvas, w, s)

        val file = File(photosDir(context), fileName(s.time) + ".jpg")
        file.outputStream().use { photo.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        photo.recycle()
        writeExif(file, s)
        toGallery(context, file, video = false)
        return file
    }

    private fun writeExif(file: File, s: StampInfo) {
        val exif = ExifInterface(file)
        exif.setAttribute(
            ExifInterface.TAG_DATETIME_ORIGINAL,
            SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(Date(s.time)),
        )
        if (s.hasFix) {
            exif.setLatLong(s.latitude!!, s.longitude!!)
            s.altitude?.let(exif::setAltitude)
            exif.setAttribute(ExifInterface.TAG_GPS_MAP_DATUM, "WGS-84")
        }
        exif.saveAttributes()
    }

    /** Copies a finished photo or video into the phone's gallery. */
    fun toGallery(context: Context, file: File, video: Boolean) {
        val mime = if (video) "video/mp4" else "image/jpeg"
        val folder = if (video) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val collection = if (video) {
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            }
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "$folder/$ALBUM")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(collection, values) ?: error("Could not add to the gallery")
            resolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(folder), ALBUM).apply { mkdirs() }
            val target = File(dir, file.name)
            file.copyTo(target, overwrite = true)
            MediaScannerConnection.scanFile(context, arrayOf(target.path), arrayOf(mime), null)
        }
    }
}
