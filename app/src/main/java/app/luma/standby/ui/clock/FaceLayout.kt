package app.luma.standby.ui.clock

import app.luma.standby.domain.model.FaceStyle
import kotlin.math.max
import kotlin.math.min

/** Pixel geometry shared by the renderer and JVM regression tests. */
data class FaceBox(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top
    val centerX get() = (left + right) * 0.5f
    val centerY get() = (top + bottom) * 0.5f
}

data class FaceLayout(
    val clock: FaceBox,
    val date: FaceBox,
    val rightTiles: List<FaceBox>,
    val leftTile: FaceBox?,
) {
    companion object {
        /**
         * Two clean iOS 26/27 StandBy landscape paradigms:
         * - [FaceStyle.ANALOG] & [FaceStyle.SPLIT]: centered 50/50 symmetric dual squircle
         *   stage (left clock card + right calendar/widget card, top- and bottom-aligned).
         * - [FaceStyle.FLOAT] & [FaceStyle.SOLAR]: true edge-to-edge fullscreen clock with
         *   a sleek top status bar for date and optional compact glass widget pills.
         */
        fun create(
            width: Float,
            height: Float,
            withWidgets: Boolean,
            style: FaceStyle = FaceStyle.SPLIT,
        ): FaceLayout {
            val w = width.coerceAtLeast(1f)
            val h = height.coerceAtLeast(1f)

            if (w >= h) {
                if (style == FaceStyle.FLOAT || style == FaceStyle.SOLAR) {
                    return FaceLayout(
                        clock = FaceBox(w * 0.032f, h * 0.135f, w * 0.968f, h * 0.905f),
                        date = FaceBox(w * 0.05f, h * 0.042f, w * 0.95f, h * 0.128f),
                        rightTiles = emptyList(),
                        leftTile = null,
                    )
                }

                // Centered symmetric dual-card stage modeled 1:1 on iPhone StandBy.
                val stageTop = h * 0.085f
                val stageBottom = h * 0.905f
                val stageH = stageBottom - stageTop
                val outerX = max(w * 0.052f, h * 0.075f)
                val gutter = min(w * 0.036f, stageH * 0.088f)
                val maxCardW = ((w - outerX * 2f - gutter) * 0.5f).coerceAtLeast(1f)
                val cardW = min(maxCardW, stageH * 1.06f)
                val totalW = cardW * 2f + gutter
                val leftX = (w - totalW) * 0.5f
                val rightX = leftX + cardW + gutter

                val clockBox = FaceBox(leftX, stageTop, leftX + cardW, stageBottom)
                val rightStage = FaceBox(rightX, stageTop, rightX + cardW, stageBottom)

                val tiles = if (!withWidgets) {
                    emptyList()
                } else {
                    val gap = stageH * 0.045f
                    val tileH = (stageH - gap * 2f) / 3f
                    (0..2).map { i ->
                        val top = stageTop + i * (tileH + gap)
                        FaceBox(rightX, top, rightX + cardW, top + tileH)
                    }
                }

                val dateBox = if (!withWidgets) {
                    rightStage
                } else {
                    FaceBox(rightX, h * 0.01f, rightX + cardW, stageTop * 0.85f)
                }

                val leftTileBox = if (!withWidgets) {
                    null
                } else {
                    FaceBox(leftX, h * 0.922f, leftX + cardW, h * 0.985f)
                }

                return FaceLayout(
                    clock = clockBox,
                    date = dateBox,
                    rightTiles = tiles,
                    leftTile = leftTileBox,
                )
            }

            // Portrait fallback: clean vertical stack.
            val margin = w * 0.06f
            val tiles = if (!withWidgets) {
                emptyList()
            } else {
                (0..2).map { i ->
                    FaceBox(margin, h * (0.61f + i * 0.10f), w - margin, h * (0.69f + i * 0.10f))
                }
            }
            return FaceLayout(
                clock = FaceBox(margin, h * 0.14f, w - margin, h * 0.48f),
                date = FaceBox(margin, h * 0.50f, w - margin, h * 0.57f),
                rightTiles = tiles,
                leftTile = if (!withWidgets) null else FaceBox(margin, h * 0.91f, w - margin, h * 0.97f),
            )
        }
    }
}
