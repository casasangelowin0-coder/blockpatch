package com.blockpatch.game

import android.content.Context
import android.util.SparseIntArray
import java.util.Random

/** Player data: persisted in SharedPreferences. Thread-safe (UI thread + game thread). */
class Profile(ctx: Context) {
    companion object {
        val COLORS = intArrayOf(0xFF2F7D5B.toInt(), 0xFFD9534F.toInt(), 0xFF4A7FD1.toInt(), 0xFFE0A030.toInt(), 0xFF8E5BC4.toInt())
        fun xpFor(level: Int) = 60 + 40 * level
    }

    private val sp = ctx.applicationContext.getSharedPreferences("bp2", Context.MODE_PRIVATE)
    var uid = ""
    var name = ""
    var color = 0
    var gems = 0
    var xp = 0
    var quest = 0
    var lastDaily = 0L
    var sound = true
    var music = true
    var showFps = false
    var fpsLimit = 60      // 0 = unlimited
    var lowGfx = false
    var lastWorldName = "start"
    var sel = Id.FIST
    val inv = SparseIntArray()
    val equip = IntArray(5)
    val quick = IntArray(5)
    val notes = ArrayList<String>()

    init { load() }

    @Synchronized fun reload() { load() }

    private fun load() {
        inv.clear(); notes.clear()
        uid = sp.getString("uid", null) ?: java.lang.Long.toString(Random().nextLong() and 0xFFFFFFFFFFL, 36)
        name = sp.getString("name", null) ?: "Player" + (100 + Random().nextInt(900))
        color = sp.getInt("color", 0).coerceIn(0, COLORS.size - 1)
        gems = sp.getInt("gems", 100); xp = sp.getInt("xp", 0); quest = sp.getInt("quest", 0)
        lastDaily = sp.getLong("daily", 0L)
        sound = sp.getBoolean("sound", true); music = sp.getBoolean("music", true)
        showFps = sp.getBoolean("fps", false); fpsLimit = sp.getInt("fpslim", 60); lowGfx = sp.getBoolean("low", false)
        sel = sp.getInt("sel", Id.FIST); lastWorldName = sp.getString("lastw", "start") ?: "start"
        val s = sp.getString("inv", null)
        if (s == null) {
            inv.put(Id.DIRT, 20); inv.put(Id.WOOD, 10); inv.put(Id.ROCK, 10)
            inv.put(Id.SEED + Id.DIRT, 3); inv.put(Id.WORLD_LOCK, 1)
        } else for (p in s.split(',')) {
            val kv = p.split(':'); if (kv.size == 2) inv.put(kv[0].toIntOrNull() ?: continue, kv[1].toIntOrNull() ?: continue)
        }
        sp.getString("equip", null)?.split(',')?.forEachIndexed { i, v -> if (i < equip.size) equip[i] = v.toIntOrNull() ?: 0 }
        sp.getString("quick", null)?.split(',')?.forEachIndexed { i, v -> if (i < quick.size) quick[i] = v.toIntOrNull() ?: 0 }
        sp.getString("notes", null)?.split('\n')?.filter { it.isNotBlank() }?.let { notes.addAll(it) }
        if (quick.all { it == 0 }) {
            var q = 0
            for (i in 0 until inv.size()) { val id = inv.keyAt(i); if (q < quick.size && ItemDB[id].placeable) quick[q++] = id }
        }
        if (inv.get(sel, 0) <= 0 && sel != Id.FIST) sel = Id.FIST
    }

    @Synchronized fun save() {
        val sb = StringBuilder()
        for (i in 0 until inv.size()) if (inv.valueAt(i) > 0) sb.append(inv.keyAt(i)).append(':').append(inv.valueAt(i)).append(',')
        sp.edit().putString("uid", uid).putString("name", name).putInt("color", color).putInt("gems", gems)
            .putInt("xp", xp).putInt("quest", quest).putLong("daily", lastDaily)
            .putBoolean("sound", sound).putBoolean("music", music).putBoolean("fps", showFps)
            .putString("lastw", lastWorldName).putInt("fpslim", fpsLimit).putBoolean("low", lowGfx).putInt("sel", sel)
            .putString("inv", sb.toString()).putString("equip", equip.joinToString(","))
            .putString("quick", quick.joinToString(",")).putString("notes", notes.joinToString("\n"))
            .apply()
    }

    @Synchronized fun count(id: Int) = inv.get(id, 0)
    @Synchronized fun add(id: Int, n: Int) { inv.put(id, inv.get(id, 0) + n) }
    @Synchronized fun take(id: Int, n: Int): Boolean {
        val c = inv.get(id, 0); if (c < n) return false
        if (c == n) inv.delete(id) else inv.put(id, c - n)
        if (c == n) onDepleted(id)
        return true
    }
    private fun onDepleted(id: Int) {
        for (i in quick.indices) if (quick[i] == id) quick[i] = 0
        if (sel == id) sel = Id.FIST
    }
    @Synchronized fun addGems(n: Int) { gems += n }
    @Synchronized fun addQuest() { quest++ }
    @Synchronized fun spend(g: Int): Boolean { if (gems < g) return false; gems -= g; return true }

    @Synchronized fun allItems(): List<Pair<Int, Int>> {
        val out = ArrayList<Pair<Int, Int>>()
        for (i in 0 until inv.size()) if (inv.valueAt(i) > 0) out.add(inv.keyAt(i) to inv.valueAt(i))
        return out
    }

    @Synchronized fun select(id: Int) {
        sel = id
        if (id == Id.FIST) return
        val l = quick.filter { it != 0 && it != id }.toMutableList()
        l.add(0, id)
        for (i in quick.indices) quick[i] = if (i < l.size) l[i] else 0
    }

    @Synchronized fun isEquipped(id: Int): Boolean { val s = ItemDB[id].slot ?: return false; return equip[s.ordinal] == id }
    @Synchronized fun toggleEquip(id: Int): Boolean {
        val s = ItemDB[id].slot ?: return false
        if (equip[s.ordinal] == id) { equip[s.ordinal] = 0; return false }
        equip[s.ordinal] = id; return true
    }
    fun worn(slot: Slot) = equip[slot.ordinal]
    val hasWings: Boolean get() = worn(Slot.BACK) != 0
    val hasPickaxe: Boolean get() = worn(Slot.HAND) == Id.PICKAXE

    @Synchronized fun level(): Int { var l = 1; var x = xp; while (x >= xpFor(l)) { x -= xpFor(l); l++ }; return l }
    @Synchronized fun levelProgress(): Float { var l = 1; var x = xp; while (x >= xpFor(l)) { x -= xpFor(l); l++ }; return x.toFloat() / xpFor(l) }
    /** Returns the new level if the player levelled up, else 0. */
    @Synchronized fun addXp(n: Int): Int { val before = level(); xp += n; val after = level(); return if (after > before) after else 0 }

    @Synchronized fun addNote(s: String) { notes.add(0, s); while (notes.size > 30) notes.removeAt(notes.size - 1) }

    fun dailyReady() = System.currentTimeMillis() - lastDaily >= 20L * 3600_000L
    @Synchronized fun claimDaily(): Int {
        if (!dailyReady()) return 0
        lastDaily = System.currentTimeMillis()
        val g = 50 + level() * 5; gems += g; addNote("Daily reward: +$g gems"); return g
    }
}
