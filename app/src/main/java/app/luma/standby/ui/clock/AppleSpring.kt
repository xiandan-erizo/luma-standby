package app.luma.standby.ui.clock

import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pure analytical Apple UIKit / SwiftUI damped spring physics helpers.
 *
 * Unlike `easeOutCubic` or steep Bezier curves (which inject an abrupt frame-1 velocity
 * cliff), physical springs start from zero velocity (`f'(0) = 0`) for system-initiated
 * entrances/morphs and inherit exact thumb release velocity (`v0`) for interactive gestures.
 */
internal object AppleSpring {

    /**
     * Smooth C1-continuous Hermite curve (`f(0)=0, f(1)=1, f'(0)=f'(1)=0`), ideal for
     * optical blur crossfades and luminance/opacity transitions without sudden pops.
     */
    fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
        val span = (edge1 - edge0).coerceAtLeast(0.0001f)
        val t = ((x - edge0) / span).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /**
     * Smoother C2-continuous quintic curve (`f'(0)=f''(0)=f'(1)=f''(1)=0`), eliminating
     * acceleration jumps on calm optical dissolves and backdrop settles.
     */
    fun smootherstep(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return x * x * x * (x * (x * 6f - 15f) + 10f)
    }

    /**
     * Normalized physical damped harmonic oscillator (`x(0)=0, x'(0)=0, x(1)=1`) over
     * duration [durationSec] with natural period [responseSec] and [dampingRatio].
     *
     * - `dampingRatio >= 1.0f`: critically damped — zero overshoot, silky asymptotic rest.
     * - `dampingRatio = 0.88..0.95f`: Apple StandBy feel — organic cushion, imperceptible settle.
     */
    fun evaluate(
        fraction: Float,
        durationSec: Float,
        responseSec: Float,
        dampingRatio: Float,
    ): Float {
        val f = fraction.coerceIn(0f, 1f)
        if (f <= 0f) return 0f
        if (f >= 1f) return 1f

        val omega = (2.0 * Math.PI) / responseSec.coerceAtLeast(0.05f).toDouble()
        val zeta = dampingRatio.coerceIn(0.25f, 1.5f).toDouble()

        fun raw(tSec: Double): Double {
            if (zeta < 0.999) {
                val wd = omega * sqrt(1.0 - zeta * zeta)
                val decay = exp(-zeta * omega * tSec)
                return 1.0 - decay * (cos(wd * tSec) + (zeta * omega / wd) * sin(wd * tSec))
            }
            val decay = exp(-omega * tSec)
            return 1.0 - decay * (1.0 + omega * tSec)
        }

        val endVal = raw(durationSec.toDouble()).coerceAtLeast(0.001)
        return (raw((f * durationSec).toDouble()) / endVal).toFloat()
    }

    /**
     * Closed-form displacement of a damped spring settling toward `0` from initial
     * offset [x0] and initial thumb release velocity [v0] (in pixels/second).
     *
     * Preserves exact velocity continuity at `t = 0` (`x(0) = x0, x'(0) = v0`), eliminating
     * the robotic brake-and-restart feel when a user lets go of a drag.
     */
    fun settleDisplacement(
        elapsedSec: Float,
        x0: Float,
        v0: Float,
        responseSec: Float = 0.42f,
        dampingRatio: Float = 0.86f,
    ): Float {
        if (elapsedSec <= 0f) return x0
        val t = elapsedSec.toDouble()
        val omega = (2.0 * Math.PI) / responseSec.coerceAtLeast(0.05f).toDouble()
        val zeta = dampingRatio.coerceIn(0.4f, 1.2f).toDouble()
        val a = x0.toDouble()
        val v = v0.toDouble()

        return if (zeta < 0.999) {
            val wd = omega * sqrt(1.0 - zeta * zeta)
            val b = (v + zeta * omega * a) / wd
            (exp(-zeta * omega * t) * (a * cos(wd * t) + b * sin(wd * t))).toFloat()
        } else {
            val b = v + omega * a
            (exp(-omega * t) * (a + b * t)).toFloat()
        }
    }

    /**
     * Velocity-preserving smooth glide from [startPos] to [targetPos] with initial velocity [v0],
     * using a critically damped spring shifted to [targetPos] so fast flicks coast briskly
     * while slow swipes glide calmly without jarring speed changes.
     */
    fun glideWithVelocity(
        fraction: Float,
        durationSec: Float,
        startPos: Float,
        targetPos: Float,
        v0: Float,
        responseSec: Float = 0.36f,
    ): Float {
        val f = fraction.coerceIn(0f, 1f)
        if (f <= 0f) return startPos
        if (f >= 1f) return targetPos

        val x0 = startPos - targetPos
        val omega = (2.0 * Math.PI) / responseSec.coerceAtLeast(0.08f).toDouble()
        val t = (f * durationSec).toDouble()
        val tEnd = durationSec.toDouble()
        // Clamp runaway throw velocity so extreme flicks never overshoot far past target
        val clampedV = v0.coerceIn(-4_500f, 4_500f).toDouble()

        fun disp(s: Double): Double = exp(-omega * s) * (x0.toDouble() + (clampedV + omega * x0.toDouble()) * s)
        val cur = disp(t)
        val residual = disp(tEnd)
        // Smoothly subtract tiny end residual so it lands cleanly at targetPos at f == 1
        val adjusted = cur - residual * (f * f)
        return (targetPos + adjusted).toFloat()
    }
}
