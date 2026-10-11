package jp.placanceler

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Process
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import kotlin.concurrent.Volatile
import kotlin.concurrent.thread
import kotlin.math.min

@Composable
actual fun rememberNoiseSession(): NoiseSession {
    val context = LocalContext.current.applicationContext
    return remember { AndroidNoiseSession(context) }
}

@Composable
actual fun rememberMicPermission(): MicPermission {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED,
        )
    }
    var onResult by remember { mutableStateOf<(Boolean) -> Unit>({}) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { allowed ->
        granted = allowed
        onResult(allowed)
    }
    return MicPermission(granted = granted) { result ->
        if (granted) {
            result(true)
        } else {
            onResult = result
            launcher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
}

private class AndroidNoiseSession(
    private val context: android.content.Context,
) : NoiseSession {
    private val sampleRate: Int = AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_MUSIC)
        .takeIf { it > 0 } ?: 48_000
    private val canceller = TonalCanceller(sampleRate)

    @Volatile private var running = false
    @Volatile private var status = ListenStatus.Idle
    @Volatile private var worker: Thread? = null
    private var record: AudioRecord? = null
    private var track: AudioTrack? = null

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
        val permission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO,
        )
        if (permission != PackageManager.PERMISSION_GRANTED) {
            return "マイクが許可されていません。"
        }

        val minRecord = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_FLOAT,
        )
        val minPlay = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT,
        )
        if (minRecord <= 0 || minPlay <= 0) {
            return "この端末では、必要な形式の音声を扱えません。"
        }
        val frameCount = (maxOf(minRecord, minPlay) / 4).coerceIn(256, 4096)

        val recorder = try {
            openRecorder(frameCount * 4)
        } catch (error: Exception) {
            return "マイクを開始できませんでした。"
        }
        val player = openPlayer(frameCount * 4)
        if (recorder.state != AudioRecord.STATE_INITIALIZED ||
            player.state != AudioTrack.STATE_INITIALIZED
        ) {
            recorder.release()
            player.release()
            return "音声の入出力を開始できませんでした。"
        }

        record = recorder
        track = player
        canceller.reset()
        running = true
        status = ListenStatus.Measuring
        worker = thread(name = "pla-audio", priority = Thread.MAX_PRIORITY) {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            try {
                recorder.startRecording()
                player.play()
                val path = measurePath(recorder, player)
                canceller.modelDelaySamples = path.delaySamples
                canceller.pathGain = path.gain
                status = ListenStatus.Searching
                val mic = FloatArray(frameCount)
                val speaker = FloatArray(frameCount)
                while (running) {
                    val read = recorder.read(mic, 0, mic.size, AudioRecord.READ_BLOCKING)
                    if (!running) break
                    if (read <= 0) continue
                    canceller.process(mic, speaker, read)
                    var written = 0
                    while (written < read && running) {
                        val count = player.write(
                            speaker,
                            written,
                            read - written,
                            AudioTrack.WRITE_BLOCKING,
                        )
                        if (count <= 0) break
                        written += count
                    }
                }
            } finally {
                status = ListenStatus.Idle
                stopQuietly(recorder, player)
            }
        }
        return null
    }

    override fun stop() {
        running = false
        status = ListenStatus.Idle
        try {
            record?.stop()
        } catch (_: Exception) {
        }
        try {
            track?.pause()
            track?.flush()
            track?.stop()
        } catch (_: Exception) {
        }
        worker?.join(800)
        worker = null
        release()
    }

    private fun measurePath(recorder: AudioRecord, player: AudioTrack): AcousticPath {
        val probe = LoopbackProbe(sampleRate)
        val recorded = java.util.concurrent.atomic.AtomicInteger(0)
        val reader = thread(name = "pla-calibrate") {
            while (running && recorded.get() < probe.record.size) {
                val position = recorded.get()
                val count = recorder.read(
                    probe.record,
                    position,
                    min(512, probe.record.size - position),
                    AudioRecord.READ_BLOCKING,
                )
                if (count > 0) recorded.addAndGet(count) else break
            }
        }
        var played = 0
        while (running && played < probe.play.size) {
            val count = player.write(
                probe.play,
                played,
                min(512, probe.play.size - played),
                AudioTrack.WRITE_BLOCKING,
            )
            if (count > 0) played += count else break
        }
        val deadline = System.currentTimeMillis() + 1200
        while (running && recorded.get() < probe.record.size && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }
        reader.join(200)
        return probe.correlate()
    }

    private fun openRecorder(bufferBytes: Int): AudioRecord {
        val sources = intArrayOf(
            MediaRecorder.AudioSource.UNPROCESSED,
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.MIC,
        )
        var last: Exception? = null
        for (source in sources) {
            try {
                val candidate = AudioRecord.Builder()
                    .setAudioSource(source)
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .build(),
                    )
                    .setBufferSizeInBytes(bufferBytes.coerceAtLeast(bufferBytes))
                    .build()
                if (candidate.state == AudioRecord.STATE_INITIALIZED) return candidate
                candidate.release()
            } catch (error: Exception) {
                last = error
            }
        }
        throw last ?: IllegalStateException("recorder")
    }

    private fun openPlayer(bufferBytes: Int): AudioTrack {
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(bufferBytes)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
    }

    private fun stopQuietly(recorder: AudioRecord, player: AudioTrack) {
        try {
            recorder.stop()
        } catch (_: Exception) {
        }
        try {
            player.stop()
        } catch (_: Exception) {
        }
    }

    private fun release() {
        try {
            record?.release()
        } catch (_: Exception) {
        }
        try {
            track?.release()
        } catch (_: Exception) {
        }
        record = null
        track = null
    }
}
