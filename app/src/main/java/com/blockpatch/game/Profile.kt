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
    var lowGfx = false
    var ctrlScale = 100    // on-screen button size, percent
    var ctrlOp = 1         // button opacity level 0..3
    var leftHand = false   // swap movement / action buttons
    var hudScale = 100     // HUD + notification size, percent
    var zoomBtns = true
    var zoomPct = 100      // remembered camera zoom, percent
    var lastWorldName = "start"
    var sel = Id.FIST
    var pet = 0            // equipped companion item id, 0 = none
    var skyMode = 0        // 0 auto, 1 day, 2 sunset, 3 night
    var weatherMode = 0    // 0 auto, 1 clear, 2 rain, 3 snow
    val inv = SparseIntArray()
    val equip = IntArray(5)
    val quick = IntArray(5)
    val notes = ArrayList<String>()

    // ---- badges / daily quests
    val stats = SparseIntArray()
    val badges = HashSet<Int>()
    val dayStats = IntArray(Stat.SIZE)
    val questClaimed = BooleanArray(3)
    var questDay = 0L
    private val pendingBadges = ArrayList<Achievement>()

    init { load() }

    @Synchronized fun reload() { load() }

    private fun load() {
        inv.clear(); notes.clear(); stats.clear(); badges.clear()
        uid = sp.getString("uid", null) ?: java.lang.Long.toString(Random().nextLong() and 0xFFFFFFFFFFL, 36)
        name = sp.getString("name", null) ?: "Player" + (100 + Random().nextInt(900))
        color = sp.getInt("color", 0).coerceIn(0, COLORS.size - 1)
        gems = sp.getInt("gems", 100); xp = sp.getInt("xp", 0); quest = sp.getInt("quest", 0)
        lastDaily = sp.getLong("daily", 0L)
        sound = sp.getBoolean("sound", true); music = sp.getBoolean("music", true)
        lowGfx = sp.getBoolean("low", false)
        ctrlScale = sp.getInt("cscale", 100).coerceIn(70, 160); ctrlOp = sp.getInt("cop", 1).coerceIn(0, 3)
        leftHand = sp.getBoolean("lefth", false); hudScale = sp.getInt("hscale", 100).coerceIn(80, 150)
        zoomBtns = sp.getBoolean("zbtn", true); zoomPct = sp.getInt("zoom", 100).coerceIn(50, 250)
        sel = sp.getInt("sel", Id.FIST); lastWorldName = sp.getString("lastw", "start") ?: "start"
        pet = sp.getInt("pet", 0)
        skyMode = sp.getInt("sky", 0).coerceIn(0, 3)
        weatherMode = sp.getInt("weather", 0).coerceIn(0, 3)
        questDay = sp.getLong("qday", 0L)
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
        sp.getString("badgestr", null)?.split(',')?.forEach { v -> v.toIntOrNull()?.let { badges.add(it) } }
        sp.getString("stats", null)?.split(',')?.forEach {
            val kv = it.split(':'); if (kv.size == 2) { val k = kv[0].toIntOrNull(); val n = kv[1].toIntOrNull(); if (k != null && n != null) stats.put(k, n) }
        }
        sp.getString("qstats", null)?.split(',')?.forEachIndexed { i, v -> if (i < dayStats.size) dayStats[i] = v.toIntOrNull() ?: 0 }
        sp.getString("qclaim", null)?.split(',')?.forEachIndexed { i, v -> if (i < questClaimed.size) questClaimed[i] = v.trim() == "1" }
        if (quick.all { it == 0 }) {
            var q = 0
            for (i in 0 until inv.size()) { val id = inv.keyAt(i); if (q < quick.size && ItemDB[id].placeable) quick[q++] = id }
        }
        if (inv.get(sel, 0) <= 0 && sel != Id.FIST) sel = Id.FIST
        checkDay()
    }

    @Synchronized fun save() {
        val sb = StringBuilder()
        for (i in 0 until inv.size()) if (inv.valueAt(i) > 0) sb.append(inv.keyAt(i)).append(':').append(inv.valueAt(i)).append(',')
        val st = StringBuilder()
        for (i in 0 until stats.size()) st.append(stats.keyAt(i)).append(':').append(stats.valueAt(i)).append(',')
        sp.edit().putString("uid", uid).putString("name", name).putInt("color", color).putInt("gems", gems)
            .putInt("xp", xp).putInt("quest", quest).putLong("daily", lastDaily)
            .putBoolean("sound", sound).putBoolean("music", music)
            .putInt("cscale", ctrlScale).putInt("cop", ctrlOp).putBoolean("lefth", leftHand)
            .putInt("hscale", hudScale).putBoolean("zbtn", zoomBtns).putInt("zoom", zoomPct)
            .putString("lastw", lastWorldName).putBoolean("low", lowGfx).putInt("sel", sel)
            .putString("inv", sb.toString()).putString("equip", equip.joinToString(","))
            .putString("quick", quick.joinToString(",")).putString("notes", notes.joinToString("\n"))
            .putInt("pet", pet).putInt("sky", skyMode).putInt("weather", weatherMode)
            .putLong("qday", questDay).putString("stats", st.toString())
            .putString("badgestr", badges.joinToString(","))
            .putString("qstats", dayStats.joinToString(","))
            .putString("qclaim", questClaimed.joinToString(",") { if (it) "1" else "0" })
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
        if (pet == id) pet = 0
    }
    /** 100 World Locks -> Diamond Lock, 100 Diamond Locks -> Blue Gem Lock. Returns a message if something was compressed. */
    @Synchronized fun autoCompress(): String? {
        var msg: String? = null
        fun roll(from: Int, to: Int, label: String) {
            val n = inv.get(from, 0); if (n < 100) return
            val rem = n % 100
            if (rem == 0) { inv.delete(from); onDepleted(from) } else inv.put(from, rem)
            inv.put(to, inv.get(to, 0) + n / 100)
            msg = label
        }
        roll(Id.WORLD_LOCK, Id.DIAMOND_LOCK, "100 World Locks compressed into a Diamond Lock!")
        roll(Id.DIAMOND_LOCK, Id.BLUE_LOCK, "100 Diamond Locks compressed into a Blue Gem Lock!")
        return msg
    }

    @Synchronized fun addGems(n: Int) {
        gems += n
        if (n > 0) stats.put(Stat.GEMS, stats.get(Stat.GEMS, 0) + n)
        checkUnlocks()
    }
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
        // Hotbar slots stay where they are (like Growtopia): new items take the first empty slot.
        if (quick.contains(id)) return
        val empty = quick.indexOf(0)
        if (empty >= 0) quick[empty] = id else quick[quick.size - 1] = id
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
    val hasRod: Boolean get() = worn(Slot.HAND) == Id.FISHING_ROD

    @Synchronized fun togglePet(id: Int) {
        pet = if (pet == id) 0 else id
        if (pet != 0) bump(Stat.PET)
    }

    // ------------------------------------------------------------ badges
    @Synchronized fun bump(stat: Int, n: Int = 1) {
        if (stat < 0 || stat >= Stat.SIZE) return
        stats.put(stat, stats.get(stat, 0) + n)
        checkDay()
        dayStats[stat] += n
        checkUnlocks()
    }

    private fun checkUnlocks() {
        for (a in Achievements.all) {
            if (a.id !in badges && stats.get(a.stat, 0) >= a.goal) {
                badges.add(a.id)
                gems += a.reward
                pendingBadges.add(a)
                addNote("Badge unlocked: ${a.name}  (+${a.reward} gems)")
            }
        }
    }

    @Synchronized fun statValue(stat: Int) = stats.get(stat, 0)
    @Synchronized fun hasBadge(id: Int) = id in badges

    @Synchronized fun drainBadges(): List<Achievement> {
        if (pendingBadges.isEmpty()) return emptyList()
        val out = ArrayList(pendingBadges)
        pendingBadges.clear()
        return out
    }

    // ------------------------------------------------------------ daily quests
    private fun today() = System.currentTimeMillis() / 86_400_000L
    private fun checkDay() {
        val d = today()
        if (d != questDay) {
            questDay = d
            for (i in dayStats.indices) dayStats[i] = 0
            for (i in questClaimed.indices) questClaimed[i] = false
        }
    }

    @Synchronized fun questProgress(i: Int): Int {
        checkDay()
        val q = Daily.today().getOrNull(i) ?: return 0
        return minOf(dayStats[q.stat], q.goal)
    }

    @Synchronized fun isQuestClaimed(i: Int): Boolean {
        checkDay()
        return questClaimed.getOrElse(i) { false }
    }

    /** Returns the reward, or 0 if the quest isn't ready/already claimed. */
    @Synchronized fun claimQuest(i: Int): Int {
        checkDay()
        val q = Daily.today().getOrNull(i) ?: return 0
        if (questClaimed.getOrElse(i) { true } || dayStats[q.stat] < q.goal) return 0
        questClaimed[i] = true
        gems += q.reward
        addNote("Quest complete: ${q.title}  (+${q.reward} gems)")
        return q.reward
    }

    @Synchronized fun level(): Int { var l = 1; var x = xp; while (x >= xpFor(l)) { x -= xpFor(l); l++ }; return l }
    @Synchronized fun levelProgress(): Float { var l = 1; var x = xp; while (x >= xpFor(l)) { x -= xpFor(l); l++ }; return x.toFloat() / xpFor(l) }
    /** Returns the new level if the player levelled up, else 0. */
    @Synchronized fun addXp(n: Int): Int {
        val before = level(); xp += n; val after = level()
        if (after > before) bump(Stat.LEVEL, after - before)
        return if (after > before) after else 0
    }

    @Synchronized fun addNote(s: String) { notes.add(0, s); while (notes.size > 30) notes.removeAt(notes.size - 1) }

    fun dailyReady() = System.currentTimeMillis() - lastDaily >= 20L * 3600_000L
    @Synchronized fun claimDaily(): Int {
        if (!dailyReady()) return 0
        lastDaily = System.currentTimeMillis()
        val g = 50 + level() * 5; gems += g; addNote("Daily reward: +$g gems"); return g
    }
}

