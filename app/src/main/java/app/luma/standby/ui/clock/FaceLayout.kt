package app.luma.standby.ui.clock

/** Pixel geometry shared by the renderer and JVM regression tests. */
data class FaceBox(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top
}

data class FaceLayout(val clock: FaceBox, val date: FaceBox, val rightTiles: List<FaceBox>, val leftTile: FaceBox?) {
    companion object {
        fun create(width: Float, height: Float, withWidgets: Boolean): FaceLayout {
            val w = width.coerceAtLeast(1f)
            val h = height.coerceAtLeast(1f)
            val margin = minOf(w, h) * 0.065f
            if (!withWidgets) return FaceLayout(
                FaceBox(margin, h * .13f, w - margin, h * .75f),
                FaceBox(margin, h * .78f, w - margin, h * .88f), emptyList(), null,
            )
            if (w >= h) {
                val split = w * .70f
                val rail = split + margin * .45f
                return FaceLayout(
                    FaceBox(w * .13f, h * .15f, split - margin * .5f, h * .86f),
                    FaceBox(rail, h * .10f, w - margin, h * .23f),
                    (0..2).map { i -> FaceBox(rail, h * (.30f + i * .21f), w - margin, h * (.48f + i * .21f)) },
                    FaceBox(margin, h * .89f, split - margin * .5f, h * .97f),
                )
            }
            return FaceLayout(
                FaceBox(margin, h * .18f, w - margin, h * .48f),
                FaceBox(margin, h * .51f, w - margin, h * .57f),
                (0..2).map { i -> FaceBox(margin, h * (.61f + i * .10f), w - margin, h * (.69f + i * .10f)) },
                FaceBox(margin, h * .91f, w - margin, h * .97f),
            )
        }
    }
}
