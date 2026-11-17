package io.github.mangi.eta.agent.voice

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.RuntimeShader
import android.graphics.Shader
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.core.AndroidAgentLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

@Composable
internal fun EtaAssistantWave(
    active: Boolean,
    level: () -> Float,
    modifier: Modifier = Modifier,
    energized: Boolean = true,
) {
    val context = LocalContext.current
    val lowMemory = remember(context) { context.getSystemService(android.app.ActivityManager::class.java)?.isLowRamDevice == true }
    val effect by produceState<EtaWaveEffect?>(null, lowMemory) {
        if (lowMemory) return@produceState
        value = withContext(Dispatchers.IO) {
            try {
                EtaWaveEffect(context)
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                AndroidAgentLogger.warn("Eta assistant wave unavailable: type=${error.javaClass.simpleName}")
                null
            }
        }
    }
    val currentActive by rememberUpdatedState(active)
    val currentEnergized by rememberUpdatedState(energized)
    val currentLevel by rememberUpdatedState(level)
    var elapsed by remember { mutableFloatStateOf(0f) }
    var smoothedLevel by remember { mutableFloatStateOf(0f) }
    var waveAlpha by remember { mutableFloatStateOf(0f) }
    var unavailable by remember { mutableStateOf(false) }
    var entranceReachedBottom by remember { mutableStateOf(false) }
    val path = remember { Path() }
    LaunchedEffect(Unit) {
        delay(ASSISTANT_EDGE_ARRIVAL_MS)
        entranceReachedBottom = true
    }
    LaunchedEffect(entranceReachedBottom, active) {
        if (!entranceReachedBottom) return@LaunchedEffect
        val animationEnabled = runCatching { ValueAnimator.areAnimatorsEnabled() }.getOrDefault(true)
        if (!animationEnabled) {
            elapsed = 0f
            smoothedLevel = 0f
            waveAlpha = if (active) 1f else 0f
            return@LaunchedEffect
        }
        var lastNanos = 0L
        while (isActive && (currentActive || waveAlpha > 0f || smoothedLevel > 0.01f)) {
            if (lowMemory) delay(33)
            withFrameNanos { now ->
                val deltaSeconds = if (lastNanos == 0L) 0f else ((now - lastNanos) / 1_000_000_000f).coerceIn(0f, 0.05f)
                lastNanos = now
                elapsed += deltaSeconds
                val target = if (currentActive) 1f else 0f
                waveAlpha += (target - waveAlpha) * (deltaSeconds * 7f)
                val measured = if (currentActive && currentEnergized) currentLevel().coerceIn(0f, 1f) else 0f
                smoothedLevel += (measured - smoothedLevel) * (deltaSeconds * (if (measured > smoothedLevel) 14f else 6f))
            }
            if (!currentActive && waveAlpha < 0.01f) waveAlpha = 0f
        }
    }
    Canvas(modifier.fillMaxWidth().height(140.dp)) {
        if (!entranceReachedBottom || waveAlpha <= 0f) return@Canvas
        val hardwareShader = !unavailable && effect != null && drawContext.canvas.nativeCanvas.isHardwareAccelerated
        if (hardwareShader) {
            try {
                effect?.update(elapsed, currentEnergized, smoothedLevel, size.width, size.height)
                drawRect(effect!!.brush, alpha = waveAlpha)
                return@Canvas
            } catch (error: RuntimeException) {
                unavailable = true
                AndroidAgentLogger.warn("Eta assistant wave draw failed: type=${error.javaClass.simpleName}")
            }
        }
        drawFallbackWaves(
            path = path,
            time = elapsed,
            alpha = waveAlpha,
            energized = currentEnergized,
            level = smoothedLevel,
        )
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawFallbackWaves(
    path: Path,
    time: Float,
    alpha: Float,
    energized: Boolean,
    level: Float,
) {
    val width = size.width
    val height = size.height
    val amplitude = (height * 0.06f) * (if (energized) 1f + level * 2.2f else 0.55f)
    val points = 36
    val colors = EtaAssistantPalette.WaveColors
    for (layer in 0..3) {
        val baseY = height * (0.40f + layer * 0.14f)
        val phase = time * (if (energized) 1.5f else 0.35f) + layer * 0.8f
        path.reset()
        for (point in 0..points) {
            val fraction = point.toFloat() / points
            val envelope = sin(fraction * PI.toFloat()).let { abs(it) * 0.65f + 0.35f }
            val y = baseY + sin(fraction * 9f + phase) * amplitude * envelope +
                sin(fraction * 17f - phase * 1.35f) * amplitude * 0.35f
            val x = fraction * width
            if (point == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        val color = colors[layer % colors.size]
        drawPath(
            path = path,
            brush = Brush.horizontalGradient(
                listOf(color.copy(alpha = 0.12f), color, colors[(layer + 1) % colors.size]),
                startX = 0f,
                endX = width,
            ),
            alpha = alpha * (0.52f + 0.1f * layer),
            style = Stroke(width = (5f + layer * 1.5f) * density, cap = StrokeCap.Round),
        )
    }
}

/** 只播放打包资源中的数值轨道，不执行资源里的脚本。 */
internal class EtaWaveEffect(context: Context) {
    private val data = context.assets.open("assistant/wave.json").bufferedReader().use { JSONObject(it.readText()) }
    private val shader = RuntimeShader(context.assets.open("assistant/wave.agsl").bufferedReader().use { it.readText() })
    val brush = ShaderBrush(shader)
    private val values = linkedMapOf<String, FloatArray>()
    private val animations: Map<String, List<Track>>
    private var mode = "Default"
    private var startedAt = 0f
    private var previousTime = 0f
    private var origin = emptyMap<String, FloatArray>()
    private var wasActive = false
    private val angles = FloatArray(3)
    private val animatedNames: Set<String>
    private var lastWidth = -1f
    private var lastHeight = -1f

    init {
        val uniforms = data.getJSONArray("uniforms")
        for (index in 0 until uniforms.length()) {
            val uniform = uniforms.getJSONObject(index)
            val name = uniform.getString("uniformName")
            when (uniform.getString("type")) {
                "Texture" -> {
                    val bitmap = context.assets.open("assistant/$name.png").use { requireNotNull(BitmapFactory.decodeStream(it)) }
                    val sampler = BitmapShader(bitmap, Shader.TileMode.REPEAT, Shader.TileMode.CLAMP)
                    sampler.setFilterMode(BitmapShader.FILTER_MODE_LINEAR)
                    shader.setInputShader(name, sampler)
                }
                "float" -> values[name] = floatArrayOf(uniform.getDouble("value").toFloat())
                "Vec2" -> values[name] = floatArrayOf(uniform.getDouble("x").toFloat(), uniform.getDouble("y").toFloat())
                else -> error("Unsupported wave uniform")
            }
        }
        val definitions = data.getJSONArray("animations")
        animations = buildMap {
            for (index in 0 until definitions.length()) {
                val definition = definitions.getJSONObject(index)
                val lines = definition.getJSONArray("animLines")
                put(definition.getString("name"), buildList {
                    for (lineIndex in 0 until lines.length()) {
                        val line = lines.getJSONObject(lineIndex)
                        val keys = line.getJSONObject("animKeys")
                        for (axis in keys.keys()) {
                            add(Track(line.getString("name"), if (axis == "y") 1 else 0, parseKeys(keys.getJSONArray(axis))))
                        }
                    }
                })
            }
        }
        apply("Default", 0f, 0f)
        values.getValue("r")[0] = 0f
        values.getValue("superN")[0] = 2f
        animatedNames = buildSet {
            animations.filterKeys { it != "Default" }.values.forEach { tracks ->
                tracks.forEach { add(it.name) }
            }
            add("u_time")
            add("u_speed1")
            add("u_Angle2")
            add("u_Angle3")
            add("u_Angle4")
        }
        for ((name, value) in values) shader.setFloatUniform(name, value)
    }

    fun update(time: Float, active: Boolean, level: Float, width: Float, height: Float) {
        if (active != wasActive) {
            switch(if (active) "Wave On" else "Wave Off", time)
            wasActive = active
        }
        if (active && time - startedAt >= 0.4f && mode == "Wave On") switch("Voice NoListen", time)
        if (active && mode != "Wave On") {
            val speaking = level > 0.05f
            when {
                speaking && mode == "Voice NoListen" -> switch("Voice Found", time)
                !speaking && (mode == "Voice Found" || mode == "Voice Listen") -> switch("Voice NoListen", time)
                speaking && mode == "Voice Found" && time - startedAt >= 0.25f -> switch("Voice Listen", time)
            }
        }
        apply(mode, time - startedAt, level)
        val dt = (time - previousTime).coerceIn(0f, 0.25f)
        previousTime = time
        val speaking = mode == "Voice Found" || mode == "Voice Listen"
        val speeds = if (speaking) floatArrayOf(1.2f, 1f, 0.7f) else floatArrayOf(0.2f, 0.3f, 0.17f)
        for (index in angles.indices) {
            angles[index] += speeds[index] * dt
            values.getValue("u_Angle${index + 2}")[0] = angles[index]
        }
        values.getValue("u_speed1")[0] = if (speaking) -0.4f else 0.2f
        values.getValue("u_time")[0] = time
        if (width != lastWidth || height != lastHeight) {
            values.getValue("u_resolution")[0] = width
            values.getValue("u_resolution")[1] = height
            shader.setFloatUniform("u_resolution", values.getValue("u_resolution"))
            lastWidth = width
            lastHeight = height
        }
        for (name in animatedNames) shader.setFloatUniform(name, values.getValue(name))
    }

    private fun switch(next: String, time: Float) {
        origin = values.mapValues { it.value.copyOf() }
        mode = next
        startedAt = time
    }

    private fun apply(name: String, time: Float, level: Float) {
        for (track in animations.getValue(name)) {
            val keys = track.keys
            val right = keys.indexOfFirst { it.time > time }.let { if (it < 0) keys.lastIndex else it }
            val left = (right - 1).coerceAtLeast(0)
            val from = keys[left]
            val to = keys[right]
            val start = resolve(from, track, level)
            val end = resolve(to, track, level)
            val fraction = if (time >= to.time || from.time == to.time) 1f else from.easing.transform(((time - from.time) / (to.time - from.time)).coerceIn(0f, 1f))
            values.getValue(track.name)[track.axis] = start + (end - start) * fraction
        }
    }

    private fun resolve(key: Key, track: Track, level: Float): Float {
        val amount = ((level - 0.6f) / 0.25f).coerceIn(0f, 1f)
        return when (key.expression) {
            "" -> key.value
            "origin" -> origin[track.name]?.get(track.axis) ?: key.value
            "u_amp2", "u_amp3" -> 0.12f + 0.28f * amount
            "u_amp4" -> 0.11f + 0.29f * amount
            "u_maskW" -> 0.75f + 0.25f * amount
            else -> error("Unsupported wave expression")
        }
    }

    private fun parseKeys(array: JSONArray): List<Key> = (0 until array.length()).map { index ->
        val key = array.getJSONObject(index)
        val curve = key.getJSONArray("bezier")
        require(key.getString("ipol") == "bezier")
        Key(key.getDouble("time").toFloat(), key.getDouble("value").toFloat(), key.getString("expr"),
            CubicBezierEasing(curve.getDouble(0).toFloat(), curve.getDouble(1).toFloat(), curve.getDouble(2).toFloat(), curve.getDouble(3).toFloat()))
    }

    private data class Track(val name: String, val axis: Int, val keys: List<Key>)
    private data class Key(val time: Float, val value: Float, val expression: String, val easing: CubicBezierEasing)
}
