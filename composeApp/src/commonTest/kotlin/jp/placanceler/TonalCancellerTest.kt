package jp.placanceler

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertTrue

class LoopbackProbeTest {
    @Test
    fun measuresSignedDelayAndGain() {
        val probe = LoopbackProbe(48_000)
        val delay = 120
        val gain = -0.45f
        for (index in probe.record.indices) {
            val source = index - delay
            probe.record[index] = if (source in probe.play.indices) gain * probe.play[source] else 0f
        }
        val path = probe.correlate()
        assertTrue(abs(path.delaySamples - delay) <= 2, "delay=${path.delaySamples}")
        assertTrue(abs(path.gain - gain) < 0.08f, "gain=${path.gain}")
    }
}

class TonalCancellerTest {
    @Test
    fun detectsSteadyTone() {
        val sampleRate = 48_000
        val canceller = TonalCanceller(sampleRate)
        canceller.strength = 0f
        canceller.pathGain = 0f
        val mic = FloatArray(1)
        val speaker = FloatArray(1)
        var phase = 0.0
        repeat(sampleRate / 2) {
            mic[0] = (sin(phase) * 0.4).toFloat()
            phase += 2.0 * PI * 640.0 / sampleRate
            canceller.process(mic, speaker)
        }
        val hz = canceller.snapshot(ListenStatus.Searching).frequencyHz
        assertTrue(abs(hz - 640f) < 12f, "hz=$hz")
    }

    @Test
    fun reducesDelayedToneAtTheMicrophone() {
        val sampleRate = 48_000
        val delay = 80
        val gain = 0.75f
        val canceller = TonalCanceller(sampleRate)
        canceller.modelDelaySamples = delay
        canceller.pathGain = gain
        canceller.strength = 1f
        canceller.autoAdapt = true
        canceller.harmonicCount = 1
        val line = FloatArray(delay)
        var position = 0
        var phase = 0.0
        val mic = FloatArray(1)
        val speaker = FloatArray(1)
        var micEnergy = 0.0
        var noiseEnergy = 0.0
        val total = sampleRate * 4
        for (n in 0 until total) {
            val noise = (sin(phase) * 0.35).toFloat()
            phase += 2.0 * PI * 520.0 / sampleRate
            val arrived = line[position]
            mic[0] = noise + gain * arrived
            canceller.process(mic, speaker)
            line[position] = speaker[0]
            position++
            if (position == delay) position = 0
            if (n > total - sampleRate / 2) {
                micEnergy += mic[0] * mic[0]
                noiseEnergy += noise * noise
            }
        }
        val ratio = micEnergy / noiseEnergy
        val hz = canceller.snapshot(ListenStatus.Cancelling).frequencyHz
        assertTrue(ratio < 0.45, "ratio=$ratio hz=$hz")
    }

    @Test
    fun tracksTwoIndependentTones() {
        val sampleRate = 48_000
        val canceller = TonalCanceller(sampleRate)
        canceller.strength = 0f
        canceller.pathGain = 0f
        val mic = FloatArray(1)
        val speaker = FloatArray(1)
        var phaseA = 0.0
        var phaseB = 0.0
        repeat(sampleRate / 2) {
            mic[0] = (sin(phaseA) * 0.35 + sin(phaseB) * 0.22).toFloat()
            phaseA += 2.0 * PI * 420.0 / sampleRate
            phaseB += 2.0 * PI * 880.0 / sampleRate
            canceller.process(mic, speaker)
        }
        val found = canceller.snapshot(ListenStatus.Searching).frequenciesHz
        assertTrue(found.any { abs(it - 420f) < 15f }, "found=$found")
        assertTrue(found.any { abs(it - 880f) < 15f }, "found=$found")
    }
}
