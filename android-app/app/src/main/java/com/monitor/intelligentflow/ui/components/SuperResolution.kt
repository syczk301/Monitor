package com.monitor.intelligentflow.ui.components

import android.graphics.Bitmap

/**
 * Bitmap upscaler using bicubic interpolation + unsharp mask sharpening.
 * Pure Android implementation -- no native ML dependencies.
 */
class SuperResolution {

    val isAvailable: Boolean = true

    fun upscale(bitmap: Bitmap): Bitmap? {
        val w = bitmap.width
        val h = bitmap.height
        if (w < 8 || h < 8 || w > MAX_INPUT_DIM || h > MAX_INPUT_DIM) return null

        return try {
            val scaled = Bitmap.createScaledBitmap(bitmap, w * 2, h * 2, true)
            sharpen(scaled)
        } catch (_: Exception) {
            null
        }
    }

    fun release() {}

    private fun sharpen(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)

        val out = IntArray(w * h)

        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val idx = y * w + x
                val center = pixels[idx]

                var sumR = 0; var sumG = 0; var sumB = 0
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        if (dy == 0 && dx == 0) continue
                        val p = pixels[(y + dy) * w + (x + dx)]
                        sumR += (p shr 16) and 0xFF
                        sumG += (p shr 8) and 0xFF
                        sumB += p and 0xFF
                    }
                }
                val avgR = sumR / 8; val avgG = sumG / 8; val avgB = sumB / 8

                val cR = (center shr 16) and 0xFF
                val cG = (center shr 8) and 0xFF
                val cB = center and 0xFF

                val oR = (cR + ((cR - avgR) * STRENGTH).toInt()).coerceIn(0, 255)
                val oG = (cG + ((cG - avgG) * STRENGTH).toInt()).coerceIn(0, 255)
                val oB = (cB + ((cB - avgB) * STRENGTH).toInt()).coerceIn(0, 255)

                out[idx] = (0xFF shl 24) or (oR shl 16) or (oG shl 8) or oB
            }
        }

        for (x in 0 until w) {
            out[x] = pixels[x]
            out[(h - 1) * w + x] = pixels[(h - 1) * w + x]
        }
        for (y in 0 until h) {
            out[y * w] = pixels[y * w]
            out[y * w + w - 1] = pixels[y * w + w - 1]
        }

        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }

    companion object {
        private const val MAX_INPUT_DIM = 512
        private const val STRENGTH = 0.6f
    }
}
