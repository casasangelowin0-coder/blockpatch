package com.blockpatch.game

import android.content.Context
import android.graphics.*

/** Pixel-art sprite sheet (16 columns of 16px tiles) shared by the game and the UI. */
object Atlas {
    const val TILES = 96          // 6 rows of 16 tiles (the first 64 are the original art)
    lateinit var bmp: Bitmap
        private set
    val avg = IntArray(TILES)
    private val seedFilters = arrayOfNulls<ColorFilter>(TILES)
    private var ready = false
    private val src = Rect()

    @Synchronized fun init(ctx: Context) {
        if (ready) return
        val o = BitmapFactory.Options().apply { inScaled = false; inPreferredConfig = Bitmap.Config.ARGB_8888 }
        bmp = ctx.assets.open("img/tiles.png").use { BitmapFactory.decodeStream(it, null, o)!! }
        val px = IntArray(256)
        for (i in 0 until TILES) {
            if ((i / 16) * 16 + 16 > bmp.height) break
            bmp.getPixels(px, 0, 16, (i % 16) * 16, (i / 16) * 16, 16, 16)
            var r = 0; var g = 0; var b = 0; var n = 0
            for (p in px) if ((p ushr 24) > 128) { r += (p shr 16) and 255; g += (p shr 8) and 255; b += p and 255; n++ }
            avg[i] = if (n == 0) 0xFF888888.toInt() else Color.rgb(r / n, g / n, b / n)
        }
        ready = true
    }

    fun seedFilter(block: Int): ColorFilter {
        val i = block.coerceIn(0, TILES - 1)
        return seedFilters[i] ?: PorterDuffColorFilter(avg[i], PorterDuff.Mode.MULTIPLY).also { seedFilters[i] = it }
    }

    /** Draws an inventory icon for any item id into [dst]. */
    @Synchronized fun icon(c: Canvas, id: Int, dst: Rect, p: Paint) {
        val seed = ItemDB.isSeed(id)
        val idx = (if (seed) Id.T_SEED else ItemDB[id].tile).coerceIn(0, TILES - 1)
        src.set((idx % 16) * 16, (idx / 16) * 16, (idx % 16) * 16 + 16, (idx / 16) * 16 + 16)
        p.colorFilter = if (seed) seedFilter(ItemDB.blockOfSeed(id)) else null
        c.drawBitmap(bmp, src, dst, p)
        p.colorFilter = null
    }
}
