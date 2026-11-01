// The satellite mini map: Esri World Imagery tiles around the position, stitched into one
// square image with the position at its centre.
package com.bbcl.gpscamera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin

const val MAP_ZOOM = 17 // about 1 m per pixel in India: houses, roads and fields are visible
private const val TILE = 256

/** Side of the stitched image in pixels. */
const val MAP_IMAGE_SIZE = 512

const val MAP_ATTRIBUTION = "Esri, Maxar, Earthstar Geographics, and the GIS User Community"

private fun tileUrl(z: Int, x: Int, y: Int) =
    "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/$z/$y/$x"

/** World pixel position (Web Mercator) of a point at [zoom]. */
fun worldPixel(lat: Double, lon: Double, zoom: Int): Pair<Double, Double> {
    val scale = TILE * 2.0.pow(zoom)
    val x = (lon + 180) / 360 * scale
    val s = sin(lat * PI / 180)
    val y = (0.5 - ln((1 + s) / (1 - s)) / (4 * PI)) * scale
    return x to y
}

class MiniMapLoader {
    private val tiles = object : LinkedHashMap<String, Bitmap>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?) = size > 60
    }
    private val pool = Executors.newFixedThreadPool(4)

    private fun fetchTile(z: Int, x: Int, y: Int): Bitmap? {
        val key = "$z/$x/$y"
        synchronized(tiles) { tiles[key]?.let { return it } }
        val conn = URL(tileUrl(z, x, y)).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        conn.setRequestProperty("User-Agent", "GPSCameraOnline/1.0 (Android)")
        try {
            if (conn.responseCode != 200) return null
            val bmp = conn.inputStream.use(BitmapFactory::decodeStream) ?: return null
            synchronized(tiles) { tiles[key] = bmp }
            return bmp
        } finally {
            conn.disconnect()
        }
    }

    /** A [MAP_IMAGE_SIZE]-pixel square of imagery centred on the point, or null when offline. Blocking. */
    fun load(lat: Double, lon: Double): Bitmap? = try {
        val (px, py) = worldPixel(lat, lon, MAP_ZOOM)
        val left = px - MAP_IMAGE_SIZE / 2
        val top = py - MAP_IMAGE_SIZE / 2
        val x0 = floor(left / TILE).toInt()
        val x1 = floor((left + MAP_IMAGE_SIZE) / TILE).toInt()
        val y0 = floor(top / TILE).toInt()
        val y1 = floor((top + MAP_IMAGE_SIZE) / TILE).toInt()
        val jobs = buildList {
            for (x in x0..x1) for (y in y0..y1) add(Triple(x, y, pool.submit(Callable { fetchTile(MAP_ZOOM, x, y) })))
        }
        val got = jobs.map { (x, y, f) -> Triple(x, y, f.get()) }
        if (got.any { it.third == null }) {
            null
        } else {
            val out = Bitmap.createBitmap(MAP_IMAGE_SIZE, MAP_IMAGE_SIZE, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(out)
            val paint = Paint(Paint.FILTER_BITMAP_FLAG)
            for ((x, y, img) in got) {
                canvas.drawBitmap(img!!, (x * TILE - left).toFloat(), (y * TILE - top).toFloat(), paint)
            }
            out
        }
    } catch (_: Exception) {
        null // offline or the tile server is unreachable
    }
}
