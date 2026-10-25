package com.example.referencecamera.model

import org.junit.Assert.*
import org.junit.Test

class ModelTilingTest {
    @Test fun coresCoverOddCanvasExactlyOnceAndContextStaysInsideIt() {
        val width = 205; val height = 121
        val coverage = IntArray(width * height)
        val tiles = ModelTiling.plan(width, height)
        assertEquals(6, tiles.size)
        for (tile in tiles) {
            assertTrue(tile.left >= 0 && tile.top >= 0 && tile.right <= width && tile.bottom <= height)
            assertEquals(maxOf(0, tile.x - ModelTiling.HALO), tile.left)
            assertEquals(minOf(width, tile.x + tile.width + ModelTiling.HALO), tile.right)
            for (y in tile.y until tile.y + tile.height) for (x in tile.x until tile.x + tile.width) coverage[y * width + x]++
        }
        assertTrue(coverage.all { it == 1 })
    }

    @Test fun tinyCanvasDoesNotRequireArtificialPadding() {
        assertEquals(ModelTile(0, 0, 1, 3, 0, 0, 1, 3), ModelTiling.plan(1, 3).single())
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidCoreIsRejected() { ModelTiling.plan(100, 100, core = 0) }
}