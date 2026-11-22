package com.blockpatch.game

import android.content.Context
import android.graphics.*

/** Pixel-art sprite sheet (16 columns of 16px tiles) shared by the game and the UI. */
object Atlas {
    lateinit var bmp: Bitmap
        private set
    val avg = IntArray(64)
    private val seedFilters = arrayOfNulls<ColorFilter>(64)
    private var ready = false
    private val src = Rect()

    @Synchronized fun init(ctx: Context) {
        if (ready) return
        val o = BitmapFactory.Options().apply { inScaled = false; inPreferredConfig = Bitmap.Config.ARGB_8888 }
        bmp = ctx.assets.open("img/tiles.png").use { BitmapFactory.decodeStream(it, null, o)!! }
        val px = IntArray(256)
        for (i in 0 until 64) {
            bmp.getPixels(px, 0, 16, (i % 16) * 16, (i / 16) * 16, 16, 16)
            var r = 0; var g = 0; var b = 0; var n = 0
            for (p in px) if ((p ushr 24) > 128) { r += (p shr 16) and 255; g += (p shr 8) and 255; b += p and 255; n++ }
            avg[i] = if (n == 0) 0xFF888888.toInt() else Color.rgb(r / n, g / n, b / n)
        }
        ready = true
    }

    fun seedFilter(block: Int): ColorFilter {
        val i = block and 63
        return seedFilters[i] ?: PorterDuffColorFilter(avg[i], PorterDuff.Mode.MULTIPLY).also { seedFilters[i] = it }
    }

    /** Draws an inventory icon for any item id into [dst]. */
    @Synchronized fun icon(c: Canvas, id: Int, dst: Rect, p: Paint) {
        val seed = ItemDB.isSeed(id)
        val idx = if (seed) Id.T_SEED else ItemDB[id].tile
        src.set((idx % 16) * 16, (idx / 16) * 16, (idx % 16) * 16 + 16, (idx / 16) * 16 + 16)
        p.colorFilter = if (seed) seedFilter(ItemDB.blockOfSeed(id)) else null
        c.drawBitmap(bmp, src, dst, p)
        p.colorFilter = null
    }
}
