package com.example.pocketagent

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

/**
 * Custom 60fps Canvas audio waveform visualizer for the Duplex Voice dialog.
 *
 * Renders 20 centered rounded vertical bars in white (`0xFFFFFFFF`) with alpha
 * modulation and state-driven motion:
 * - [DuplexVoiceController.DuplexState.LISTENING]: symmetric bell-curve envelope reacting to RMS.
 * - [DuplexVoiceController.DuplexState.TRANSCRIBING] / [DuplexVoiceController.DuplexState.GENERATING] /
 *   [DuplexVoiceController.DuplexState.SPEAKING]: calm traveling sine-wave / breathing pulse.
 * - [DuplexVoiceController.DuplexState.IDLE] / [DuplexVoiceController.DuplexState.ERROR]: minimal resting dots.
 */
class VoiceWaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }

    private val barRect = RectF()
    private val barHeights = FloatArray(BAR_COUNT) { MIN_BAR_FRACTION }
    private val targetBarHeights = FloatArray(BAR_COUNT) { MIN_BAR_FRACTION }
    private val bellEnvelope = FloatArray(BAR_COUNT) { index ->
        val center = (BAR_COUNT - 1) / 2f
        val normDist = (index - center) / center
        exp(-BELL_FALLOFF * normDist * normDist)
    }

    private var voiceState: DuplexVoiceController.DuplexState = DuplexVoiceController.DuplexState.IDLE
    private var targetAmplitude: Float = 0f
    private var smoothedAmplitude: Float = 0f
    private var phase: Float = 0f
    private var lastFrameNanos: Long = 0L

    private var animator: ValueAnimator? = null

    /**
     * Updates the target audio amplitude from a raw RMS reading.
     * Non-finite values are clamped to `0f`, and the normalized level is interpolated smoothly.
     */
    fun setAmplitude(rms: Float) {
        val safeRms = if (rms.isFinite()) rms else 0f
        val scaled = (safeRms * RMS_SCALE).coerceIn(0f, 1f)
        targetAmplitude = scaled.pow(RMS_GAMMA)
        if (isAttachedToWindow && isShown && animator == null && (!isRestingState() || !isSettledAtRest())) {
            startAnimator()
        }
    }

    /**
     * Switches the visualizer's animation mode to match [state].
     */
    fun setVoiceState(state: DuplexVoiceController.DuplexState) {
        voiceState = state
        if (state != DuplexVoiceController.DuplexState.LISTENING) {
            targetAmplitude = 0f
        }
        if (isAttachedToWindow && isShown && animator == null && (!isRestingState() || !isSettledAtRest())) {
            startAnimator()
        }
        invalidate()
    }

    /**
     * Stops and cleans up the internal [ValueAnimator] to prevent leaks.
     * Safe to invoke multiple times.
     */
    fun release() {
        stopAnimator()
        targetAmplitude = 0f
        smoothedAmplitude = 0f
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (isShown && (!isRestingState() || !isSettledAtRest())) {
            startAnimator()
        }
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (isShown && isAttachedToWindow) {
            if (!isRestingState() || !isSettledAtRest()) {
                startAnimator()
            }
        } else {
            stopAnimator()
        }
    }

    override fun onDetachedFromWindow() {
        release()
        super.onDetachedFromWindow()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val defaultWidth = dp(context, DEFAULT_WIDTH_DP) + paddingLeft + paddingRight
        val defaultHeight = dp(context, DEFAULT_HEIGHT_DP) + paddingTop + paddingBottom
        setMeasuredDimension(
            resolveSize(defaultWidth, widthMeasureSpec),
            resolveSize(defaultHeight, heightMeasureSpec),
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val availWidth = (width - paddingLeft - paddingRight).toFloat()
        val availHeight = (height - paddingTop - paddingBottom).toFloat()
        if (availWidth <= 0f || availHeight <= 0f) return

        val maxBarWidth = (availHeight * MAX_BAR_WIDTH_HEIGHT_RATIO).coerceAtLeast(2f)
        val barWidth = (availWidth / (BAR_COUNT * 1.9f)).coerceIn(2f, maxBarWidth)
        val maxGap = if (BAR_COUNT > 1) {
            ((availWidth - BAR_COUNT * barWidth) / (BAR_COUNT - 1)).coerceAtLeast(0f)
        } else {
            0f
        }
        val gap = (barWidth * 0.9f).coerceAtMost(maxGap)
        val totalWidth = BAR_COUNT * barWidth + (BAR_COUNT - 1) * gap
        val startX = paddingLeft + (availWidth - totalWidth) / 2f
        val centerY = paddingTop + availHeight / 2f
        val maxBarHeight = availHeight * MAX_HEIGHT_FRACTION
        val isResting = isRestingState()

        for (i in 0 until BAR_COUNT) {
            val level = barHeights[i].coerceIn(MIN_BAR_FRACTION, 1f)
            val barHeight = maxOf(barWidth, maxBarHeight * level).coerceAtMost(availHeight)
            val cornerRadius = minOf(barWidth, barHeight) / 2f
            val left = startX + i * (barWidth + gap)
            val top = centerY - barHeight / 2f

            val baseAlpha = if (isResting) RESTING_ALPHA else ACTIVE_MIN_ALPHA
            val alphaRange = if (isResting) 40f else (255 - ACTIVE_MIN_ALPHA).toFloat()
            barPaint.alpha = (baseAlpha + alphaRange * level).toInt().coerceIn(RESTING_ALPHA, 255)

            barRect.set(left, top, left + barWidth, top + barHeight)
            canvas.drawRoundRect(barRect, cornerRadius, cornerRadius, barPaint)
        }
    }

    private fun startAnimator() {
        if (animator != null) return
        lastFrameNanos = System.nanoTime()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ANIMATOR_PERIOD_MS
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { stepFrame() }
            start()
        }
    }

    private fun stopAnimator() {
        animator?.apply {
            removeAllUpdateListeners()
            removeAllListeners()
            cancel()
        }
        animator = null
        lastFrameNanos = 0L
    }

    private fun isRestingState(): Boolean =
        voiceState == DuplexVoiceController.DuplexState.IDLE ||
            voiceState == DuplexVoiceController.DuplexState.ERROR

    private fun isSettledAtRest(): Boolean {
        for (i in 0 until BAR_COUNT) {
            if (abs(barHeights[i] - MIN_BAR_FRACTION) >= SETTLE_EPSILON) {
                return false
            }
        }
        return true
    }

    private fun stepFrame() {
        val nowNanos = System.nanoTime()
        val rawDt = if (lastFrameNanos == 0L) DEFAULT_DT_SEC else (nowNanos - lastFrameNanos) / 1_000_000_000f
        lastFrameNanos = nowNanos
        val dt = rawDt.coerceIn(0.001f, MAX_DT_SEC)

        val ampLerp = 1f - exp(-AMPLITUDE_RESPONSE * dt)
        smoothedAmplitude += (targetAmplitude - smoothedAmplitude) * ampLerp

        when (voiceState) {
            DuplexVoiceController.DuplexState.LISTENING -> {
                phase = (phase + dt * LISTENING_PHASE_SPEED) % TWO_PI
                val activeLevel = smoothedAmplitude.coerceIn(0f, 1f)
                for (i in 0 until BAR_COUNT) {
                    val ripple = 0.90f + 0.10f * sin(phase + i * 0.65f)
                    val height = MIN_BAR_FRACTION +
                        (1f - MIN_BAR_FRACTION) * activeLevel * bellEnvelope[i] * ripple
                    targetBarHeights[i] = height.coerceIn(MIN_BAR_FRACTION, 1f)
                }
            }

            DuplexVoiceController.DuplexState.TRANSCRIBING -> {
                updateTravelingWave(dt, speed = 5.4f, amplitude = 0.42f, base = 0.22f)
            }

            DuplexVoiceController.DuplexState.GENERATING -> {
                updateTravelingWave(dt, speed = 3.4f, amplitude = 0.36f, base = 0.18f)
            }

            DuplexVoiceController.DuplexState.SPEAKING -> {
                updateTravelingWave(dt, speed = 4.8f, amplitude = 0.56f, base = 0.24f)
            }

            DuplexVoiceController.DuplexState.IDLE,
            DuplexVoiceController.DuplexState.ERROR -> {
                for (i in 0 until BAR_COUNT) {
                    targetBarHeights[i] = MIN_BAR_FRACTION
                }
            }
        }

        val barLerp = 1f - exp(-BAR_SPRING_STIFFNESS * dt)
        for (i in 0 until BAR_COUNT) {
            barHeights[i] += (targetBarHeights[i] - barHeights[i]) * barLerp
        }
        if (isRestingState() && isSettledAtRest()) {
            for (i in 0 until BAR_COUNT) {
                barHeights[i] = MIN_BAR_FRACTION
            }
            invalidate()
            post {
                if (isAttachedToWindow && isRestingState() && isSettledAtRest()) {
                    stopAnimator()
                }
            }
            return
        }
        invalidate()
    }

    private fun updateTravelingWave(dt: Float, speed: Float, amplitude: Float, base: Float) {
        phase = (phase + dt * speed) % TWO_PI
        for (i in 0 until BAR_COUNT) {
            val wave = 0.5f + 0.5f * sin(phase - i * WAVE_SPATIAL_STEP)
            val envelope = 0.55f + 0.45f * bellEnvelope[i]
            targetBarHeights[i] = (base + amplitude * wave * envelope).coerceIn(MIN_BAR_FRACTION, 1f)
        }
    }

    private companion object {
        const val BAR_COUNT = 20
        const val DEFAULT_WIDTH_DP = 220
        const val DEFAULT_HEIGHT_DP = 52
        const val MIN_BAR_FRACTION = 0.08f
        const val MAX_HEIGHT_FRACTION = 0.92f
        const val MAX_BAR_WIDTH_HEIGHT_RATIO = 0.16f
        const val SETTLE_EPSILON = 0.002f
        const val BELL_FALLOFF = 2.4f
        const val RMS_SCALE = 3.2f
        const val RMS_GAMMA = 0.85f
        const val AMPLITUDE_RESPONSE = 18f
        const val BAR_SPRING_STIFFNESS = 14f
        const val LISTENING_PHASE_SPEED = 6.0f
        const val WAVE_SPATIAL_STEP = 0.45f
        const val RESTING_ALPHA = 95
        const val ACTIVE_MIN_ALPHA = 115
        const val ANIMATOR_PERIOD_MS = 1000L
        const val DEFAULT_DT_SEC = 1f / 60f
        const val MAX_DT_SEC = 0.05f
        const val TWO_PI = (Math.PI * 2.0).toFloat()
    }
}
