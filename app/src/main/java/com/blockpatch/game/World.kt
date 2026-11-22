package com.blockpatch.game

import android.content.Context
import android.util.SparseArray
import java.io.*
import java.util.Random
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.math.floor
import kotlin.math.sin

class Tree(@JvmField val seed: Int, @JvmField val planted: Long) {
    fun growMs() = ItemDB[seed].growSec * 1000L
    fun ripeAt() = planted + growMs()
}

/** A claimed area: the lock block at (x,y) protects [tiles] (indices y*W+x). */
class AreaLock(
    @JvmField val id: Int, @JvmField val x: Int, @JvmField val y: Int, @JvmField val type: Int,
    @JvmField val ownerId: String, @JvmField val ownerName: String,
    @JvmField var mode: Int,              // Builder's Lock only: bit0 = others may build, bit1 = others may punch
    @JvmField val tiles: IntArray
)

class WorldInfo(val name: String, val type: Int, val ownerId: String, val ownerName: String, val modified: Long)

class World(val name: String) {
    companion object {
        const val W = 100
        const val H = 60
        const val SURF = 24
        const val NORMAL = 0
        const val HILLS = 1
        const val BLANK = 2
        private const val MAGIC = 0x42505733
        val TYPE_NAMES = arrayOf("Normal", "Hills", "Blank")

        fun norm(s: String?): String {
            val n = (s ?: "").lowercase().filter { it in 'a'..'z' || it in '0'..'9' }.take(20)
            return if (n.isEmpty()) "start" else n
        }
        private fun dir(ctx: Context) = File(ctx.filesDir, "worlds").also { it.mkdirs() }
        fun file(ctx: Context, name: String) = File(dir(ctx), norm(name) + ".bpw")
        fun exists(ctx: Context, name: String) = file(ctx, name).exists()
        fun delete(ctx: Context, name: String) { file(ctx, name).delete() }

        fun randomName(): String {
            val a = arrayOf("sky", "moon", "pixel", "golden", "mega", "tiny", "wild", "cozy", "lava", "star", "cloud", "iron")
            val b = arrayOf("farm", "land", "town", "base", "park", "fort", "mine", "isle", "hub", "garden", "shop", "castle")
            val r = Random()
            return a[r.nextInt(a.size)] + b[r.nextInt(b.size)] + r.nextInt(100)
        }

        fun list(ctx: Context): List<WorldInfo> {
            val out = ArrayList<WorldInfo>()
            for (f in dir(ctx).listFiles() ?: emptyArray()) {
                if (!f.name.endsWith(".bpw")) continue
                try {
                    DataInputStream(BufferedInputStream(GZIPInputStream(FileInputStream(f)))).use { d ->
                        if (d.readInt() != MAGIC) return@use
                        val type = d.readByte().toInt(); val oid = d.readUTF(); val on = d.readUTF()
                        out.add(WorldInfo(f.name.removeSuffix(".bpw"), type, oid, on, f.lastModified()))
                    }
                } catch (_: Exception) { }
            }
            out.sortByDescending { it.modified }
            return out
        }

        fun load(ctx: Context, name: String): World? {
            val f = file(ctx, name); if (!f.exists()) return null
            return try {
                DataInputStream(BufferedInputStream(GZIPInputStream(FileInputStream(f)))).use { d ->
                    if (d.readInt() != MAGIC) return null
                    val w = World(norm(name))
                    w.type = d.readByte().toInt()
                    val oid = d.readUTF(); w.ownerId = if (oid.isEmpty()) null else oid
                    w.ownerName = d.readUTF()
                    w.lockX = d.readInt(); w.lockY = d.readInt(); w.doorX = d.readInt(); w.doorY = d.readInt()
                    w.created = d.readLong()
                    d.readFully(w.fg); d.readFully(w.bg)
                    repeat(d.readInt()) { val k = d.readInt(); w.trees.put(k, Tree(d.readInt(), d.readLong())) }
                    repeat(d.readInt()) { val k = d.readInt(); w.links.put(k, d.readUTF()) }
                    repeat(d.readInt()) { val k = d.readInt(); w.texts.put(k, d.readUTF()) }
                    try {   // area locks (absent in older saves)
                        repeat(d.readInt()) {
                            val ax = d.readInt(); val ay = d.readInt(); val at = d.readInt()
                            val aoid = d.readUTF(); val aon = d.readUTF(); val am = d.readInt()
                            val n = d.readInt().coerceIn(0, 400)
                            val tiles = IntArray(n) { d.readInt() }
                            if (tiles.all { it in 0 until W * H }) w.addArea(ax, ay, at, aoid, aon, am, tiles)
                        }
                    } catch (_: EOFException) { }
                    w.dirty = false
                    w
                }
            } catch (e: Exception) { null }
        }

        // ---- deterministic noise
        private fun hash2(x: Int, y: Int, s: Int): Float {
            var h = x * 374761393 + y * 668265263 + s * 1274126177
            h = (h xor (h ushr 13)) * 1274126177
            h = h xor (h ushr 16)
            return (h and 0xFFFFFF) / 16777216f
        }
        private fun vnoise(x: Float, y: Float, s: Int): Float {
            val xi = floor(x).toInt(); val yi = floor(y).toInt()
            val xf = x - xi; val yf = y - yi
            val u = xf * xf * (3 - 2 * xf); val v = yf * yf * (3 - 2 * yf)
            val a = hash2(xi, yi, s); val b = hash2(xi + 1, yi, s)
            val c = hash2(xi, yi + 1, s); val d = hash2(xi + 1, yi + 1, s)
            return (a + (b - a) * u) + ((c + (d - c) * u) - (a + (b - a) * u)) * v
        }
    }

    @JvmField val fg = ByteArray(W * H)
    @JvmField val bg = ByteArray(W * H)
    @JvmField val trees = SparseArray<Tree>()
    @JvmField val links = SparseArray<String>()
    @JvmField val texts = SparseArray<String>()
    var type = NORMAL
    var ownerId: String? = null
    var ownerName = ""
    var lockX = -1
    var lockY = -1
    var doorX = W / 2
    var doorY = SURF - 1
    var created = System.currentTimeMillis()
    @Volatile var dirty = false

    fun inb(x: Int, y: Int) = x in 0 until W && y in 0 until H
    fun get(x: Int, y: Int): Int = if (!inb(x, y)) Id.BEDROCK else fg[y * W + x].toInt()

    fun set(x: Int, y: Int, id: Int) {
        if (!inb(x, y)) return
        val k = y * W + x
        fg[k] = id.toByte()
        trees.remove(k); links.remove(k); texts.remove(k)
        dirty = true
    }

    // ---- area locks (Small / Big / Huge / Builder's)
    @JvmField val claim = ShortArray(W * H)             // 0 = free, else AreaLock.id
    @JvmField val areas = HashMap<Int, AreaLock>()
    private var nextAreaId = 1

    fun areaAt(x: Int, y: Int): AreaLock? {
        if (!inb(x, y)) return null
        val c = claim[y * W + x].toInt()
        return if (c == 0) null else areas[c]
    }

    fun hasForeignAreas(uid: String) = areas.values.any { it.ownerId != uid }

    /** Flood-fills (8-way, nearest first) up to [max] free tiles around (tx,ty). Null if the origin is already claimed. */
    fun planClaim(tx: Int, ty: Int, max: Int): IntArray? {
        if (!inb(tx, ty) || claim[ty * W + tx].toInt() != 0) return null
        val out = IntArray(max); var n = 0
        val seen = HashSet<Int>(); val q = ArrayDeque<Int>()
        val k0 = ty * W + tx; q.add(k0); seen.add(k0)
        while (q.isNotEmpty() && n < max) {
            val k = q.removeFirst(); out[n++] = k
            val x = k % W; val y = k / W
            for (dy in -1..1) for (dx in -1..1) {
                if (dx == 0 && dy == 0) continue
                val nx = x + dx; val ny = y + dy
                if (!inb(nx, ny)) continue
                val nk = ny * W + nx
                if (nk in seen || claim[nk].toInt() != 0) continue
                val t = fg[nk].toInt()
                if (t == Id.BEDROCK || t == Id.MAIN_DOOR) continue
                seen.add(nk); q.add(nk)
            }
        }
        return out.copyOf(n)
    }

    fun addArea(x: Int, y: Int, type: Int, ownerId: String, ownerName: String, mode: Int, tiles: IntArray): AreaLock {
        val a = AreaLock(nextAreaId++, x, y, type, ownerId, ownerName, mode, tiles)
        areas[a.id] = a
        for (k in tiles) claim[k] = a.id.toShort()
        dirty = true
        return a
    }

    fun removeArea(a: AreaLock) {
        for (k in a.tiles) if (claim[k].toInt() == a.id) claim[k] = 0
        areas.remove(a.id); dirty = true
    }

    fun nextRipe(): Long {
        val now = System.currentTimeMillis(); var best = Long.MAX_VALUE
        for (i in 0 until trees.size()) { val t = trees.valueAt(i).ripeAt(); if (t > now && t < best) best = t }
        return if (best == Long.MAX_VALUE) 0L else best
    }

    fun generate() {
        val s = name.hashCode()
        val rnd = Random(s.toLong())
        doorX = 8 + rnd.nextInt(W - 16)
        val fx = (s and 1023) * 0.37f
        fun surfaceAt(x: Int): Int = when (type) {
            HILLS -> SURF - 2 + Math.round(3f * sin(x * 0.11f + fx) + 2f * sin(x * 0.27f + fx * 2) + 1.5f * sin(x * 0.05f + fx * 3))
            else -> SURF
        }
        val doorSurf = if (type == HILLS) surfaceAt(doorX) else SURF
        for (x in 0 until W) {
            val top = if (type == HILLS) (if (Math.abs(x - doorX) <= 2) doorSurf else surfaceAt(x)) else SURF
            for (y in 0 until H) {
                val k = y * W + x
                if (y >= H - 3) { fg[k] = Id.BEDROCK.toByte(); continue }
                if (type == BLANK || y < top) continue
                val d = y - top
                var id = Id.DIRT
                if (d > 3) {
                    val rockP = ((d - 4) / 24f).coerceIn(0f, 0.85f)
                    if (hash2(x, y, s) < rockP) id = Id.ROCK
                    if (d > 9 && hash2(x, y, s + 5) < 0.012f) id = Id.GOLD
                } else if (vnoise(x * 0.35f, y * 0.35f, s + 9) > 0.8f && d in 1..3) id = Id.SAND
                val nearDoor = Math.abs(x - doorX) <= 3 && d <= 8
                if (!nearDoor) {
                    if (d > 3 && y < H - 8 && vnoise(x * 0.17f, y * 0.22f, s + 1) > 0.78f) id = Id.AIR
                    else if (y >= H - 13 && vnoise(x * 0.23f, y * 0.3f, s + 2) > 0.66f) id = Id.LAVA
                }
                fg[k] = id.toByte()
                if (d >= 1) bg[k] = 1
            }
        }
        doorY = (if (type == HILLS) doorSurf else SURF) - 1
        if (type == BLANK) doorY = SURF - 1
        fg[(doorY + 1) * W + doorX] = Id.BEDROCK.toByte()
        fg[doorY * W + doorX] = Id.MAIN_DOOR.toByte()
        dirty = true
    }

    fun save(ctx: Context) {
        val f = file(ctx, name); val tmp = File(f.path + ".tmp")
        try {
            DataOutputStream(BufferedOutputStream(GZIPOutputStream(FileOutputStream(tmp)))).use { d ->
                d.writeInt(MAGIC); d.writeByte(type); d.writeUTF(ownerId ?: ""); d.writeUTF(ownerName)
                d.writeInt(lockX); d.writeInt(lockY); d.writeInt(doorX); d.writeInt(doorY); d.writeLong(created)
                d.write(fg); d.write(bg)
                d.writeInt(trees.size()); for (i in 0 until trees.size()) { d.writeInt(trees.keyAt(i)); val t = trees.valueAt(i); d.writeInt(t.seed); d.writeLong(t.planted) }
                d.writeInt(links.size()); for (i in 0 until links.size()) { d.writeInt(links.keyAt(i)); d.writeUTF(links.valueAt(i)) }
                d.writeInt(texts.size()); for (i in 0 until texts.size()) { d.writeInt(texts.keyAt(i)); d.writeUTF(texts.valueAt(i)) }
                d.writeInt(areas.size)
                for (a in areas.values) {
                    d.writeInt(a.x); d.writeInt(a.y); d.writeInt(a.type); d.writeUTF(a.ownerId); d.writeUTF(a.ownerName)
                    d.writeInt(a.mode); d.writeInt(a.tiles.size); for (k in a.tiles) d.writeInt(k)
                }
            }
            if (f.exists()) f.delete()
            tmp.renameTo(f)
            dirty = false
        } catch (e: Exception) { tmp.delete() }
    }
}
