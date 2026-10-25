package com.example.referencecamera.model

/** A core tile plus enough real neighbouring pixels for the model's receptive field. */
data class ModelTile(
    val x: Int, val y: Int, val width: Int, val height: Int,
    val left: Int, val top: Int, val right: Int, val bottom: Int
)

object ModelTiling {
    const val CORE = 96
    // SRVGGNetCompact has 34 padded 3x3 convolutions: a 34-pixel radius.
    const val HALO = 40

    fun plan(width: Int, height: Int, core: Int = CORE, halo: Int = HALO): List<ModelTile> {
        require(width > 0 && height > 0 && core > 0 && halo >= 0)
        return buildList {
            for (y in 0 until height step core) for (x in 0 until width step core) {
                val w = minOf(core, width - x); val h = minOf(core, height - y)
                add(ModelTile(x, y, w, h, maxOf(0, x - halo), maxOf(0, y - halo),
                    minOf(width, x + w + halo), minOf(height, y + h + halo)))
            }
        }
    }
}