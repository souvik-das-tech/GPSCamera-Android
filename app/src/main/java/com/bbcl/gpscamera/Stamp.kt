// The location stamp: a satellite mini map with a pin, and a dark box with the place, the
// address, the coordinates, the time and an optional note. The same drawing code paints
// the live preview over the camera and the stamp burned into photos and videos.
package com.bbcl.gpscamera

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

const val APP_NAME = "Your GPS Camera"
const val STAMP_ACCENT = 0xFFE53935.toInt()

/** Everything printed on a photo. */
data class StampInfo(
    val latitude: Double? = null,
    val longitude: Double? = null,
    val altitude: Double? = null, // metres above sea level
    val time: Long = System.currentTimeMillis(),
    val note: String = "",
    /** "Atrakhi, West Bengal, India" and "8F3P+JRG, Atrakhi, West Bengal 721149, India". */
    val title: String? = null,
    val addressLine: String? = null,
    val inIndia: Boolean = false,
    /** Satellite image centred on the position (null when offline). */
    val map: Bitmap? = null,
    /** The user's company, shown on the tag above the box instead of the app name. */
    val company: String = "",
    val logo: Bitmap? = null,
    /** The event the photo was taken at, shown on its own badge in the top-left corner. */
    val event: String = "",
) {
    val hasFix get() = latitude != null && longitude != null
    val hasBrand get() = company.isNotBlank() || logo != null
}

// ============== Formatting ==============

/** Tuesday, 20/10/2026 08:46 PM GMT +05:30 */
fun formatDateTime(t: Long): String {
    val off = TimeZone.getDefault().getOffset(t) / 60_000
    val sign = if (off < 0) "-" else "+"
    val mins = abs(off)
    val date = SimpleDateFormat("EEEE, dd/MM/yyyy hh:mm a", Locale.ENGLISH).format(Date(t))
    return "$date GMT $sign%02d:%02d".format(mins / 60, mins % 60)
}

/** Lat 22.304114° Long 87.487076° */
fun formatLatLong(lat: Double, lon: Double) = "Lat %.6f° Long %.6f°".format(Locale.US, lat, lon)

/** The stamp's text lines; the first one is the large title. */
fun stampLines(s: StampInfo): List<String> {
    val note = s.note.trim()
    if (!s.hasFix) {
        return listOfNotNull("Waiting for GPS…", formatDateTime(s.time), if (note.isNotEmpty()) "Note : $note" else null)
    }
    val lat = s.latitude!!
    val lon = s.longitude!!
    return listOfNotNull(
        if (s.title != null) s.title + if (s.inIndia) " 🇮🇳" else "" else formatLatLong(lat, lon),
        s.addressLine ?: encodePlusCode(lat, lon),
        if (s.title != null) formatLatLong(lat, lon) else null,
        formatDateTime(s.time),
        if (note.isNotEmpty()) "Note : $note" else null,
    )
}

// ============== Drawing ==============

/** A laid-out block of white, lightly shadowed text. */
private class Text(s: String, size: Float, weight: Int, maxWidth: Float, maxLines: Int = 2) {
    val layout: StaticLayout
    val width: Float
    val height: Float get() = layout.height.toFloat()

    init {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = size
            typeface = if (Build.VERSION.SDK_INT >= 28) Typeface.create(Typeface.DEFAULT, weight, false)
            else if (weight >= 600) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            setShadowLayer(size * 0.08f, 0f, 0f, 0x8A000000.toInt())
        }
        layout = StaticLayout.Builder.obtain(s, 0, s.length, paint, max(1, maxWidth.toInt()))
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setMaxLines(maxLines)
            .setEllipsize(TextUtils.TruncateAt.END)
            .setIncludePad(false)
            .setLineSpacing(size * 0.05f, 1f)
            .build()
        width = (0 until layout.lineCount).maxOfOrNull { layout.getLineWidth(it) } ?: 0f
    }

    fun draw(canvas: Canvas, x: Float, y: Float) {
        canvas.save()
        canvas.translate(x, y)
        layout.draw(canvas)
        canvas.restore()
    }
}

private class StampLayout(
    val unit: Float,
    val margin: Float,
    val mapSize: Float,
    val pad: Float,
    val gap: Float,
    val tagHeight: Float,
    val logoWidth: Float,
    val logoHeight: Float,
    val tag: Text,
    val lines: List<Text>,
    val boxHeight: Float,
    val height: Float,
)

private fun layout(s: StampInfo, width: Float): StampLayout {
    // Sizes scale with the width so the stamp looks the same on a preview and a photo.
    val u = width / 100
    val margin = u * 2.5f
    val mapSize = u * 25
    val pad = u * 2.2f
    val gap = u * 0.5f
    val textWidth = width - margin * 2 - mapSize - u * 1.5f - pad * 2
    val lines = stampLines(s).mapIndexed { i, t ->
        Text(t, u * if (i == 0) 4.6f else 3.2f, if (i == 0) 600 else 400, textWidth)
    }
    val textHeight = lines.sumOf { it.height.toDouble() }.toFloat() + gap * (lines.size - 1)
    val boxHeight = max(mapSize, textHeight + pad * 2)
    // The tag: the company's logo and name, or the app name when no branding is set.
    val logo = s.logo
    val logoHeight = if (logo == null) 0f else u * 7
    val logoWidth = if (logo == null) 0f else min(logoHeight * logo.width / logo.height, u * 20)
    val tag = if (s.hasBrand) {
        Text(s.company.trim(), u * 3.6f, 700, width - margin * 2 - logoWidth - u * 6, maxLines = 1)
    } else {
        Text(APP_NAME, u * 2.6f, 500, width, maxLines = 1)
    }
    val tagHeight = max(tag.height, logoHeight) + u * 1.2f
    return StampLayout(
        u, margin, mapSize, pad, gap, tagHeight, logoWidth, logoHeight, tag, lines, boxHeight,
        tagHeight + u + boxHeight + margin,
    )
}

/** Height of the stamp area for a picture [width] pixels wide. */
fun stampHeight(s: StampInfo, width: Float): Float = layout(s, width).height

private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

private fun fill(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }

/** Paints the stamp across the bottom of a [width] x [height] area (transparent around the boxes). */
fun paintStamp(canvas: Canvas, width: Float, height: Float, s: StampInfo) {
    val l = layout(s, width)
    val u = l.unit
    val bottom = height - l.margin
    val boxTop = bottom - l.boxHeight
    val radius = u * 1.6f

    // Mini map, bottom left.
    val mapRect = RectF(l.margin, bottom - l.mapSize, l.margin + l.mapSize, bottom)
    canvas.save()
    canvas.clipPath(Path().apply { addRoundRect(mapRect, radius, radius, Path.Direction.CW) })
    val map = s.map
    if (map != null) {
        canvas.drawBitmap(map, null, mapRect, bitmapPaint)
    } else {
        canvas.drawRect(mapRect, fill(0xFF2E3B33.toInt()))
        val t = Text(if (s.hasFix) "Map needs\ninternet" else "No GPS", u * 2.6f, 500, l.mapSize - u * 2)
        t.draw(canvas, mapRect.centerX() - t.width / 2, mapRect.centerY() - t.height / 2 - u * 4)
    }
    canvas.restore()

    // Red pin at the position (its tip on the map's centre).
    if (s.hasFix) {
        val tipX = mapRect.centerX()
        val tipY = mapRect.centerY()
        val r = u * 2.6f
        val headY = tipY - r * 2.4f
        val pin = Path().apply {
            moveTo(tipX, tipY)
            lineTo(tipX - r * 0.86f, headY + r * 0.5f)
            // Over the top of the head, from 150° round to 30°.
            arcTo(RectF(tipX - r, headY - r, tipX + r, headY + r), 150f, 240f)
            close()
        }
        canvas.drawPath(pin, fill(STAMP_ACCENT).apply { setShadowLayer(u * 0.6f, 0f, u * 0.2f, 0x99000000.toInt()) })
        canvas.drawCircle(tipX, headY, r * 0.4f, fill(0xFF7F0000.toInt()))
    }

    // Text box, right of the map.
    val boxLeft = mapRect.right + u * 1.5f
    canvas.drawRoundRect(RectF(boxLeft, boxTop, width - l.margin, bottom), radius, radius, fill(0x8C000000.toInt()))
    var y = boxTop + l.pad
    for (t in l.lines) {
        t.draw(canvas, boxLeft + l.pad, y)
        y += t.height + l.gap
    }

    // Company (or app) tag above the box's right edge: the logo on a white card, then the name.
    val logoGap = if (l.logoWidth > 0 && l.tag.width > 0) u * 1.2f else 0f
    val tagRect = RectF(
        width - l.margin - l.logoWidth - logoGap - l.tag.width - u * 2.4f,
        boxTop - u - l.tagHeight,
        width - l.margin,
        boxTop - u,
    )
    canvas.drawRoundRect(tagRect, u, u, fill(0x73000000))
    val logo = s.logo
    if (logo != null) {
        val logoRect = RectF(
            tagRect.left + u * 1.2f,
            tagRect.centerY() - l.logoHeight / 2,
            tagRect.left + u * 1.2f + l.logoWidth,
            tagRect.centerY() + l.logoHeight / 2,
        )
        val card = RectF(logoRect).apply { inset(-u * 0.3f, -u * 0.3f) }
        canvas.drawRoundRect(card, u * 0.8f, u * 0.8f, fill(Color.WHITE))
        canvas.drawBitmap(logo, Rect(0, 0, logo.width, logo.height), logoRect, bitmapPaint)
    }
    l.tag.draw(canvas, tagRect.left + u * 1.2f + l.logoWidth + logoGap, tagRect.centerY() - l.tag.height / 2)
}

// ============== Event badge ==============

/** The "EVENT" label over the name, for a picture [width] wide (null when there is no event). */
private fun eventLayout(s: StampInfo, width: Float): Triple<Text, Text, Float>? {
    val event = s.event.trim()
    if (event.isEmpty()) return null
    val u = width / 100
    val maxWidth = width * 0.55f
    return Triple(Text("EVENT", u * 2.4f, 700, maxWidth, maxLines = 1), Text(event, u * 4.4f, 700, maxWidth), u)
}

/** Height of the strip along the top that holds the event badge (0 when there is no event). */
fun eventStripHeight(s: StampInfo, width: Float): Float {
    val (label, name, u) = eventLayout(s, width) ?: return 0f
    return u * 2.5f + u * 1.8f + label.height + u * 0.4f + name.height + u * 1.8f
}

/** Paints the event badge in the top-left corner (transparent elsewhere). */
fun paintEventBadge(canvas: Canvas, width: Float, s: StampInfo) {
    val (label, name, u) = eventLayout(s, width) ?: return
    val margin = u * 2.5f
    val pad = u * 1.8f
    val bar = u * 1.0f
    val w = max(label.width, name.width) + pad * 2 + bar
    val h = pad + label.height + u * 0.4f + name.height + pad
    val box = RectF(margin, margin, margin + w, margin + h)
    val r = u * 1.6f
    canvas.drawRoundRect(box, r, r, fill(0x99000000.toInt()))
    // Red stripe down the left edge, so the badge reads as the stamp's own.
    canvas.save()
    canvas.clipPath(Path().apply { addRoundRect(box, r, r, Path.Direction.CW) })
    canvas.drawRect(margin, margin, margin + bar, margin + h, fill(STAMP_ACCENT))
    canvas.restore()
    val x = margin + bar + pad
    label.draw(canvas, x, margin + pad)
    name.draw(canvas, x, margin + pad + label.height + u * 0.4f)
}

/** Draws the stamp (and event badge) for a picture [width] wide into a transparent bitmap. */
fun renderStamp(s: StampInfo, width: Int): Bitmap {
    val h = ceil(stampHeight(s, width.toFloat())).toInt()
    return Bitmap.createBitmap(width, h, Bitmap.Config.ARGB_8888).also {
        paintStamp(Canvas(it), width.toFloat(), h.toFloat(), s)
    }
}

fun renderEventStrip(s: StampInfo, width: Int): Bitmap? {
    val h = ceil(eventStripHeight(s, width.toFloat())).toInt()
    if (h == 0) return null
    return Bitmap.createBitmap(width, h, Bitmap.Config.ARGB_8888).also { paintEventBadge(Canvas(it), width.toFloat(), s) }
}
