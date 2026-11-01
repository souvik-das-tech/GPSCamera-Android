// Open Location Code ("plus code") encoding, per the open specification at
// https://github.com/google/open-location-code. A plus code names a ~3 m square anywhere on
// Earth, e.g. 7MJF8F3P+JRG; next to a town name the first four digits can be dropped
// ("8F3P+JRG, Atrakhi").
package com.bbcl.gpscamera

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToLong

private const val ALPHABET = "23456789CFGHJMPQRVWX"

// Integer precision of the full 15-digit code, which keeps the arithmetic exact.
private const val LAT_MULTIPLIER = 8000L * 3125 // 20^3 * 5^5
private const val LNG_MULTIPLIER = 8000L * 1024 // 20^3 * 4^5

/** The full plus code with [digits] digits (10 = ~14 m, 11 = ~3 m). */
fun encodePlusCode(latitude: Double, longitude: Double, digits: Int = 11): String {
    val lat = latitude.coerceIn(-90.0, 90.0)
    var lng = longitude
    while (lng < -180) lng += 360
    while (lng >= 180) lng -= 360
    var latVal = toLong(lat * LAT_MULTIPLIER) + 90 * LAT_MULTIPLIER
    var lngVal = toLong(lng * LNG_MULTIPLIER) + 180 * LNG_MULTIPLIER
    // The north pole belongs to the cell just below it.
    if (latVal >= 180 * LAT_MULTIPLIER) latVal = 180 * LAT_MULTIPLIER - 1

    val out = CharArray(15)
    // Grid digits (11th-15th): 5 rows x 4 columns per step, least significant first.
    for (i in 14 downTo 10) {
        out[i] = ALPHABET[((latVal % 5) * 4 + lngVal % 4).toInt()]
        latVal /= 5
        lngVal /= 4
    }
    // Pair digits (1st-10th): latitude and longitude alternately, base 20.
    for (i in 4 downTo 0) {
        out[i * 2 + 1] = ALPHABET[(lngVal % 20).toInt()]
        out[i * 2] = ALPHABET[(latVal % 20).toInt()]
        latVal /= 20
        lngVal /= 20
    }
    val code = String(out, 0, digits)
    return code.substring(0, 8) + "+" + code.substring(8)
}

/**
 * Floor of [v], treating values a hair below a whole number as that number (66.89 * 25e6
 * comes out as 1672249999.9999998, which must count as 1672250000).
 */
private fun toLong(v: Double): Long {
    val r = v.roundToLong()
    return if (abs(v - r) < 1e-6) r else floor(v).toLong()
}

/** The code without its first four digits, to be shown next to a nearby place name. */
fun shortPlusCode(latitude: Double, longitude: Double) = encodePlusCode(latitude, longitude).substring(4)
