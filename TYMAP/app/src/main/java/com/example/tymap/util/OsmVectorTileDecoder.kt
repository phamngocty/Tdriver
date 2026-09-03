package com.example.tymap.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * Bộ giải mã Mapbox Vector Tile (.mvt) từ vector.openstreetmap.org
 * Chỉ trích xuất layer "streets" (tuyến đường), loại bỏ 100% nhãn tên, nhà cửa, địa hình.
 * Render trực tiếp thành Bitmap đen trắng tương phản cao tối ưu cho màn hình OLED đơn sắc.
 */
object OsmVectorTileDecoder {

    fun decodeMvtToBitmap(input: InputStream, size: Int = 256): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.BLACK)

        val paint = Paint().apply {
            color = Color.WHITE
            strokeWidth = 2f
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            isAntiAlias = false // Giữ nét mảnh 1-2px không bị mờ nhòe cho OLED
        }

        try {
            val rawBytes = input.readBytes()
            val data = try {
                GZIPInputStream(ByteArrayInputStream(rawBytes)).readBytes()
            } catch (e: Exception) {
                rawBytes
            }

            parseAndDrawStreets(data, canvas, paint, size.toFloat())
        } catch (e: Exception) {
            // Trường hợp lỗi giải mã, trả về tile đen
        }

        return bitmap
    }

    private fun parseAndDrawStreets(data: ByteArray, canvas: Canvas, paint: Paint, canvasSize: Float) {
        var pos = 0
        val len = data.size

        while (pos < len) {
            val tagPair = readVarint(data, pos)
            val tag = tagPair.first
            pos = tagPair.second
            val fn = (tag ushr 3).toInt()
            val wt = (tag and 0x7L).toInt()

            if (wt == 2) {
                val lenPair = readVarint(data, pos)
                val fieldLen = lenPair.first.toInt()
                pos = lenPair.second
                val fieldEnd = pos + fieldLen

                if (fn == 3) { // Layer
                    parseLayer(data, pos, fieldEnd, canvas, paint, canvasSize)
                }
                pos = fieldEnd
            } else if (wt == 0) {
                pos = readVarint(data, pos).second
            } else if (wt == 1) {
                pos += 8
            } else if (wt == 5) {
                pos += 4
            }
        }
    }

    private fun parseLayer(
        data: ByteArray,
        startPos: Int,
        endPos: Int,
        canvas: Canvas,
        paint: Paint,
        canvasSize: Float
    ) {
        var pos = startPos
        var layerName = ""
        var extent = 4096f
        val featureRanges = mutableListOf<Pair<Int, Int>>()

        while (pos < endPos) {
            val tagPair = readVarint(data, pos)
            val tag = tagPair.first
            pos = tagPair.second
            val fn = (tag ushr 3).toInt()
            val wt = (tag and 0x7L).toInt()

            if (wt == 2) {
                val lenPair = readVarint(data, pos)
                val fieldLen = lenPair.first.toInt()
                pos = lenPair.second
                val fieldEnd = pos + fieldLen

                if (fn == 1) { // name
                    layerName = String(data, pos, fieldLen, Charsets.ISO_8859_1)
                } else if (fn == 2) { // feature
                    featureRanges.add(Pair(pos, fieldEnd))
                }
                pos = fieldEnd
            } else if (wt == 0) {
                val vPair = readVarint(data, pos)
                pos = vPair.second
                if (fn == 5) { // extent
                    extent = vPair.first.toFloat()
                }
            } else if (wt == 1) {
                pos += 8
            } else if (wt == 5) {
                pos += 4
            }
        }

        // Chỉ quan tâm layer đường giao thông ("streets" hoặc "roads")
        if (layerName == "streets" || layerName == "roads") {
            val scale = canvasSize / extent
            for (range in featureRanges) {
                drawFeature(data, range.first, range.second, canvas, paint, scale)
            }
        }
    }

    private fun drawFeature(
        data: ByteArray,
        startPos: Int,
        endPos: Int,
        canvas: Canvas,
        paint: Paint,
        scale: Float
    ) {
        var pos = startPos
        var geomType = 0
        var geomStart = 0
        var geomEnd = 0

        while (pos < endPos) {
            val tagPair = readVarint(data, pos)
            val tag = tagPair.first
            pos = tagPair.second
            val fn = (tag ushr 3).toInt()
            val wt = (tag and 0x7L).toInt()

            if (wt == 0) {
                val vPair = readVarint(data, pos)
                pos = vPair.second
                if (fn == 3) {
                    geomType = vPair.first.toInt()
                }
            } else if (wt == 2) {
                val lenPair = readVarint(data, pos)
                val fieldLen = lenPair.first.toInt()
                pos = lenPair.second
                if (fn == 4) { // geometry packed
                    geomStart = pos
                    geomEnd = pos + fieldLen
                }
                pos += fieldLen
            } else if (wt == 1) {
                pos += 8
            } else if (wt == 5) {
                pos += 4
            }
        }

        // 2 = LineString (Đường sá)
        if (geomType == 2 && geomStart < geomEnd) {
            renderLineGeometry(data, geomStart, geomEnd, canvas, paint, scale)
        }
    }

    private fun renderLineGeometry(
        data: ByteArray,
        startPos: Int,
        endPos: Int,
        canvas: Canvas,
        paint: Paint,
        scale: Float
    ) {
        var pos = startPos
        var curX = 0
        var curY = 0
        val path = Path()
        var hasPoints = false

        while (pos < endPos) {
            val cmdPair = readVarint(data, pos)
            val cmdHdr = cmdPair.first.toInt()
            pos = cmdPair.second

            val cmd = cmdHdr and 0x7
            val count = cmdHdr ushr 3

            if (cmd == 1) { // MoveTo
                for (i in 0 until count) {
                    val dxPair = readVarint(data, pos)
                    pos = dxPair.second
                    val dyPair = readVarint(data, pos)
                    pos = dyPair.second

                    val dx = zigzagDecode(dxPair.first)
                    val dy = zigzagDecode(dyPair.first)
                    curX += dx
                    curY += dy

                    path.moveTo(curX * scale, curY * scale)
                    hasPoints = true
                }
            } else if (cmd == 2) { // LineTo
                for (i in 0 until count) {
                    val dxPair = readVarint(data, pos)
                    pos = dxPair.second
                    val dyPair = readVarint(data, pos)
                    pos = dyPair.second

                    val dx = zigzagDecode(dxPair.first)
                    val dy = zigzagDecode(dyPair.first)
                    curX += dx
                    curY += dy

                    path.lineTo(curX * scale, curY * scale)
                    hasPoints = true
                }
            } else if (cmd == 7) { // ClosePath
                path.close()
            }
        }

        if (hasPoints) {
            canvas.drawPath(path, paint)
        }
    }

    private fun readVarint(data: ByteArray, startPos: Int): Pair<Long, Int> {
        var result = 0L
        var shift = 0
        var pos = startPos
        while (pos < data.size) {
            val b = data[pos++].toLong()
            result = result or ((b and 0x7FL) shl shift)
            if ((b and 0x80L) == 0L) break
            shift += 7
        }
        return Pair(result, pos)
    }

    private fun zigzagDecode(n: Long): Int {
        return ((n ushr 1) xor -(n and 1L)).toInt()
    }
}
