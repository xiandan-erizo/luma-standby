package app.luma.standby.ui.clock

import app.luma.standby.domain.model.FaceStyle

/** Pixel geometry shared by the renderer and JVM regression tests. */
data class FaceBox(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top
}

data class FaceLayout(
    val clock: FaceBox,
    val date: FaceBox,
    val rightTiles: List<FaceBox>,
    val leftTile: FaceBox?,
) {
    companion object {
        /**
         * Landscape reserves the right rail in [FaceStyle.SPLIT] whether or not widgets
         * are placed, so the date never jumps between layouts when the first widget is
         * added or removed. In [FaceStyle.FLOAT] without widgets, the clock expands to
         * fill the landscape stage with a compact top-right date header.
         */
        fun create(
            width: Float,
            height: Float,
            withWidgets: Boolean,
            style: FaceStyle = FaceStyle.SPLIT,
        ): FaceLayout {
            val w = width.coerceAtLeast(1f)
            val h = height.coerceAtLeast(1f)
            val margin = minOf(w, h) * 0.06f

            if (w >= h) {
                if (style == FaceStyle.FLOAT && !withWidgets) {
                    return FaceLayout(
                        clock = FaceBox(w * 0.055f, h * 0.155f, w * 0.945f, h * 0.895f),
                        date = FaceBox(w * 0.62f, h * 0.055f, w - margin, h * 0.14f),
                        rightTiles = emptyList(),
                        leftTile = null,
                    )
                }

                val split = w * 0.585f
                val rail = split + margin * 0.50f
                val dateBox = FaceBox(rail, h * 0.09f, w - margin, h * 0.24f)
                val tiles = if (!withWidgets) {
                    emptyList()
                } else {
                    (0..2).map { i ->
                        FaceBox(
                            rail,
                            h * (0.275f + i * 0.21f),
                            w - margin,
                            h * (0.46f + i * 0.21f),
                        )
                    }
                }
                return FaceLayout(
                    clock = FaceBox(w * 0.05f, h * 0.10f, split - margin * 0.35f, h * 0.87f),
                    date = dateBox,
                    rightTiles = tiles,
                    leftTile = if (!withWidgets) {
                        null
                    } else {
                        FaceBox(w * 0.05f, h * 0.895f, split - margin * 0.35f, h * 0.975f)
                    },
                )
            }

            val tiles = if (!withWidgets) {
                emptyList()
            } else {
                (0..2).map { i ->
                    FaceBox(margin, h * (0.61f + i * 0.10f), w - margin, h * (0.69f + i * 0.10f))
                }
            }
            return FaceLayout(
                clock = FaceBox(margin, h * 0.16f, w - margin, h * 0.47f),
                date = FaceBox(margin, h * 0.50f, w - margin, h * 0.57f),
                rightTiles = tiles,
                leftTile = if (!withWidgets) null else FaceBox(margin, h * 0.91f, w - margin, h * 0.97f),
            )
        }
    }
}
