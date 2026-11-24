package io.github.guanhui999.fakelocgum.core

import java.util.Locale
import kotlin.math.abs
import kotlin.math.round

/** Rewrite coordinates, NOT satellite signal/fix validity. Keep real GSA/GSV and quality fields. */
object NmeaCodec {
    fun rewrite(sentence: String, latitude: Double, longitude: Double,
                speedMps: Float? = null, bearing: Float? = null, altitude: Double? = null): String {
        if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return sentence
        val raw = sentence.trimEnd('\r', '\n')
        if (!raw.startsWith('$')) return sentence
        val body = raw.substring(1).substringBefore('*')
        val fields = body.split(',').toMutableList()
        val type = fields.firstOrNull()?.takeLast(3) ?: return sentence
        fun coordinates(latIndex: Int, lonIndex: Int): Boolean {
            if (fields.size <= lonIndex + 1) return false
            fields[latIndex] = degreesMinutes(latitude, 2)
            fields[latIndex + 1] = if (latitude < 0) "S" else "N"
            fields[lonIndex] = degreesMinutes(longitude, 3)
            fields[lonIndex + 1] = if (longitude < 0) "W" else "E"
            return true
        }
        when (type) {
            "GGA", "GNS" -> {
                if (!coordinates(2, 4)) return sentence
                if (altitude?.isFinite() == true && fields.size > 9) fields[9] = String.format(Locale.US, "%.1f", altitude)
            }
            "RMC" -> {
                if (!coordinates(3, 5)) return sentence
                if (speedMps?.isFinite() == true && fields.size > 7) fields[7] = String.format(Locale.US, "%.2f", speedMps * 1.94384449)
                if (bearing?.isFinite() == true && fields.size > 8) fields[8] = String.format(Locale.US, "%.1f", bearing)
            }
            "GLL" -> if (!coordinates(1, 3)) return sentence
            "VTG" -> {
                if (fields.size < 9 || speedMps?.isFinite() != true) return sentence
                fields[5] = String.format(Locale.US, "%.2f", speedMps * 1.94384449)
                fields[7] = String.format(Locale.US, "%.2f", speedMps * 3.6)
                if (bearing?.isFinite() == true) fields[1] = String.format(Locale.US, "%.1f", bearing)
            }
            else -> return sentence
        }
        val changed = fields.joinToString(",")
        var checksum = 0
        for (c in changed) checksum = checksum xor c.code
        val suffix = sentence.substring(raw.length)
        return "$" + changed + "*" + String.format(Locale.US, "%02X", checksum) + suffix
    }

    private fun degreesMinutes(degrees: Double, width: Int): String {
        val minutes = round(abs(degrees) * 60 * 100_000) / 100_000
        val whole = (minutes / 60).toInt()
        val remaining = minutes - whole * 60
        return whole.toString().padStart(width, '0') + String.format(Locale.US, "%08.5f", remaining)
    }
}
