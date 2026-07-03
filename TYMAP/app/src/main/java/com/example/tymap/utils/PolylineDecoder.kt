package com.example.tymap.utils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.osmdroid.util.GeoPoint

object PolylineDecoder {
    /**
     * Decodes an encoded HTTP polyline string into a list of GeoPoints.
     * Used by OSRM and GraphHopper (if points_encoded=true).
     */
    /**
     * Decodes an encoded HTTP polyline string into a list of GeoPoints.
     * @param precision 5 for standard (Google/OSRM), 6 for Valhalla/Mapbox
     */
    suspend fun decode(encoded: String, precision: Int = 5): List<GeoPoint> = withContext(Dispatchers.Default) {
        val poly = ArrayList<GeoPoint>()
        var index = 0
        val len = encoded.length
        var lat = 0
        var lng = 0
        val factor = Math.pow(10.0, precision.toDouble())

        while (index < len) {
            var b: Int
            var shift = 0
            var result = 0
            do {
                b = encoded[index++].code - 63
                result = result or (b and 0x1f shl shift)
                shift += 5
            } while (b >= 0x20)
            val dlat = if (result and 1 != 0) (result shr 1).inv() else result shr 1
            lat += dlat

            shift = 0
            result = 0
            do {
                b = encoded[index++].code - 63
                result = result or (b and 0x1f shl shift)
                shift += 5
            } while (b >= 0x20)
            val dlng = if (result and 1 != 0) (result shr 1).inv() else result shr 1
            lng += dlng

            val p = GeoPoint(lat.toDouble() / factor, lng.toDouble() / factor)
            poly.add(p)
        }
        poly
    }

    fun simplify(points: List<Pair<Double, Double>>, tolerance: Double): List<Pair<Double, Double>> {
        if (points.size < 3) return points

        val keep = BooleanArray(points.size) { false }
        keep[0] = true
        keep[points.size - 1] = true

        val stack = java.util.Stack<Pair<Int, Int>>()
        stack.push(Pair(0, points.size - 1))

        while (!stack.isEmpty()) {
            val step = stack.pop()
            val start = step.first
            val end = step.second

            if (end - start < 2) continue

            var maxDist = 0.0
            var index = start

            for (i in (start + 1) until end) {
                val dist = perpendicularDistance(points[i], points[start], points[end])
                if (dist > maxDist) {
                    maxDist = dist
                    index = i
                }
            }

            if (maxDist > tolerance) {
                keep[index] = true
                stack.push(Pair(start, index))
                stack.push(Pair(index, end))
            }
        }

        val result = ArrayList<Pair<Double, Double>>()
        for (i in points.indices) {
            if (keep[i]) {
                result.add(points[i])
            }
        }
        return result
    }

    private fun perpendicularDistance(pt: Pair<Double, Double>, lineStart: Pair<Double, Double>, lineEnd: Pair<Double, Double>): Double {
        val dx = lineEnd.second - lineStart.second
        val dy = lineEnd.first - lineStart.first

        if (dx == 0.0 && dy == 0.0) {
            return Math.hypot(pt.second - lineStart.second, pt.first - lineStart.first)
        }

        val t = ((pt.second - lineStart.second) * dx + (pt.first - lineStart.first) * dy) / (dx * dx + dy * dy)
        return if (t < 0.0) {
            Math.hypot(pt.second - lineStart.second, pt.first - lineStart.first)
        } else if (t > 1.0) {
            Math.hypot(pt.second - lineEnd.second, pt.first - lineEnd.first)
        } else {
            val closestX = lineStart.second + t * dx
            val closestY = lineStart.first + t * dy
            Math.hypot(pt.second - closestX, pt.first - closestY)
        }
    }
}
