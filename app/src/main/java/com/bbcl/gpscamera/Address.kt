// Street address of a position, looked up online, formatted like
//   title: "Atrakhi, West Bengal, India"
//   line:  "8F3P+JRG, Atrakhi, West Bengal 721149, India"
// From Android's built-in geocoder first (Google's data, free), and when the phone has none or
// it finds nothing, from OpenStreetMap's free Nominatim service.
package com.bbcl.gpscamera

import android.content.Context
import android.location.Geocoder
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

data class Address(val title: String, val line: String, val countryCode: String)

private fun clean(s: String?): String? = s?.trim()?.takeIf { it.isNotEmpty() }

private fun format(
    lat: Double,
    lon: Double,
    place: String?,
    street: String?,
    subLocality: String?,
    state: String?,
    pin: String?,
    country: String?,
    countryCode: String?,
): Address? {
    val title = mutableListOf<String>()
    for (s in listOf(place, state, country)) if (s != null && s !in title) title += s
    if (title.isEmpty()) return null

    val line = mutableListOf(shortPlusCode(lat, lon))
    fun add(s: String?) {
        if (s != null && '+' !in s && s !in line) line += s
    }
    add(if (street == place) null else street)
    add(subLocality)
    add(place)
    add(if (state == null) pin else if (pin == null) state else "$state $pin")
    add(country)
    return Address(title.joinToString(", "), line.joinToString(", "), countryCode?.uppercase() ?: "")
}

@Suppress("DEPRECATION") // the async variant is API 33+; this runs on a background thread anyway
private fun android(context: Context, lat: Double, lon: Double): Address? {
    if (!Geocoder.isPresent()) return null
    val m = Geocoder(context, Locale("en", "IN")).getFromLocation(lat, lon, 1)?.firstOrNull() ?: return null
    val place = clean(m.locality) ?: clean(m.subLocality) ?: clean(m.subAdminArea)
    return format(
        lat, lon,
        place = place,
        // The street, or the plus code the geocoder sometimes returns as a "street".
        street = clean(m.thoroughfare) ?: clean(m.featureName),
        subLocality = clean(m.subLocality),
        state = clean(m.adminArea),
        pin = clean(m.postalCode),
        country = clean(m.countryName),
        countryCode = clean(m.countryCode),
    )
}

/**
 * Nominatim's usage policy: at most one request a second and an identifying User-Agent.
 * The app asks only when the position has moved 40 m or more.
 */
private fun nominatim(lat: Double, lon: Double): Address? {
    val q = mapOf(
        "format" to "jsonv2", "lat" to "$lat", "lon" to "$lon", "zoom" to "18",
        "addressdetails" to "1", "accept-language" to "en",
    ).entries.joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}" }
    val conn = URL("https://nominatim.openstreetmap.org/reverse?$q").openConnection() as HttpURLConnection
    conn.connectTimeout = 10_000
    conn.readTimeout = 10_000
    conn.setRequestProperty("User-Agent", "GPSCameraOnline/1.0 (Android; com.bbcl.gpscamera)")
    try {
        if (conn.responseCode != 200) return null
        val a = JSONObject(conn.inputStream.bufferedReader().readText()).optJSONObject("address") ?: return null
        fun f(key: String) = clean(a.optString(key).takeIf { a.has(key) })
        val road = f("road")
        val house = f("house_number")
        return format(
            lat, lon,
            place = f("village") ?: f("town") ?: f("city") ?: f("hamlet") ?: f("suburb") ?: f("county"),
            street = if (road == null) null else if (house == null) road else "$house, $road",
            subLocality = f("neighbourhood") ?: f("suburb"),
            state = f("state"),
            pin = f("postcode"),
            country = f("country"),
            countryCode = f("country_code"),
        )
    } finally {
        conn.disconnect()
    }
}

/** The address of a position, or null when offline or nothing is known there. Blocking. */
fun lookupAddress(context: Context, lat: Double, lon: Double): Address? {
    try {
        android(context, lat, lon)?.let { return it }
    } catch (_: Exception) {
        // No geocoder on this phone, or it failed: try OpenStreetMap.
    }
    return try {
        nominatim(lat, lon)
    } catch (_: Exception) {
        null
    }
}
