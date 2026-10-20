package com.lampp.nmaxconnect.core.places

import com.lampp.nmaxconnect.core.location.GeoPoint
import kotlin.math.floor
import kotlin.math.pow

/**
 * Google Plus Codes (Open Location Code): Google's address for places without a street address,
 * e.g. "RH6J+F7M Camping Đồi Bù". The code is the coordinates themselves, so it gives Google's exact pin.
 * Short codes drop the first 4 characters (a ~1° cell) and need a reference point within ±0.5°.
 */
object PlusCode {
    private const val ALPHABET = "23456789CFGHJMPQRVWX"
    private const val SEPARATOR_AT = 8
    private const val PAIR_DIGITS = 10
    private const val GRID_ROWS = 5
    private const val GRID_COLS = 4
    private const val BASE = 20.0
    private val code = Regex("""(?<![0-9A-Za-z])((?:[$ALPHABET]{2}){2,4}\+[$ALPHABET]{2,3})(?![0-9A-Za-z])""")

    fun find(text: String): String? = code.find(text)?.value

    fun isFull(code: String) = code.indexOf('+') == SEPARATOR_AT

    /** Centre of a full code's cell (14 m for 10 digits, ~3 m for 11). */
    fun decode(code: String): GeoPoint {
        val digits = code.replace("+", "")
        var lat = -90.0
        var lng = -180.0
        var latCell = BASE * BASE
        var lngCell = BASE * BASE
        var i = 0
        while (i < PAIR_DIGITS && i < digits.length) {
            latCell /= BASE
            lngCell /= BASE
            lat += ALPHABET.indexOf(digits[i]) * latCell
            lng += ALPHABET.indexOf(digits[i + 1]) * lngCell
            i += 2
        }
        for (c in digits.drop(PAIR_DIGITS)) {
            latCell /= GRID_ROWS
            lngCell /= GRID_COLS
            lat += ALPHABET.indexOf(c) / GRID_COLS * latCell
            lng += ALPHABET.indexOf(c) % GRID_COLS * lngCell
        }
        return GeoPoint(lat + latCell / 2, lng + lngCell / 2)
    }

    /** The point matching a short [code] nearest to [reference] (Open Location Code `recoverNearest`). */
    fun recover(code: String, reference: GeoPoint): GeoPoint {
        val missing = SEPARATOR_AT - code.indexOf('+')
        if (missing <= 0) return decode(code)
        val size = BASE.pow(2 - missing / 2)
        val p = decode(prefix(reference, missing) + code)
        val lat = when {
            p.lat - reference.lat > size / 2 && p.lat - size >= -90 -> p.lat - size
            reference.lat - p.lat > size / 2 && p.lat + size <= 90 -> p.lat + size
            else -> p.lat
        }
        val lng = when {
            p.lng - reference.lng > size / 2 -> p.lng - size
            reference.lng - p.lng > size / 2 -> p.lng + size
            else -> p.lng
        }
        return GeoPoint(lat, lng)
    }

    private fun prefix(at: GeoPoint, length: Int): String = buildString {
        var lat = (at.lat + 90).coerceIn(0.0, 179.999_999)
        var lng = at.lng + 180
        var cell = BASE
        repeat(length / 2) {
            val a = floor(lat / cell).toInt()
            val b = floor(lng / cell).toInt()
            append(ALPHABET[a]).append(ALPHABET[b])
            lat -= a * cell
            lng -= b * cell
            cell /= BASE
        }
    }
}
