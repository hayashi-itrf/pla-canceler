package jp.placanceler

import kotlin.concurrent.Volatile
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

enum class ListenStatus {
    Idle,
    Measuring,
    Searching,
    Cancelling,
}

data class CancellerSnapshot(
    val status: ListenStatus = ListenStatus.Idle,
    val frequencyHz: Float = 0f,
    val frequenciesHz: List<Float> = emptyList(),
    val inputLevel: Float = 0f,
    val outputLevel: Float = 0f,
    val spectrum: List<Float> = List(SPECTRUM_BARS) { 0f },
    val delaySamples: Int = 0,
    val pathGain: Float = 0f,
    val sampleRate: Int = 0,
)

const val SPECTRUM_BARS = 32

/**
 * 高さがしばらく続く音を、最大3つまで同時に追って逆位相を作る。
 *
 * マイクには外の音と、自分のスピーカーの音が混ざる。
 * 開始時に測った遅れで自分の音を引き、残った音の山を FFT で拾う。
 * 自動モードでは、それぞれの高さの sin / cos を LMS で混ぜ、マイク位置の音が小さくなる向きに係数を動かす。
 * 数秒に一度だけ出力を止めて、その音がまだ続いているかを確認する。
 */
class TonalCanceller(val sampleRate: Int) {
    @Volatile var strength: Float = 0.75f
    @Volatile var phaseTrimDegrees: Float = 0f
    @Volatile var harmonicCount: Int = 3
    @Volatile var autoAdapt: Boolean = true
    @Volatile var invertPath: Boolean = false
    @Volatile var modelDelaySamples: Int = sampleRate / 50
    @Volatile var pathGain: Float = 1f

    private val maxTones = 3
    private val maxHarmonics = 2
    private val channels = maxTones * maxHarmonics
    private val delayLength = sampleRate.coerceAtLeast(256)
    private val sinHistory = Array(channels) { FloatArray(delayLength) }
    private val cosHistory = Array(channels) { FloatArray(delayLength) }
    private val playHistory = FloatArray(delayLength)
    private var historyPos = 0

    private val weightSin = FloatArray(channels)
    private val weightCos = FloatArray(channels)
    private val oscPhase = DoubleArray(channels)
    private val refPower = FloatArray(channels) { 0.05f }
    private val trackedHz = FloatArray(maxTones)
    private val toneLevel = FloatArray(maxTones)
    private val toneMiss = IntArray(maxTones)

    private val fftSize = 2048
    private val hop = 1024
    private val analysis = FloatArray(fftSize)
    private var analysisPos = 0
    private var seen = 0
    private val window = FloatArray(fftSize) { i ->
        (0.5 - 0.5 * cos(2.0 * PI * i / fftSize)).toFloat()
    }
    private val fftReal = FloatArray(fftSize)
    private val fftImag = FloatArray(fftSize)
    private val magnitudes = FloatArray(fftSize / 2)

    private var blockInput = 0f
    private var blockOutput = 0f
    private var spectrum = List(SPECTRUM_BARS) { 0f }

    private var samplesSinceProbe = 0
    private var probeLeft = 0

    fun snapshot(status: ListenStatus): CancellerSnapshot {
        val frequencies = (0 until maxTones)
            .filter { trackedHz[it] > 0f }
            .sortedByDescending { toneLevel[it] }
            .map { trackedHz[it] }
        val shown = when (status) {
            ListenStatus.Cancelling -> if (frequencies.isNotEmpty()) ListenStatus.Cancelling else ListenStatus.Searching
            else -> status
        }
        return CancellerSnapshot(
            status = shown,
            frequencyHz = frequencies.firstOrNull() ?: 0f,
            frequenciesHz = frequencies,
            inputLevel = blockInput.coerceIn(0f, 1f),
            outputLevel = blockOutput.coerceIn(0f, 1f),
            spectrum = spectrum,
            delaySamples = modelDelaySamples,
            pathGain = pathGain,
            sampleRate = sampleRate,
        )
    }

    fun resetWeights() {
        weightSin.fill(0f)
        weightCos.fill(0f)
        refPower.fill(0.05f)
    }

    fun reset() {
        resetWeights()
        oscPhase.fill(0.0)
        trackedHz.fill(0f)
        toneLevel.fill(0f)
        toneMiss.fill(0)
        historyPos = 0
        analysisPos = 0
        seen = 0
        playHistory.fill(0f)
        sinHistory.forEach { it.fill(0f) }
        cosHistory.forEach { it.fill(0f) }
        analysis.fill(0f)
        samplesSinceProbe = 0
        probeLeft = 0
        blockInput = 0f
        blockOutput = 0f
        spectrum = List(SPECTRUM_BARS) { 0f }
    }

    fun process(mic: FloatArray, out: FloatArray, length: Int = mic.size) {
        var inputEnergy = 0.0
        var outputEnergy = 0.0
        val userStrength = strength.coerceIn(0f, 1f)
        val trimRadians = phaseTrimDegrees * (PI.toFloat() / 180f)
        val delay = modelDelaySamples.coerceIn(1, delayLength - 1)
        val gain = pathGain
        val signedGain = (if (abs(gain) < 0.02f) 1f else gain) * if (invertPath) -1f else 1f
        val adapting = autoAdapt

        for (i in 0 until length) {
            val heard = mic[i]
            val echoIndex = floorMod(historyPos - delay, delayLength)
            val residual = heard - gain * playHistory[echoIndex]
            inputEnergy += residual * residual
            pushAnalysis(residual)

            val probing = probeLeft > 0
            if (probing) probeLeft--

            var sample = 0f
            val activeTones = (0 until maxTones).count { trackedHz[it] > 90f }
            if (activeTones > 0) {
                if (adapting) {
                    for (tone in 0 until maxTones) {
                        val freq = trackedHz[tone]
                        if (freq <= 90f) continue
                        val trimSamples = (phaseTrimDegrees / 360f * sampleRate / freq).toInt()
                        val totalDelay = (delay + trimSamples).coerceIn(1, delayLength - 1)
                        val harmonics = harmonicCount.coerceIn(1, maxHarmonics)
                        for (harmonic in 0 until harmonics) {
                            val harmonicHz = freq * (harmonic + 1)
                            if (harmonicHz >= sampleRate * 0.45f) break
                            val channel = tone * maxHarmonics + harmonic
                            oscPhase[channel] += 2.0 * PI * harmonicHz / sampleRate
                            if (oscPhase[channel] > TWO_PI) oscPhase[channel] -= TWO_PI
                            val sine = sin(oscPhase[channel]).toFloat()
                            val cosine = cos(oscPhase[channel]).toFloat()
                            sinHistory[channel][historyPos] = sine
                            cosHistory[channel][historyPos] = cosine
                            if (probing) continue
                            val refIndex = floorMod(historyPos - totalDelay, delayLength)
                            val filteredSin = sinHistory[channel][refIndex] * signedGain
                            val filteredCos = cosHistory[channel][refIndex] * signedGain
                            refPower[channel] =
                                0.9995f * refPower[channel] +
                                    0.0005f * (filteredSin * filteredSin + filteredCos * filteredCos)
                            val step = (0.006f / (refPower[channel] + 0.05f)).coerceIn(0.0005f, 0.08f)
                            weightSin[channel] -= step * heard * filteredSin
                            weightCos[channel] -= step * heard * filteredCos
                            val limit = 0.7f / (harmonic + 1) / activeTones
                            weightSin[channel] = weightSin[channel].coerceIn(-limit, limit)
                            weightCos[channel] = weightCos[channel].coerceIn(-limit, limit)
                            sample += weightSin[channel] * sine + weightCos[channel] * cosine
                        }
                    }
                    if (!probing) sample *= userStrength
                    samplesSinceProbe++
                    if (!probing && samplesSinceProbe > sampleRate * 3) {
                        probeLeft = (sampleRate * 0.12f).toInt()
                        samplesSinceProbe = 0
                    }
                } else if (!probing) {
                    val harmonics = harmonicCount.coerceIn(1, maxHarmonics)
                    for (tone in 0 until maxTones) {
                        val freq = trackedHz[tone]
                        if (freq <= 90f) continue
                        for (harmonic in 0 until harmonics) {
                            val harmonicHz = freq * (harmonic + 1)
                            if (harmonicHz >= sampleRate * 0.45f) break
                            val channel = tone * maxHarmonics + harmonic
                            oscPhase[channel] += 2.0 * PI * harmonicHz / sampleRate
                            if (oscPhase[channel] > TWO_PI) oscPhase[channel] -= TWO_PI
                            val ratio = if (harmonic == 0) 0.85f else 0.4f
                            sample += (userStrength * ratio / activeTones) * sin(
                                oscPhase[channel] + trimRadians * (harmonic + 1),
                            ).toFloat()
                        }
                    }
                }
            }

            if (sample > 0.95f) sample = 0.95f
            if (sample < -0.95f) sample = -0.95f
            out[i] = sample
            playHistory[historyPos] = sample
            historyPos++
            if (historyPos == delayLength) historyPos = 0
            outputEnergy += sample * sample
        }

        val count = length.coerceAtLeast(1)
        blockInput = sqrt(inputEnergy / count).toFloat()
        blockOutput = sqrt(outputEnergy / count).toFloat()
    }

    private fun pushAnalysis(sample: Float) {
        analysis[analysisPos] = sample
        analysisPos++
        if (analysisPos == fftSize) analysisPos = 0
        seen++
        if (seen >= fftSize && seen % hop == 0) {
            analyze()
        }
    }

    private fun analyze() {
        for (i in 0 until fftSize) {
            val sample = analysis[(analysisPos + i) % fftSize]
            fftReal[i] = sample * window[i]
            fftImag[i] = 0f
        }
        fft(fftReal, fftImag)

        val minBin = (60f * fftSize / sampleRate).toInt().coerceAtLeast(1)
        val maxBin = (4000f * fftSize / sampleRate).toInt().coerceAtMost(fftSize / 2 - 2)
        var sum = 0f
        for (bin in (minBin - 1)..maxBin) {
            val magnitude = hypot(fftReal[bin], fftImag[bin])
            magnitudes[bin] = magnitude
            if (bin in minBin..maxBin) sum += magnitude
        }
        val floor = sum / (maxBin - minBin + 1).coerceAtLeast(1)
        val peaks = ArrayList<Float>(maxTones)
        val peakMags = ArrayList<Float>(maxTones)
        for (bin in (minBin + 1) until maxBin) {
            val magnitude = magnitudes[bin]
            if (magnitude < magnitudes[bin - 1] || magnitude < magnitudes[bin + 1]) continue
            if (magnitude < floor * 5f || magnitude < 1f) continue
            val left = magnitudes[bin - 1]
            val right = magnitudes[bin + 1]
            val denom = left - 2f * magnitude + right
            val delta = if (abs(denom) < 1e-6f) 0f else 0.5f * (left - right) / denom
            val hz = ((bin + delta.coerceIn(-0.5f, 0.5f)) * sampleRate / fftSize)
                .coerceIn(60f, 4000f)
            val crowded = peaks.indices.any { abs(peaks[it] - hz) < 45f }
            if (crowded) continue
            var place = peaks.size
            for (index in peaks.indices) {
                if (magnitude > peakMags[index]) {
                    place = index
                    break
                }
            }
            if (place >= maxTones) continue
            peaks.add(place, hz)
            peakMags.add(place, magnitude)
            if (peaks.size > maxTones) {
                peaks.removeAt(peaks.lastIndex)
                peakMags.removeAt(peakMags.lastIndex)
            }
        }
        val used = BooleanArray(maxTones)
        for (index in peaks.indices) {
            val hz = peaks[index]
            var best = -1
            var bestDistance = 70f
            for (tone in 0 until maxTones) {
                if (used[tone] || trackedHz[tone] <= 0f) continue
                val distance = abs(hz - trackedHz[tone])
                if (distance < bestDistance) {
                    bestDistance = distance
                    best = tone
                }
            }
            val slot = if (best >= 0) {
                best
            } else {
                (0 until maxTones).firstOrNull { !used[it] && trackedHz[it] <= 0f } ?: -1
            }
            if (slot < 0) continue
            trackedHz[slot] = if (trackedHz[slot] <= 0f || abs(hz - trackedHz[slot]) > 60f) {
                hz
            } else {
                trackedHz[slot] + (hz - trackedHz[slot]) * 0.35f
            }
            toneLevel[slot] = peakMags[index]
            toneMiss[slot] = 0
            used[slot] = true
        }
        val holdingCancellation = blockOutput > 0.02f
        for (tone in 0 until maxTones) {
            if (used[tone] || trackedHz[tone] <= 0f) continue
            if (holdingCancellation) continue
            toneMiss[tone]++
            if (toneMiss[tone] > 30 && blockInput < 0.02f) {
                clearTone(tone)
            }
        }

        val bars = FloatArray(SPECTRUM_BARS)
        val shownMaxHz = 4000f
        for (bar in 0 until SPECTRUM_BARS) {
            val startHz = shownMaxHz * bar / SPECTRUM_BARS
            val endHz = shownMaxHz * (bar + 1) / SPECTRUM_BARS
            val startBin = (startHz * fftSize / sampleRate).toInt().coerceIn(1, fftSize / 2 - 1)
            val endBin = (endHz * fftSize / sampleRate).toInt().coerceIn(startBin, fftSize / 2 - 1)
            var peak = 0f
            for (bin in startBin..endBin) {
                val magnitude = hypot(fftReal[bin], fftImag[bin])
                if (magnitude > peak) peak = magnitude
            }
            bars[bar] = peak
        }
        val maxBar = bars.max()
        spectrum = if (maxBar < 1f) {
            List(SPECTRUM_BARS) { 0f }
        } else {
            bars.map { (it / maxBar).coerceIn(0f, 1f) }
        }
    }

    private fun clearTone(tone: Int) {
        trackedHz[tone] = 0f
        toneLevel[tone] = 0f
        toneMiss[tone] = 0
        for (harmonic in 0 until maxHarmonics) {
            val channel = tone * maxHarmonics + harmonic
            weightSin[channel] = 0f
            weightCos[channel] = 0f
            oscPhase[channel] = 0.0
            refPower[channel] = 0.05f
        }
    }

    private companion object {
        const val TWO_PI = PI * 2.0
    }
}
