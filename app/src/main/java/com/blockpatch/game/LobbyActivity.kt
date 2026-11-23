package com.blockpatch.game

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.text.format.DateUtils
import android.view.Gravity
import android.view.View
import android.widget.*

/** Main menu: nickname, colour, enter / create / delete worlds, notifications, daily reward. */
class LobbyActivity : Activity() {
    private lateinit var prof: Profile
    private lateinit var sfx: Sfx
    private lateinit var nameEdit: EditText
    private lateinit var worldEdit: EditText
    private lateinit var status: TextView
    private lateinit var stats: TextView
    private lateinit var notesBox: LinearLayout
    private lateinit var listBox: LinearLayout
    private lateinit var dailyBtn: TextView
    private var newType = World.NORMAL
    private val typeBtns = ArrayList<TextView>()

    private fun dp(v: Float) = Ui.dp(this, v)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Atlas.init(this)
        prof = Profile(this); sfx = Sfx(this, prof)
        Notify.ensureChannel(this)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 1)
        build()
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; background = Ui.rounded(Ui.CARD, 16f, this@LobbyActivity)
        setPadding(dp(16f), dp(14f), dp(16f), dp(16f))
    }

    private fun build() {
        val root = FrameLayout(this); root.setBackgroundColor(Ui.BG)
        val sv = ScrollView(this); sv.isFillViewport = true
        val cols = LinearLayout(this); cols.orientation = LinearLayout.HORIZONTAL; cols.setPadding(dp(16f), dp(12f), dp(16f), dp(12f))
        sv.addView(cols, FrameLayout.LayoutParams(Ui.MATCH, Ui.WRAP)); root.addView(sv)

        // ---- left: profile
        val left = card()
        try {
            val img = ImageView(this); img.adjustViewBounds = true
            img.setImageBitmap(assets.open("img/logo.png").use { BitmapFactory.decodeStream(it) })
            left.addView(img, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP))
        } catch (_: Exception) { left.addView(Ui.text(this, "Blockpatch", 28f, Ui.TEXT, true)) }
        left.addView(Ui.text(this, "Nickname", 12f, Ui.MUTED), Ui.lp(Ui.WRAP, Ui.WRAP, 0f, 8, this))
        nameEdit = Ui.edit(this, "Your nickname", prof.name, 10); left.addView(nameEdit)
        val sw = LinearLayout(this)
        Profile.COLORS.forEachIndexed { i, col ->
            val b = View(this)
            fun paint() { b.background = Ui.rounded(col, 20f, this, if (prof.color == i) Ui.TEXT else 0) }
            paint()
            b.setOnClickListener { prof.color = i; sfx.play("equip", 0.5f); for (k in 0 until sw.childCount) sw.getChildAt(k).background = Ui.rounded(Profile.COLORS[k], 20f, this, if (k == i) Ui.TEXT else 0) }
            sw.addView(b, LinearLayout.LayoutParams(dp(34f), dp(34f)).also { it.rightMargin = dp(8f) })
        }
        left.addView(sw, Ui.lp(Ui.WRAP, Ui.WRAP, 0f, 10, this))
        stats = Ui.text(this, "", 13f, Ui.MUTED); left.addView(stats, Ui.lp(Ui.WRAP, Ui.WRAP, 0f, 10, this))
        dailyBtn = Ui.button(this, "🎁 Claim daily reward", Ui.GOLD) {
            val g = prof.claimDaily(); if (g > 0) { sfx.play("levelup"); say("Daily reward: +$g gems!", true) }; refresh()
        }
        dailyBtn.setTextColor(0xFF222222.toInt())
        left.addView(dailyBtn, Ui.lp(Ui.MATCH, Ui.WRAP, 0f, 8, this))
        left.addView(Ui.text(this, "🔔 Notifications", 14f, Ui.TEXT, true), Ui.lp(Ui.WRAP, Ui.WRAP, 0f, 14, this))
        notesBox = LinearLayout(this); notesBox.orientation = LinearLayout.VERTICAL; left.addView(notesBox)
        cols.addView(left, LinearLayout.LayoutParams(0, Ui.WRAP, 0.85f))

        // ---- right: worlds
        val right = card()
        right.addView(Ui.text(this, "Enter a world", 20f, Ui.TEXT, true))
        right.addView(Ui.text(this, "Type any name. If it doesn't exist yet, it gets created.", 12f, Ui.MUTED))
        worldEdit = Ui.edit(this, "WORLD NAME", prof.lastWorldName.uppercase(), 20)
        worldEdit.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        right.addView(worldEdit, Ui.lp(Ui.MATCH, Ui.WRAP, 0f, 8, this))
        val r1 = LinearLayout(this)
        r1.addView(Ui.button(this, "Enter World") { go(worldEdit.text.toString(), World.NORMAL, false) }, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        r1.addView(Ui.button(this, "🎲 Random", Ui.CARD2) { worldEdit.setText(World.randomName().uppercase()) }, LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP).also { it.leftMargin = dp(8f) })
        right.addView(r1, Ui.lp(Ui.MATCH, Ui.WRAP, 0f, 8, this))

        right.addView(Ui.text(this, "Create a new world", 15f, Ui.TEXT, true), Ui.lp(Ui.WRAP, Ui.WRAP, 0f, 14, this))
        val tr = LinearLayout(this)
        World.TYPE_NAMES.forEachIndexed { i, n ->
            val b = Ui.button(this, n, Ui.CARD2, 13f) { newType = i; styleTypes() }
            typeBtns.add(b); tr.addView(b, LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP).also { it.rightMargin = dp(6f) })
        }
        styleTypes()
        right.addView(tr, Ui.lp(Ui.WRAP, Ui.WRAP, 0f, 6, this))
        right.addView(Ui.text(this, "Normal: flat farmland with caves and lava.  Hills: rolling terrain.  Blank: empty sky to build from scratch.", 11f, Ui.MUTED))
        right.addView(Ui.button(this, "Create World", Ui.ACCENT) { go(worldEdit.text.toString(), newType, true) }, Ui.lp(Ui.MATCH, Ui.WRAP, 0f, 8, this))
        status = Ui.text(this, "", 13f, Ui.GOLD); right.addView(status, Ui.lp(Ui.WRAP, Ui.WRAP, 0f, 6, this))

        right.addView(Ui.text(this, "Your worlds", 15f, Ui.TEXT, true), Ui.lp(Ui.WRAP, Ui.WRAP, 0f, 10, this))
        listBox = LinearLayout(this); listBox.orientation = LinearLayout.VERTICAL; right.addView(listBox)
        cols.addView(right, LinearLayout.LayoutParams(0, Ui.WRAP, 1.15f).also { it.leftMargin = dp(12f) })
        setContentView(root)
    }

    private fun styleTypes() = typeBtns.forEachIndexed { i, b -> b.background = Ui.rounded(if (i == newType) Ui.ACCENT else Ui.CARD2, 10f, this) }

    private fun say(s: String, good: Boolean = false) { status.setTextColor(if (good) 0xFF7CE0A0.toInt() else Ui.GOLD); status.text = s }

    private fun go(raw: String, type: Int, createOnly: Boolean) {
        val n = World.norm(raw)
        val exists = World.exists(this, n)
        if (createOnly && exists) { sfx.play("deny"); say("${n.uppercase()} already exists. Use Enter World to visit it."); return }
        val nick = nameEdit.text.toString().trim()
        if (nick.isNotEmpty()) prof.name = nick
        prof.lastWorldName = n; prof.save()
        sfx.play("door")
        startActivity(Intent(this, GameActivity::class.java).putExtra("world", n).putExtra("type", type))
    }

    private fun refresh() {
        stats.text = "Level ${prof.level()}  ·  ${prof.gems} gems"
        dailyBtn.visibility = if (prof.dailyReady()) View.VISIBLE else View.GONE
        notesBox.removeAllViews()
        if (prof.notes.isEmpty()) notesBox.addView(Ui.text(this, "Nothing new.", 12f, Ui.MUTED))
        for (n in prof.notes.take(6)) notesBox.addView(Ui.text(this, "• $n", 12f, Ui.MUTED))
        listBox.removeAllViews()
        val ws = World.list(this)
        if (ws.isEmpty()) listBox.addView(Ui.text(this, "No worlds yet. Enter a name above to create your first one!", 12f, Ui.MUTED))
        for (w in ws) {
            val row = LinearLayout(this); row.gravity = Gravity.CENTER_VERTICAL
            row.background = Ui.rounded(Ui.CARD2, 10f, this); row.setPadding(dp(10f), dp(6f), dp(6f), dp(6f))
            val col = LinearLayout(this); col.orientation = LinearLayout.VERTICAL
            val lock = if (w.ownerId == prof.uid) "  🔒 yours" else if (w.ownerId.isNotEmpty()) "  🔒 ${w.ownerName}" else ""
            col.addView(Ui.text(this, w.name.uppercase() + lock, 14f, Ui.TEXT, true))
            col.addView(Ui.text(this, World.TYPE_NAMES.getOrElse(w.type) { "Normal" } + " · " +
                DateUtils.getRelativeTimeSpanString(w.modified, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS), 11f, Ui.MUTED))
            row.addView(col, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
            row.addView(Ui.button(this, "Play", Ui.ACCENT, 13f) { go(w.name, w.type, false) })
            row.addView(Ui.button(this, "🗑", Ui.DANGER, 13f) {
                AlertDialog.Builder(this).setTitle("Delete ${w.name.uppercase()}?").setMessage("This cannot be undone.")
                    .setPositiveButton("Delete") { _, _ -> World.delete(this, w.name); refresh() }.setNegativeButton("Cancel", null).show()
            }, LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP).also { it.leftMargin = dp(6f) })
            listBox.addView(row, Ui.lp(Ui.MATCH, Ui.WRAP, 0f, 6, this))
        }
    }

    @Suppress("DEPRECATION")
    override fun onResume() {
        super.onResume()
        prof.reload()
        refresh(); sfx.startMusic()
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
    }
    override fun onPause() { sfx.pauseMusic(); prof.name = nameEdit.text.toString().trim().ifEmpty { prof.name }; prof.save(); super.onPause() }
    override fun onDestroy() { sfx.release(); super.onDestroy() }
}
