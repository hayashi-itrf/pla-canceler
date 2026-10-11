package jp.placanceler

import kotlin.math.PI
import kotlin.math.sin

data class AcousticPath(
    val delaySamples: Int,
    val gain: Float,
)

/**
 * 開始時に短い音を鳴らし、スピーカーからマイクへ戻るまでの遅れと強さを測る。
 * この遅れがずれると、逆位相が逆に音を大きくする。
 */
class LoopbackProbe(val sampleRate: Int) {
    val playLength: Int = sampleRate * 2 / 5
    val recordLength: Int = playLength + sampleRate / 4
    val play: FloatArray = FloatArray(playLength)
    val record: FloatArray = FloatArray(recordLength)

    private val burstStart: Int = sampleRate / 20
    private val burstLength: Int = sampleRate / 12

    init {
        var phase = 0.0
        val tone = 1400.0
        for (i in 0 until burstLength) {
            phase += 2.0 * PI * tone / sampleRate
            val fade = fade(i, burstLength)
            play[burstStart + i] = (sin(phase) * 0.4 * fade).toFloat()
        }
    }

    fun correlate(): AcousticPath {
        var playEnergy = 0.0
        for (i in burstStart until burstStart + burstLength) {
            playEnergy += play[i] * play[i]
        }
        if (playEnergy < 1e-8) {
            return AcousticPath(sampleRate / 100, 1f)
        }

        val maxLag = (sampleRate / 4).coerceAtMost(recordLength - burstStart - burstLength)
        var bestLag = sampleRate / 80
        var bestCorrelation = 0.0
        var bestAbs = 0.0

        var lag = 0
        while (lag < maxLag) {
            val correlation = correlationAt(lag)
            val abs = kotlin.math.abs(correlation)
            if (abs > bestAbs) {
                bestAbs = abs
                bestCorrelation = correlation
                bestLag = lag
            }
            lag += 4
        }
        val refineFrom = (bestLag - 6).coerceAtLeast(0)
        val refineTo = (bestLag + 6).coerceAtMost(maxLag - 1)
        for (fine in refineFrom..refineTo) {
            val correlation = correlationAt(fine)
            val abs = kotlin.math.abs(correlation)
            if (abs > bestAbs) {
                bestAbs = abs
                bestCorrelation = correlation
                bestLag = fine
            }
        }

        val gain = (bestCorrelation / playEnergy).toFloat()
        val trusted = bestAbs > playEnergy * 0.05
        return if (trusted) {
            AcousticPath(bestLag.coerceAtLeast(1), gain.coerceIn(-4f, 4f))
        } else {
            AcousticPath(sampleRate / 100, 1f)
        }
    }

    private fun correlationAt(lag: Int): Double {
        var correlation = 0.0
        for (i in burstStart until burstStart + burstLength) {
            val j = i + lag
            if (j < record.size) {
                correlation += play[i] * record[j]
            }
        }
        return correlation
    }

    private fun fade(index: Int, length: Int): Double {
        val edge = (length * 0.15).toInt().coerceAtLeast(1)
        val window = when {
            index < edge -> index.toDouble() / edge
            index > length - edge -> (length - index).toDouble() / edge
            else -> 1.0
        }
        return window.coerceIn(0.0, 1.0)
    }
}
