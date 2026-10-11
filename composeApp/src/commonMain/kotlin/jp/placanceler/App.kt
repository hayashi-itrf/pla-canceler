package jp.placanceler

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

@Composable
fun App() {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Ink,
            onPrimary = Paper,
            background = Paper,
            onBackground = Ink,
            surface = Card,
            onSurface = Ink,
            secondary = Accent,
        ),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = Paper) {
            PlaScreen()
        }
    }
}

@Composable
private fun PlaScreen() {
    val session = rememberNoiseSession()
    val permission = rememberMicPermission()
    var running by remember { mutableStateOf(false) }
    var armed by remember { mutableStateOf(false) }
    var strength by remember { mutableFloatStateOf(0.75f) }
    var phase by remember { mutableFloatStateOf(0f) }
    var harmonics by remember { mutableStateOf(true) }
    var autoAdapt by remember { mutableStateOf(true) }
    var invertPath by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var snapshot by remember { mutableStateOf(CancellerSnapshot()) }

    LaunchedEffect(permission.granted, armed) {
        if (armed && permission.granted && !running) {
            val message = session.start()
            if (message != null) {
                error = message
                armed = false
            } else {
                error = null
                running = true
            }
        }
    }

    LaunchedEffect(running) {
        while (running) {
            snapshot = session.snapshot()
            delay(50)
        }
        snapshot = CancellerSnapshot()
    }

    LaunchedEffect(strength, phase, harmonics, autoAdapt, invertPath) {
        session.strength = strength
        session.phaseTrimDegrees = phase
        session.harmonicCount = if (harmonics) 3 else 1
        session.autoAdapt = autoAdapt
        session.invertPath = invertPath
    }

    DisposableEffect(Unit) {
        onDispose { session.stop() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Paper)
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 18.dp),
    ) {
        Text(
            text = "音やわらげ",
            color = Ink,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 1.sp,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "おもちゃのモーター音など、\n高さが続く音に逆向きの音を重ねます。",
            color = Muted,
            fontSize = 15.sp,
            lineHeight = 22.sp,
        )
        Spacer(Modifier.height(28.dp))
        FrequencyReadout(snapshot)
        Spacer(Modifier.height(8.dp))
        Text(
            text = statusText(snapshot, running),
            color = if (snapshot.status == ListenStatus.Cancelling) Quiet else Muted,
            fontSize = 14.sp,
        )
        Spacer(Modifier.height(18.dp))
        Spectrum(snapshot.spectrum, active = snapshot.status == ListenStatus.Cancelling)
        Spacer(Modifier.height(8.dp))
        LevelRow(label = "残っている音", level = snapshot.inputLevel, color = Ink)
        Spacer(Modifier.height(6.dp))
        LevelRow(label = "出している音", level = snapshot.outputLevel, color = Accent)
        Spacer(Modifier.height(28.dp))

        val buttonLabel = if (running) "停止" else "始める"
        Box(
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .size(156.dp)
                .clip(CircleShape)
                .background(if (running) Accent else Ink)
                .clickable {
                    if (running) {
                        running = false
                        armed = false
                        session.stop()
                        error = null
                    } else if (permission.granted) {
                        armed = true
                    } else {
                        armed = true
                        permission.request { allowed ->
                            if (!allowed) {
                                armed = false
                                error = "マイクが許可されていません。設定アプリからマイクを許可してください。"
                            }
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = buttonLabel,
                color = Paper,
                fontSize = 20.sp,
                fontWeight = FontWeight.Medium,
            )
        }

        if (error != null) {
            Spacer(Modifier.height(14.dp))
            Text(text = error.orEmpty(), color = Accent, fontSize = 13.sp, lineHeight = 18.sp)
        }

        Spacer(Modifier.height(28.dp))
        ControlCard {
            SliderRow(
                label = "強さ",
                valueLabel = "${(strength * 100).roundToInt()}%",
                value = strength,
                onValueChange = { strength = it },
                valueRange = 0f..1f,
            )
            Spacer(Modifier.height(8.dp))
            SliderRow(
                label = if (autoAdapt) "ずれの補正" else "位相",
                valueLabel = "${phase.roundToInt()}°",
                value = phase,
                onValueChange = { phase = it },
                valueRange = -180f..180f,
            )
            Spacer(Modifier.height(12.dp))
            ToggleRow(
                title = "自動で合わせる",
                body = "マイクの位置で小さくなるように、強さと位相を動かします。",
                checked = autoAdapt,
                onCheckedChange = { autoAdapt = it },
            )
            Spacer(Modifier.height(8.dp))
            ToggleRow(
                title = "倍の高さも合わせる",
                body = "二倍の高さが混ざっているときに、それも一緒に出します。",
                checked = harmonics,
                onCheckedChange = { harmonics = it },
            )
            Spacer(Modifier.height(8.dp))
            ToggleRow(
                title = "向きを反転",
                body = "自動にして音が大きくなったときだけ、オンにしてください。",
                checked = invertPath,
                onCheckedChange = {
                    invertPath = it
                    session.invertPath = it
                },
            )
        }

        if (running && snapshot.sampleRate > 0 && snapshot.delaySamples > 0) {
            val millis = snapshot.delaySamples * 1000f / snapshot.sampleRate
            Spacer(Modifier.height(12.dp))
            Text(
                text = "測った遅れ ${millis.roundToInt()} ms",
                color = Muted,
                fontSize = 12.sp,
            )
        }

        Spacer(Modifier.height(18.dp))
        Text(
            text = "おもちゃのモーター音のように、高さがしばらく続く音に向いています。声や、急に変わる音にはあまり効きません。弱くなるのはスピーカーのすぐ近くだけです。耳のそばに置くか、自分とその音のあいだに置いてください。始めると、遅れを測る短い音が一度鳴ります。音量ボタンも使って、残っている音がいちばん小さくなる強さに合わせてください。",
            color = Muted,
            fontSize = 13.sp,
            lineHeight = 20.sp,
        )
    }
}

@Composable
private fun FrequencyReadout(snapshot: CancellerSnapshot) {
    val number = if (snapshot.frequencyHz > 0f) {
        snapshot.frequencyHz.roundToInt().toString()
    } else {
        "— —"
    }
    Row(verticalAlignment = Alignment.Bottom) {
        Text(
            text = number,
            color = Ink,
            fontSize = 76.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = (-2).sp,
            lineHeight = 76.sp,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = "Hz",
            color = Muted,
            fontSize = 20.sp,
            modifier = Modifier.padding(bottom = 12.dp),
        )
    }
    val others = snapshot.frequenciesHz.drop(1)
    if (others.isNotEmpty()) {
        Spacer(Modifier.height(4.dp))
        Text(
            text = others.joinToString("  ·  ") { "${it.roundToInt()} Hz" },
            color = Muted,
            fontSize = 16.sp,
        )
    }
}

@Composable
private fun Spectrum(values: List<Float>, active: Boolean) {
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp),
    ) {
        if (values.isEmpty()) return@Canvas
        val gap = 3.dp.toPx()
        val barWidth = ((size.width - gap * (values.size - 1)) / values.size).coerceAtLeast(1f)
        values.forEachIndexed { index, raw ->
            val amount = raw.coerceIn(0f, 1f)
            val barHeight = (amount * size.height).coerceAtLeast(3.dp.toPx())
            val color = if (active) {
                Accent.copy(alpha = 0.28f + 0.72f * amount)
            } else {
                Ink.copy(alpha = 0.18f + 0.35f * amount)
            }
            drawRoundRect(
                color = color,
                topLeft = Offset(index * (barWidth + gap), size.height - barHeight),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f),
            )
        }
    }
}

@Composable
private fun LevelRow(label: String, level: Float, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            color = Muted,
            fontSize = 12.sp,
            modifier = Modifier.width(92.dp),
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(6.dp)
                .clip(RoundedCornerShape(99.dp))
                .background(PaperDeep),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(level.coerceIn(0.02f, 1f))
                    .height(6.dp)
                    .clip(RoundedCornerShape(99.dp))
                    .background(color.copy(alpha = 0.85f)),
            )
        }
    }
}

@Composable
private fun ControlCard(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(Card)
            .padding(horizontal = 18.dp, vertical = 16.dp),
    ) {
        content()
    }
}

@Composable
private fun SliderRow(
    label: String,
    valueLabel: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = label, color = Ink, fontSize = 14.sp)
        Text(text = valueLabel, color = Muted, fontSize = 14.sp)
    }
    Slider(
        value = value,
        onValueChange = onValueChange,
        valueRange = valueRange,
        colors = SliderDefaults.colors(
            thumbColor = Ink,
            activeTrackColor = Ink,
            inactiveTrackColor = PaperDeep,
        ),
    )
}

@Composable
private fun ToggleRow(
    title: String,
    body: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, color = Ink, fontSize = 15.sp)
            Text(text = body, color = Muted, fontSize = 12.sp, lineHeight = 17.sp)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedTrackColor = Quiet,
                checkedThumbColor = Paper,
                uncheckedTrackColor = PaperDeep,
                uncheckedThumbColor = Paper,
                uncheckedBorderColor = PaperDeep,
            ),
        )
    }
}

private fun statusText(snapshot: CancellerSnapshot, running: Boolean): String {
    if (!running) return "止めています"
    return when (snapshot.status) {
        ListenStatus.Measuring -> "スピーカーとマイクのずれを測っています"
        ListenStatus.Cancelling -> "聞こえている音をやわらげています"
        ListenStatus.Searching -> "おもちゃのモーター音を探しています"
        ListenStatus.Idle -> "準備しています"
    }
}
