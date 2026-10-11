@file:OptIn(
    kotlinx.cinterop.BetaInteropApi::class,
    kotlinx.cinterop.ExperimentalForeignApi::class,
    kotlin.concurrent.atomics.ExperimentalAtomicApi::class,
)

package jp.placanceler

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicInt
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.FloatVar
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.value
import platform.AVFAudio.AVAudioEngine
import platform.AVFAudio.AVAudioFormat
import platform.AVFAudio.AVAudioPCMBuffer
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryOptionDefaultToSpeaker
import platform.AVFAudio.AVAudioSessionCategoryPlayAndRecord
import platform.AVFAudio.AVAudioSessionModeMeasurement
import platform.AVFAudio.AVAudioSessionPortOverrideSpeaker
import platform.AVFAudio.AVAudioSessionRecordPermissionGranted
import platform.AVFAudio.AVAudioSourceNode
import platform.AVFAudio.sampleRate
import platform.AVFAudio.setActive
import platform.AVFAudio.setPreferredIOBufferDuration
import platform.AVFAudio.setPreferredSampleRate
import platform.Foundation.NSError
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

@Composable
actual fun rememberNoiseSession(): NoiseSession = remember { IosNoiseSession() }

@Composable
actual fun rememberMicPermission(): MicPermission {
    val session = AVAudioSession.sharedInstance()
    var granted by remember {
        mutableStateOf(session.recordPermission == AVAudioSessionRecordPermissionGranted)
    }
    return MicPermission(granted = granted) { onResult ->
        if (granted) {
            onResult(true)
        } else {
            session.requestRecordPermission { ok ->
                dispatch_async(dispatch_get_main_queue()) {
                    granted = ok
                    onResult(ok)
                }
            }
        }
    }
}

private class IosNoiseSession : NoiseSession {
    private var sampleRate: Int = 48_000
    private var canceller = TonalCanceller(sampleRate)
    private var engine: AVAudioEngine? = null
    private val micRing = SampleRing(32_768)
    private var probe: LoopbackProbe? = null

    @Volatile private var running = false
    @Volatile private var measuring = false
    @Volatile private var status = ListenStatus.Idle
    @Volatile private var playIndex = 0
    @Volatile private var recordIndex = 0

    override var strength: Float
        get() = canceller.strength
        set(value) { canceller.strength = value }

    override var phaseTrimDegrees: Float
        get() = canceller.phaseTrimDegrees
        set(value) { canceller.phaseTrimDegrees = value }

    override var harmonicCount: Int
        get() = canceller.harmonicCount
        set(value) { canceller.harmonicCount = value }

    override var autoAdapt: Boolean
        get() = canceller.autoAdapt
        set(value) { canceller.autoAdapt = value }

    override var invertPath: Boolean
        get() = canceller.invertPath
        set(value) {
            if (canceller.invertPath != value) {
                canceller.invertPath = value
                canceller.resetWeights()
            }
        }

    override fun snapshot(): CancellerSnapshot = canceller.snapshot(status)

    override fun start(): String? {
        if (running) return null
        val audioSession = AVAudioSession.sharedInstance()
        if (audioSession.recordPermission != AVAudioSessionRecordPermissionGranted) {
            return "マイクが許可されていません。設定アプリからマイクを許可してください。"
        }
        val configured = configureSession(audioSession)
        if (configured != null) return configured

        val rate = audioSession.sampleRate().takeIf { it >= 8_000.0 } ?: 48_000.0
        sampleRate = rate.toInt()
        canceller = TonalCanceller(sampleRate)
        val localProbe = LoopbackProbe(sampleRate)
        probe = localProbe
        playIndex = 0
        recordIndex = 0
        measuring = true
        status = ListenStatus.Measuring
        micRing.clear()

        val audioEngine = AVAudioEngine()
        val input = audioEngine.inputNode
        val inputFormat = input.outputFormatForBus(0u)
        if (inputFormat.sampleRate <= 0.0) {
            return "マイクの形式を取得できませんでした。"
        }

        val outputFormat = AVAudioFormat(standardFormatWithSampleRate = rate, channels = 1u)

        input.installTapOnBus(0u, 256u, inputFormat) { buffer, _ ->
            onMicrophone(buffer)
        }

        val source = AVAudioSourceNode(format = outputFormat) { _, _, frameCount, audioBufferList ->
            render(frameCount.toInt(), audioBufferList)
        }
        audioEngine.attachNode(source)
        audioEngine.connect(source, audioEngine.mainMixerNode, outputFormat)

        engine = audioEngine
        running = true
        memScoped {
            val error = alloc<ObjCObjectVar<NSError?>>()
            val started = audioEngine.startAndReturnError(error.ptr)
            if (!started) {
                running = false
                input.removeTapOnBus(0u)
                engine = null
                return error.value?.localizedDescription ?: "音声エンジンを開始できませんでした。"
            }
        }
        return null
    }

    override fun stop() {
        running = false
        measuring = false
        status = ListenStatus.Idle
        val audioEngine = engine
        engine = null
        audioEngine?.inputNode?.removeTapOnBus(0u)
        audioEngine?.stop()
        try {
            AVAudioSession.sharedInstance().setActive(false, null)
        } catch (_: Exception) {
        }
        canceller.reset()
    }

    private fun configureSession(session: AVAudioSession): String? {
        memScoped {
            val error = alloc<ObjCObjectVar<NSError?>>()
            val categorySet = session.setCategory(
                AVAudioSessionCategoryPlayAndRecord,
                mode = AVAudioSessionModeMeasurement,
                options = AVAudioSessionCategoryOptionDefaultToSpeaker,
                error = error.ptr,
            )
            if (!categorySet) {
                return error.value?.localizedDescription ?: "オーディオの設定に失敗しました。"
            }
            session.setPreferredSampleRate(48_000.0, error.ptr)
            session.setPreferredIOBufferDuration(0.005, error.ptr)
            session.overrideOutputAudioPort(AVAudioSessionPortOverrideSpeaker, error.ptr)
            val active = session.setActive(true, error.ptr)
            if (!active) {
                return error.value?.localizedDescription ?: "オーディオを開始できませんでした。"
            }
        }
        return null
    }

    private fun onMicrophone(buffer: AVAudioPCMBuffer?) {
        if (!running || buffer == null) return
        val channels = buffer.floatChannelData ?: return
        val data = channels[0] ?: return
        val frames = buffer.frameLength.toInt()
        if (measuring) {
            val localProbe = probe ?: return
            var index = recordIndex
            for (i in 0 until frames) {
                if (index < localProbe.record.size) {
                    localProbe.record[index] = data[i]
                    index++
                }
            }
            recordIndex = index
            return
        }
        for (i in 0 until frames) {
            micRing.push(data[i])
        }
    }

    private fun render(frames: Int, audioBufferList: CPointer<platform.CoreAudioTypes.AudioBufferList>?): Int {
        val list = audioBufferList?.pointed ?: return 0
        val first = list.mBuffers[0]
        val data = first.mData?.reinterpret<FloatVar>() ?: return 0
        if (!running) {
            for (i in 0 until frames) data[i] = 0f
            return 0
        }
        if (measuring) {
            val localProbe = probe
            if (localProbe == null) {
                for (i in 0 until frames) data[i] = 0f
                return 0
            }
            var index = playIndex
            for (i in 0 until frames) {
                data[i] = if (index < localProbe.play.size) localProbe.play[index] else 0f
                index++
            }
            playIndex = index
            if (index >= localProbe.play.size && recordIndex >= localProbe.record.size) {
                val path = localProbe.correlate()
                canceller.modelDelaySamples = path.delaySamples
                canceller.pathGain = path.gain
                measuring = false
                status = ListenStatus.Searching
            }
            return 0
        }

        if (micRing.available() < frames) {
            for (i in 0 until frames) data[i] = 0f
            return 0
        }
        val mic = FloatArray(frames)
        val speaker = FloatArray(frames)
        micRing.pop(mic, frames)
        canceller.process(mic, speaker, frames)
        for (i in 0 until frames) data[i] = speaker[i]
        return 0
    }
}

private class SampleRing(private val capacity: Int) {
    private val data = FloatArray(capacity)
    private val write = AtomicInt(0)
    private val read = AtomicInt(0)

    fun clear() {
        write.store(0)
        read.store(0)
    }

    fun available(): Int {
        val amount = write.load() - read.load()
        return if (amount >= 0) amount else amount + capacity
    }

    fun push(sample: Float) {
        val current = write.load()
        val next = if (current + 1 == capacity) 0 else current + 1
        if (next == read.load()) return
        data[current] = sample
        write.store(next)
    }

    fun pop(target: FloatArray, length: Int) {
        var current = read.load()
        for (i in 0 until length) {
            target[i] = data[current]
            current = if (current + 1 == capacity) 0 else current + 1
        }
        read.store(current)
    }
}
