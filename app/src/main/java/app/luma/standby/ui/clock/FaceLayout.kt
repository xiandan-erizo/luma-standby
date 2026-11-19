package app.luma.standby.ui.clock

/** Pixel geometry shared by the renderer and JVM regression tests. */
data class FaceBox(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top
}

data class FaceLayout(val clock: FaceBox, val date: FaceBox, val rightTiles: List<FaceBox>, val leftTile: FaceBox?) {
    companion object {
        /**
         * Landscape reserves the right rail whether or not widgets are placed, so the
         * date never jumps between layouts when the first widget is added or removed.
         * Portrait has no room for a rail, so the date sits under the clock instead.
         */
        fun create(width: Float, height: Float, withWidgets: Boolean): FaceLayout {
            val w = width.coerceAtLeast(1f)
            val h = height.coerceAtLeast(1f)
            val margin = minOf(w, h) * 0.065f

            if (w >= h) {
                val split = w * .70f
                val rail = split + margin * .45f
                val dateBox = FaceBox(rail, h * .10f, w - margin, h * .23f)
                val tiles = if (!withWidgets) emptyList() else
                    (0..2).map { i -> FaceBox(rail, h * (.30f + i * .21f), w - margin, h * (.48f + i * .21f)) }
                return FaceLayout(
                    FaceBox(w * .13f, h * .15f, split - margin * .5f, h * .86f),
                    dateBox,
                    tiles,
                    if (!withWidgets) null else FaceBox(margin, h * .89f, split - margin * .5f, h * .97f),
                )
            }

            val tiles = if (!withWidgets) emptyList() else
                (0..2).map { i -> FaceBox(margin, h * (.61f + i * .10f), w - margin, h * (.69f + i * .10f)) }
            return FaceLayout(
                FaceBox(margin, h * .18f, w - margin, h * .48f),
                FaceBox(margin, h * .51f, w - margin, h * .57f),
                tiles,
                FaceBox(margin, h * .91f, w - margin, h * .97f),
            )
        }
    }
}