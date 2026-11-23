package com.blockpatch.game

import android.app.Activity
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.*

class GameActivity : Activity(), GameHost {
    private lateinit var prof: Profile
    private lateinit var sfx: Sfx
    private lateinit var game: GameView
    private lateinit var root: FrameLayout
    private lateinit var sheet: FrameLayout
    private lateinit var scroll: ScrollView
    private lateinit var card: LinearLayout
    private lateinit var chatBar: LinearLayout
    private lateinit var chatEdit: EditText
    private lateinit var topBtns: LinearLayout
    private var panel = ""
    private var shopTab = 0
    private var bagSel = 0

    private fun dp(v: Float) = Ui.dp(this, v)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Atlas.init(this)
        prof = Profile(this)
        sfx = Sfx(this, prof)
        Notify.ensureChannel(this)
        game = GameView(this, prof, sfx, this)
        root = FrameLayout(this)
        root.addView(game, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))

        topBtns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun top(label: String, f: () -> Unit) {
            val b = Ui.button(this, label, 0x99000000.toInt(), 18f) { f() }
            b.minWidth = dp(44f); b.setPadding(dp(8f), dp(4f), dp(8f), dp(4f))
            topBtns.addView(b, LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP).also { it.leftMargin = dp(6f) })
        }
        top("Bag") { showBag() }
        top("Shop") { showShop() }
        top("Chat") { toggleChat() }
        top("Badges") { showBadges() }
        top("Menu") { showMenu() }
        root.addView(topBtns, FrameLayout.LayoutParams(Ui.WRAP, Ui.WRAP, Gravity.TOP or Gravity.END).also { it.setMargins(0, dp(8f), dp(8f), 0) })

        chatEdit = Ui.edit(this, "Say something... (/help for commands)", "", 80).apply { imeOptions = EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_EXTRACT_UI }
        chatEdit.setOnEditorActionListener { _, _, _ -> sendChat(); true }
        chatBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; visibility = View.GONE; setPadding(dp(8f), dp(8f), dp(8f), dp(8f))
            setBackgroundColor(0xCC000000.toInt())
        }
        chatBar.addView(chatEdit, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        chatBar.addView(Ui.button(this, "Send") { sendChat() }, LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP).also { it.leftMargin = dp(8f) })
        root.addView(chatBar, FrameLayout.LayoutParams(Ui.MATCH, Ui.WRAP, Gravity.TOP))

        sheet = FrameLayout(this).apply { setBackgroundColor(0xAA000000.toInt()); visibility = View.GONE; isClickable = true; setOnClickListener { closeSheet() } }
        scroll = ScrollView(this).apply { background = Ui.rounded(Ui.BG, 16f, this@GameActivity); isClickable = true }
        card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16f), dp(14f), dp(16f), dp(16f)) }
        scroll.addView(card)
        sheet.addView(scroll, FrameLayout.LayoutParams(dp(480f), Ui.WRAP, Gravity.CENTER))
        root.addView(sheet, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
        setContentView(root)

        root.setOnApplyWindowInsetsListener { _, ins ->
            var l = 0; var t = 0; var r = 0; var b = 0
            if (Build.VERSION.SDK_INT >= 28) ins.displayCutout?.let { l = it.safeInsetLeft; t = it.safeInsetTop; r = it.safeInsetRight; b = it.safeInsetBottom }
            game.setInsets(l, t, r, b)
            topBtns.setPadding(0, t, r, 0)
            ins
        }

        val name = intent.getStringExtra("world") ?: prof.lastWorldName
        game.enterInitial(name, intent.getIntExtra("type", World.NORMAL))
    }

    // ------------------------------------------------------------ lifecycle
    override fun onResume() {
        super.onResume()
        Notify.cancel(this)
        sfx.startMusic(); game.onResume()
    }

    override fun onPause() {
        game.onPause(); sfx.pauseMusic(); prof.save()
        game.nextRipe()?.let { Notify.schedule(this, it.first, it.second) }
        super.onPause()
    }

    override fun onDestroy() { sfx.release(); super.onDestroy() }

    @Suppress("DEPRECATION")
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        when {
            sheet.visibility == View.VISIBLE -> closeSheet()
            chatBar.visibility == View.VISIBLE -> toggleChat()
            else -> showMenu()
        }
    }

    // ------------------------------------------------------------ GameHost
    override fun promptText(title: String, hint: String, def: String, max: Int, ok: (String) -> Unit) = runOnUiThread {
        openSheet("prompt") { c ->
            c.addView(Ui.text(this, title, 18f, Ui.TEXT, true))
            val e = Ui.edit(this, hint, def, max)
            c.addView(e, Ui.lp(Ui.MATCH, Ui.WRAP, 0f, 10, this))
            val row = LinearLayout(this)
            row.addView(Ui.button(this, "Cancel", Ui.CARD2) { closeSheet() }, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
            row.addView(Ui.button(this, "OK") { val v = e.text.toString(); closeSheet(); ok(v) }, LinearLayout.LayoutParams(0, Ui.WRAP, 1f).also { it.leftMargin = dp(8f) })
            c.addView(row, Ui.lp(Ui.MATCH, Ui.WRAP, 0f, 10, this))
            e.setOnEditorActionListener { _, _, _ -> val v = e.text.toString(); closeSheet(); ok(v); true }
            e.requestFocus(); e.post { (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(e, 0) }
        }
    }

    override fun confirm(title: String, msg: String, ok: () -> Unit) = runOnUiThread {
        openSheet("confirm") { c ->
            c.addView(Ui.text(this, title, 18f, Ui.TEXT, true))
            c.addView(Ui.text(this, msg, 14f, Ui.MUTED), Ui.lp(Ui.MATCH, Ui.WRAP, 0f, 8, this))
            val row = LinearLayout(this)
            row.addView(Ui.button(this, "Cancel", Ui.CARD2) { closeSheet() }, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
            row.addView(Ui.button(this, "Yes", Ui.DANGER) { closeSheet(); ok() }, LinearLayout.LayoutParams(0, Ui.WRAP, 1f).also { it.leftMargin = dp(8f) })
            c.addView(row, Ui.lp(Ui.MATCH, Ui.WRAP, 0f, 14, this))
        }
    }

    override fun exitWorld() = runOnUiThread { game.saveNow(); finish() }

    override fun stateChanged() = runOnUiThread {
        if (panel == "bag" || panel == "shop") {
            val y = scroll.scrollY
            if (panel == "bag") showBag() else showShop()
            scroll.post { scroll.scrollTo(0, y) }
        }
    }

    override fun openPanel(name: String) = runOnUiThread {
        when (name) {
            "badges" -> showBadges()
            "quests" -> showQuests()
            "stats" -> showStats()
            "weather" -> showAtmosphere()
            "pets" -> { shopTab = Shop.tabs.indexOfFirst { it.first == "Pets" }.coerceAtLeast(0); showShop() }
        }
    }

    // ------------------------------------------------------------ sheet plumbing
    private fun openSheet(name: String, build: (LinearLayout) -> Unit) {
        panel = name; card.removeAllViews(); build(card); sheet.visibility = View.VISIBLE
    }

    private fun closeSheet() {
        panel = ""; sheet.visibility = View.GONE
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(root.windowToken, 0)
    }

    private fun header(c: LinearLayout, title: String) {
        val row = LinearLayout(this); row.gravity = Gravity.CENTER_VERTICAL
        row.addView(Ui.text(this, title, 20f, Ui.TEXT, true), LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        row.addView(Ui.text(this, "${prof.gems} gems", 14f, 0xFF8FC9FF.toInt(), true))
        row.addView(Ui.button(this, "X", Ui.CARD2) { closeSheet() }, LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP).also { it.leftMargin = dp(10f) })
        c.addView(row)
    }

    private fun toggleChat() {
        val show = chatBar.visibility != View.VISIBLE
        chatBar.visibility = if (show) View.VISIBLE else View.GONE
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        if (show) { chatEdit.requestFocus(); chatEdit.post { imm.showSoftInput(chatEdit, 0) } }
        else imm.hideSoftInputFromWindow(chatEdit.windowToken, 0)
    }

    private fun sendChat() {
        val t = chatEdit.text.toString(); chatEdit.setText("")
        if (t.isNotBlank()) game.say(t)
        toggleChat()
    }

    // ------------------------------------------------------------ backpack
    private fun showBag(): Unit = openSheet("bag") { c ->
        header(c, "Backpack")
        val items = prof.allItems().filter { it.first != Id.FIST }
        val all = listOf(Id.FIST to 1) + items
        val per = 6
        val cell = dp(64f)
        var row: LinearLayout? = null
        all.forEachIndexed { i, (id, n) ->
            if (i % per == 0) { row = LinearLayout(this); c.addView(row, Ui.lp(Ui.WRAP, Ui.WRAP, 0f, 6, this)) }
            val v = ItemCell(this, id, if (id == Id.FIST) 0 else n, prof.sel == id, prof.isEquipped(id))
            v.setOnClickListener { bagSel = id; showBag() }
            row!!.addView(v, LinearLayout.LayoutParams(cell, cell))
        }
        val id = if (all.any { it.first == bagSel }) bagSel else Id.FIST
        val it = ItemDB[id]; val n = prof.count(id)
        c.addView(Ui.text(this, it.name + if (id != Id.FIST) "  x$n" else "", 17f, Ui.GOLD, true), Ui.lp(Ui.WRAP, Ui.WRAP, 0f, 14, this))
        c.addView(Ui.text(this, it.desc, 13f, Ui.MUTED))
        val acts = LinearLayout(this); acts.orientation = LinearLayout.HORIZONTAL
        fun act(label: String, bg: Int = Ui.ACCENT, f: () -> Unit) =
            acts.addView(Ui.button(this, label, bg, 13f) { f() }, LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP).also { p -> p.rightMargin = dp(6f); p.topMargin = dp(4f) })
        if (it.slot != null) act(if (prof.isEquipped(id)) "Unequip" else "Equip") {
            val on = prof.toggleEquip(id); sfx.play("equip"); game.message(if (on) "Equipped ${it.name}" else "Removed ${it.name}", GameView.C_GREEN); showBag()
        } else if (ItemDB.isPet(id)) act(if (prof.pet == id) "Dismiss" else "Summon") {
            prof.togglePet(id); sfx.play("equip")
            game.message(if (prof.pet == id) "${it.name} follows you!" else "${it.name} dismissed.", GameView.C_GREEN)
            showBag()
        } else if (ItemDB.isFish(id)) act("Sell for ${Prices.sell(id)} gems", Ui.GOLD) {
            if (prof.take(id, 1)) {
                prof.addGems(Prices.sell(id)); prof.bump(Stat.SELL); sfx.play("buy")
                game.message("Sold ${it.name} for ${Prices.sell(id)} gems.", GameView.C_GREEN)
            }
            showBag()
        } else if (it.placeable || id == Id.FIST) act(if (id == Id.FIST) "Use Fist" else "Select") { game.selectItem(id); closeSheet() }
        if (id == Id.WORLD_LOCK && n >= 100) act("Compress 100 -> Diamond Lock") { prof.take(id, 100); prof.add(Id.DIAMOND_LOCK, 1); sfx.play("lock"); showBag() }
        if (id == Id.DIAMOND_LOCK) {
            act("Shatter -> 100 World Locks", Ui.CARD2) { prof.take(id, 1); prof.add(Id.WORLD_LOCK, 100); sfx.play("unlock"); showBag() }
            if (n >= 100) act("Compress 100 -> Blue Gem Lock") { prof.take(id, 100); prof.add(Id.BLUE_LOCK, 1); sfx.play("lock"); showBag() }
        }
        if (id == Id.BLUE_LOCK) act("Shatter -> 100 Diamond Locks", Ui.CARD2) { prof.take(id, 1); prof.add(Id.DIAMOND_LOCK, 100); sfx.play("unlock"); showBag() }
        if (id != Id.FIST && !ItemDB.isCurrency(id)) act("Trash 1", Ui.DANGER) { prof.take(id, 1); sfx.play("deny", 0.5f); showBag() }
        c.addView(acts)
        val worn = (0 until 5).map { prof.equip[it] }.filter { it != 0 }.joinToString(", ") { ItemDB[it].name }
        c.addView(Ui.text(this, "Wearing: " + if (worn.isEmpty()) "nothing" else worn, 12f, Ui.MUTED), Ui.lp(Ui.WRAP, Ui.WRAP, 0f, 10, this))
    }

    // ------------------------------------------------------------ store
    private fun showShop(): Unit = openSheet("shop") { c ->
        header(c, "Store")
        val tabs = Shop.tabs.map { it.first } + listOf("Craft", "Quests", "Sell")
        if (shopTab !in tabs.indices) shopTab = 0
        val hs = HorizontalScrollView(this); val tr = LinearLayout(this); hs.addView(tr)
        tabs.forEachIndexed { i, n ->
            tr.addView(Ui.button(this, n, if (i == shopTab) Ui.ACCENT else Ui.CARD2, 13f) { shopTab = i; showShop() },
                LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP).also { it.rightMargin = dp(6f) })
        }
        c.addView(hs, Ui.lp(Ui.MATCH, Ui.WRAP, 0f, 8, this))
        when {
            shopTab < Shop.tabs.size -> for (o in Shop.tabs[shopTab].second) {
                val it = ItemDB[o.item]
                val owned = (it.slot != null || it.pet) && prof.count(o.item) > 0
                val row = LinearLayout(this); row.gravity = Gravity.CENTER_VERTICAL
                row.addView(IconView(this, o.item), LinearLayout.LayoutParams(dp(36f), dp(36f)))
                val col = LinearLayout(this); col.orientation = LinearLayout.VERTICAL
                col.addView(Ui.text(this, it.name + if (o.qty > 1) " x${o.qty}" else "", 15f, Ui.TEXT, true))
                col.addView(Ui.text(this, it.desc, 11f, Ui.MUTED))
                row.addView(col, LinearLayout.LayoutParams(0, Ui.WRAP, 1f).also { p -> p.leftMargin = dp(10f) })
                row.addView(Ui.button(this, if (owned) "Owned" else "${o.price} gems", if (owned) Ui.CARD2 else Ui.ACCENT, 13f) {
                    if (owned) return@button
                    if (prof.spend(o.price)) {
                        prof.add(o.item, o.qty); sfx.play("buy"); game.notifyUser("Purchased", "${it.name} x${o.qty}", GameView.C_GREEN, o.item)
                        prof.autoCompress()?.let { m -> sfx.play("lock"); game.notifyUser("Locks compressed", m, GameView.C_BLUE, Id.DIAMOND_LOCK) }
                    }
                    else { sfx.play("deny"); game.message("Not enough gems.", GameView.C_RED) }
                    showShop()
                })
                c.addView(row, Ui.lp(Ui.MATCH, Ui.WRAP, 0f, 8, this))
            }
            shopTab == Shop.tabs.size -> for (r in Shop.recipes) {
                val row = LinearLayout(this); row.gravity = Gravity.CENTER_VERTICAL
                row.addView(Ui.text(this, r.label, 14f), LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
                row.addView(Ui.button(this, "Craft", Ui.ACCENT, 13f) {
                    if (r.need.all { (id, n) -> prof.count(id) >= n }) {
                        r.need.forEach { (id, n) -> prof.take(id, n) }; r.give.forEach { (id, n) -> prof.add(id, n) }
                        prof.bump(Stat.CRAFT)
                        sfx.play("buy"); game.message("Crafted!", GameView.C_GREEN)
                    } else { sfx.play("deny"); game.message("Missing ingredients.", GameView.C_RED) }
                    showShop()
                })
                c.addView(row, Ui.lp(Ui.MATCH, Ui.WRAP, 0f, 8, this))
            }
            shopTab == Shop.tabs.size + 1 -> questRows(c)
            else -> sellRows(c)
        }
    }

    private fun questRows(c: LinearLayout) {
        c.addView(Ui.text(this, "Daily Quests", 18f, Ui.TEXT, true), Ui.lp(Ui.WRAP, Ui.WRAP, 0f, 6, this))
        c.addView(Ui.text(this, "Three quests per day. Progress counts from today.", 12f, Ui.MUTED))
        val qs = Daily.today()
        qs.forEachIndexed { i, q ->
            val prog = prof.questProgress(i)
            val claimed = prof.isQuestClaimed(i)
            val row = LinearLayout(this); row.gravity = Gravity.CENTER_VERTICAL
            val col = LinearLayout(this); col.orientation = LinearLayout.VERTICAL
            col.addView(Ui.text(this, q.title, 14f, Ui.TEXT, true))
            col.addView(Ui.text(this, "$prog / ${q.goal}  -  +${q.reward} gems", 12f, if (prog >= q.goal) Ui.GOLD else Ui.MUTED))
            row.addView(col, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
            row.addView(Ui.button(this, if (claimed) "Done" else "Claim",
                if (claimed) Ui.CARD2 else if (prog >= q.goal) Ui.ACCENT else Ui.CARD2, 13f) {
                if (!claimed && prog >= q.goal) {
                    val r = prof.claimQuest(i)
                    if (r > 0) { sfx.play("levelup"); game.notifyUser("Quest complete!", "+$r gems", GameView.C_GREEN, Id.T_GEM) }
                } else sfx.play("deny")
                if (panel == "shop") showShop() else showQuests()
            })
            c.addView(row, Ui.lp(Ui.MATCH, Ui.WRAP, 0f, 10, this))
        }
    }

    private fun sellRows(c: LinearLayout) {
        c.addView(Ui.text(this, "Sell Items", 18f, Ui.TEXT, true), Ui.lp(Ui.WRAP, Ui.WRAP, 0f, 6, this))
        c.addView(Ui.text(this, "The store pays gems for anything in your backpack (locks excluded).", 12f, Ui.MUTED))
        val items = prof.allItems().filter { it.first != Id.FIST && !ItemDB.isCurrency(it.first) && Prices.sell(it.first) > 0 }
        if (items.isEmpty()) {
            c.addView(Ui.text(this, "Nothing to sell yet. Catch fish, harvest crops, or mine gems!", 13f, Ui.MUTED), Ui.lp(Ui.WRAP, Ui.WRAP, 0f, 10, this))
            return
        }
        for ((id, n) in items.take(80)) {
            val it = ItemDB[id]; val price = Prices.sell(id)
            val row = LinearLayout(this); row.gravity = Gravity.CENTER_VERTICAL
            row.addView(IconView(this, id), LinearLayout.LayoutParams(dp(36f), dp(36f)))
            val col = LinearLayout(this); col.orientation = LinearLayout.VERTICAL
            col.addView(Ui.text(this, it.name + "  x$n", 14f, Ui.TEXT, true))
            col.addView(Ui.text(this, it.desc, 11f, Ui.MUTED))
            row.addView(col, LinearLayout.LayoutParams(0, Ui.WRAP, 1f).also { p -> p.leftMargin = dp(10f) })
            row.addView(Ui.button(this, "$price gems", Ui.GOLD, 13f) {
                if (prof.take(id, 1)) {
                    prof.addGems(price); prof.bump(Stat.SELL); sfx.play("buy")
                    game.message("Sold ${it.name} for $price gems.", GameView.C_GREEN)
                }
                showShop()
            })
            c.addView(row, Ui.lp(Ui.MATCH, Ui.WRAP, 0f, 8, this))
        }
    }

    private fun showQuests(): Unit = openSheet("quests") { c ->
        header(c, "Quests")
        questRows(c)
    }

    // ------------------------------------------------------------ badges / stats / atmosphere
    private fun showBadges(): Unit = openSheet("badges") { c ->
        header(c, "Badges")
        val unlocked = Achievements.all.count { prof.hasBadge(it.id) }
        c.addView(Ui.text(this, "$unlocked / ${Achievements.all.size} unlocked", 15f, Ui.GOLD, true), Ui.lp(Ui.WRAP, Ui.WRAP, 0f, 6, this))
        c.addView(Ui.text(this, "Badges are permanent. Rewards are paid automatically when you earn one.", 11f, Ui.MUTED))
        for (a in Achievements.all) {
            val done = prof.hasBadge(a.id)
            val v = prof.statValue(a.stat)
            val row = LinearLayout(this); row.gravity = Gravity.CENTER_VERTICAL
            row.addView(IconView(this, a.icon), LinearLayout.LayoutParams(dp(38f), dp(38f)))
            val col = LinearLayout(this); col.orientation = LinearLayout.VERTICAL
            col.addView(Ui.text(this, a.name + if (done) "  DONE" else "", 15f, if (done) Ui.GOLD else Ui.TEXT, true))
            col.addView(Ui.text(this, a.desc, 11f, Ui.MUTED))
            col.addView(Ui.text(this, "${minOf(v, a.goal)} / ${a.goal}  -  +${a.reward} gems", 11f, if (done) Ui.GOLD else Ui.MUTED))
            row.addView(col, LinearLayout.LayoutParams(0, Ui.WRAP, 1f).also { p -> p.leftMargin = dp(10f) })
            c.addView(row, Ui.lp(Ui.MATCH, Ui.WRAP, 0f, 9, this))
        }
    }

    private fun showStats(): Unit = openSheet("stats") { c ->
        header(c, "Statistics")
        c.addView(Ui.text(this, "Level ${prof.level()}  -  ${prof.gems} gems", 15f, Ui.GOLD, true), Ui.lp(Ui.WRAP, Ui.WRAP, 0f, 6, this))
        val rows = listOf(
            "Blocks placed" to Stat.PLACE,
            "Blocks broken" to Stat.BREAK,
            "Trees harvested" to Stat.HARVEST,
            "Fish caught" to Stat.FISH,
            "Items sold" to Stat.SELL,
            "Locks placed" to Stat.LOCK,
            "Items crafted" to Stat.CRAFT,
            "Level-ups" to Stat.LEVEL,
            "Gems earned" to Stat.GEMS,
            "World visits" to Stat.WORLDS
        )
        for ((label, stat) in rows) {
            val r = LinearLayout(this); r.gravity = Gravity.CENTER_VERTICAL
            r.addView(Ui.text(this, label, 14f, Ui.TEXT), LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
            r.addView(Ui.text(this, prof.statValue(stat).toString(), 14f, Ui.GOLD, true))
            c.addView(r, Ui.lp(Ui.MATCH, Ui.WRAP, 0f, 7, this))
        }
        val badges = Achievements.all.count { prof.hasBadge(it.id) }
        c.addView(Ui.text(this, "Badges: $badges / ${Achievements.all.size}", 13f, Ui.MUTED), Ui.lp(Ui.WRAP, Ui.WRAP, 0f, 10, this))
        c.addView(Ui.button(this, "View badges") { showBadges() }, Ui.lp(Ui.MATCH, Ui.WRAP, 0f, 8, this))
        c.addView(Ui.button(this, "Daily quests") { showQuests() }, Ui.lp(Ui.MATCH, Ui.WRAP, 0f, 8, this))
    }

    private fun showAtmosphere(): Unit = openSheet("weather") { c ->
        header(c, "Sky & Weather")
        val skyNames = arrayOf("Auto (cycle)", "Always day", "Sunset", "Night")
        val weatherNames = arrayOf("Auto (per world)", "Clear", "Rain", "Snow")
        fun btn(label: String, bg: Int = Ui.CARD2, f: () -> Unit) =
            c.addView(Ui.button(this, label, bg) { f() }, Ui.lp(Ui.MATCH, Ui.WRAP, 0f, 8, this))
        btn("Sky: ${skyNames[prof.skyMode]}") { prof.skyMode = (prof.skyMode + 1) % 4; prof.save(); showAtmosphere() }
        btn("Weather: ${weatherNames[prof.weatherMode]}") { prof.weatherMode = (prof.weatherMode + 1) % 4; prof.save(); showAtmosphere() }
        c.addView(Ui.text(this, "Tip: use /time and /weather in chat to change the mood instantly.", 11f, Ui.MUTED), Ui.lp(Ui.WRAP, Ui.WRAP, 0f, 10, this))
        btn("Back", Ui.ACCENT) { showMenu() }
    }

    // ------------------------------------------------------------ display & controls
    private fun showDisplay(): Unit = openSheet("display") { c ->
        c.addView(Ui.text(this, "Display & Controls", 20f, Ui.TEXT, true))
        c.addView(Ui.text(this, "Changes apply instantly.", 12f, Ui.MUTED))
        fun btn(label: String, bg: Int = Ui.CARD2, f: () -> Unit) =
            c.addView(Ui.button(this, label, bg) { f() }, Ui.lp(Ui.MATCH, Ui.WRAP, 0f, 8, this))
        fun commit() { prof.save(); game.applyUiSettings(); showDisplay() }
        val sizes = intArrayOf(80, 100, 125, 150)
        val ops = arrayOf("Faint", "Normal", "Strong", "Solid")
        val huds = intArrayOf(85, 100, 120, 140)
        btn("Button size: ${prof.ctrlScale}%") { prof.ctrlScale = sizes[(sizes.indexOf(prof.ctrlScale) + 1) % sizes.size]; commit() }
        btn("Button opacity: ${ops[prof.ctrlOp]}") { prof.ctrlOp = (prof.ctrlOp + 1) % ops.size; commit() }
        btn("UI & text size: ${prof.hudScale}%") { prof.hudScale = huds[(huds.indexOf(prof.hudScale) + 1) % huds.size]; commit() }
        btn("Left-handed layout: " + if (prof.leftHand) "ON" else "OFF") { prof.leftHand = !prof.leftHand; commit() }
        btn("Zoom buttons (+ / -): " + if (prof.zoomBtns) "ON" else "OFF") { prof.zoomBtns = !prof.zoomBtns; commit() }
        btn("Reset to defaults") {
            prof.ctrlScale = 100; prof.ctrlOp = 1; prof.hudScale = 100; prof.leftHand = false; prof.zoomBtns = true; commit()
        }
        btn("Back", Ui.ACCENT) { showMenu() }
    }

    // ------------------------------------------------------------ menu
    private fun showMenu(): Unit = openSheet("menu") { c ->
        c.addView(Ui.text(this, (game.worldName() ?: "").uppercase(), 20f, Ui.TEXT, true))
        c.addView(Ui.text(this, game.worldOwnerLine() + "  -  Level ${prof.level()}", 13f, Ui.MUTED))
        fun btn(label: String, bg: Int = Ui.CARD2, f: () -> Unit) =
            c.addView(Ui.button(this, label, bg) { f() }, Ui.lp(Ui.MATCH, Ui.WRAP, 0f, 8, this))
        btn("Resume", Ui.ACCENT) { closeSheet() }
        btn("Backpack") { showBag() }
        btn("Store") { showShop() }
        btn("Badges") { showBadges() }
        btn("Daily Quests") { showQuests() }
        btn("Sky & Weather") { showAtmosphere() }
        btn("Statistics") { showStats() }
        btn("Sound effects: " + if (prof.sound) "ON" else "OFF") { prof.sound = !prof.sound; prof.save(); showMenu() }
        btn("Music: " + if (prof.music) "ON" else "OFF") { prof.music = !prof.music; prof.save(); sfx.applyMusicSetting(); showMenu() }
        btn("Display & Controls...") { showDisplay() }
        btn("Low graphics (faster): " + if (prof.lowGfx) "ON" else "OFF") { prof.lowGfx = !prof.lowGfx; prof.save(); showMenu() }
        btn("Exit World", Ui.DANGER) { closeSheet(); exitWorld() }
    }
}

