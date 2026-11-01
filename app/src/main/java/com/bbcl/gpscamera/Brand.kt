// What the user adds to the stamp of every photo and video: their company's name and logo, the
// current event's name and a free note. Set once after installing (the app asks on first launch)
// and changed any time from the top bar. The text is kept in the preferences, the logo as a file
// in the app's folder.
package com.bbcl.gpscamera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import java.io.File
import kotlin.math.max

data class Brand(
    val company: String = "",
    val logoPath: String? = null,
    val event: String = "",
    val note: String = "",
) {
    val isEmpty get() = company.isEmpty() && logoPath == null && event.isEmpty() && note.isEmpty()
}

private fun prefs(context: Context) = context.getSharedPreferences("brand", Context.MODE_PRIVATE)

fun loadBrand(context: Context): Brand {
    val p = prefs(context)
    val path = p.getString("brandLogo", null)
    return Brand(
        company = p.getString("brandCompany", "")!!,
        event = p.getString("brandEvent", "")!!,
        note = p.getString("note", "")!!,
        logoPath = path?.takeIf { File(it).exists() },
    )
}

/** Whether the first-launch branding prompt has been answered. */
fun brandPromptShown(context: Context) = prefs(context).getBoolean("brandAsked", false)

fun markBrandPromptShown(context: Context) = prefs(context).edit().putBoolean("brandAsked", true).apply()

/** Saves the branding and deletes a replaced logo file. */
fun saveBrand(context: Context, b: Brand) {
    val p = prefs(context)
    val old = p.getString("brandLogo", null)
    p.edit()
        .putString("brandCompany", b.company)
        .putString("brandEvent", b.event)
        .putString("note", b.note)
        .apply { if (b.logoPath == null) remove("brandLogo") else putString("brandLogo", b.logoPath) }
        .apply()
    if (old != null && old != b.logoPath) File(old).delete()
}

/**
 * Copies a picked image into the app's folder, downscaled (the stamp never draws the logo
 * larger than this), under a new name each time so an old cached bitmap is never shown.
 */
fun importLogo(context: Context, uri: Uri): String? = try {
    val bmp: Bitmap = if (Build.VERSION.SDK_INT >= 28) {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { d, info, _ ->
            val big = max(info.size.width, info.size.height)
            if (big > 600) d.setTargetSize(info.size.width * 600 / big, info.size.height * 600 / big)
            d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    } else {
        context.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it) }
    }
    val file = File(context.filesDir, "logo_${System.currentTimeMillis()}.png")
    file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    file.path
} catch (_: Exception) {
    null
}

/** Decodes the logo for drawing on the stamp (null if it is missing or unreadable). */
fun decodeLogo(path: String?): Bitmap? = path?.let { runCatching { BitmapFactory.decodeFile(it) }.getOrNull() }
