package jp.placanceler

import androidx.compose.runtime.Composable

data class MicPermission(
    val granted: Boolean,
    val request: (onResult: (Boolean) -> Unit) -> Unit,
)

interface NoiseSession {
    fun start(): String?
    fun stop()
    fun snapshot(): CancellerSnapshot
    var strength: Float
    var phaseTrimDegrees: Float
    var harmonicCount: Int
    var autoAdapt: Boolean
    var invertPath: Boolean
}

@Composable
expect fun rememberNoiseSession(): NoiseSession

@Composable
expect fun rememberMicPermission(): MicPermission
