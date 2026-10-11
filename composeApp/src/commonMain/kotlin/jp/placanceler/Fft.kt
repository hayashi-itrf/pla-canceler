package jp.placanceler

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

internal fun fft(real: FloatArray, imag: FloatArray) {
    val n = real.size
    var j = 0
    for (i in 1 until n) {
        var bit = n shr 1
        while (j and bit != 0) {
            j = j xor bit
            bit = bit shr 1
        }
        j = j xor bit
        if (i < j) {
            val tmpRe = real[i]
            real[i] = real[j]
            real[j] = tmpRe
            val tmpIm = imag[i]
            imag[i] = imag[j]
            imag[j] = tmpIm
        }
    }

    var len = 2
    while (len <= n) {
        val angle = -2.0 * PI / len
        val wLenRe = cos(angle).toFloat()
        val wLenIm = sin(angle).toFloat()
        var i = 0
        while (i < n) {
            var wRe = 1f
            var wIm = 0f
            val half = len / 2
            for (k in 0 until half) {
                val uRe = real[i + k]
                val uIm = imag[i + k]
                val vIndex = i + k + half
                val vRe = real[vIndex] * wRe - imag[vIndex] * wIm
                val vIm = real[vIndex] * wIm + imag[vIndex] * wRe
                real[i + k] = uRe + vRe
                imag[i + k] = uIm + vIm
                real[vIndex] = uRe - vRe
                imag[vIndex] = uIm - vIm
                val nextRe = wRe * wLenRe - wIm * wLenIm
                wIm = wRe * wLenIm + wIm * wLenRe
                wRe = nextRe
            }
            i += len
        }
        len = len shl 1
    }
}

internal fun floorMod(value: Int, modulus: Int): Int {
    val remainder = value % modulus
    return if (remainder < 0) remainder + modulus else remainder
}
