package com.blockpatch.game

import android.content.Context
import android.graphics.*
import android.os.Build
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import java.util.Random
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.*

interface GameHost {
    fun promptText(title: String, hint: String, def: String, max: Int, ok: (String) -> Unit)
    fun confirm(title: String, msg: String, ok: () -> Unit)
    fun exitWorld()
    fun stateChanged()
}

/**
 * Native game surface: fixed-allocation render loop on its own thread, touch controls,
 * Growtopia-style punching / placing / drops / double jump.
 */
class GameView(ctx: Context, private val prof: Profile, private val sfx: Sfx, private val host: GameHost) :
    SurfaceView(ctx), SurfaceHolder.Callback, Runnable {

    companion object {
        const val PW = 0.6f
        const val PH = 1.75f
        const val GRAV = 42f
        const val JUMP = 14.2f
        const val MAXFALL = 24f
        const val GLIDE = 2.6f
        const val SPEED = 6.2f
        const val ACC_G = 80f
        const val ACC_A = 50f
        const val FRIC = 90f
        const val REACH = 4.6f
        const val COYOTE = 0.09f
        const val BUFFER = 0.12f
        const val ROWS = 12f
        const val GEM = -1
        const val C_WHITE = 0xFFFFFFFF.toInt()
        const val C_GREEN = 0xFF7CE0A0.toInt()
        const val C_YELLOW = 0xFFFFD866.toInt()
        const val C_RED = 0xFFFF7B72.toInt()
        const val C_BLUE = 0xFF8FC9FF.toInt()
        private const val MP = 160
        private const val MD = 96
        private const val MF = 12
        private const val W = World.W
        private const val H = World.H
    }

    // ---------------------------------------------------------------- state
    private val dens = resources.displayMetrics.density
    private val atlas: Bitmap = Atlas.bmp
    private val rnd = Random()
    private val jobs = ConcurrentLinkedQueue<Runnable>()

    @Volatile private var running = false
    private var thread: Thread? = null
    private var surfaceReady = false
    private var useHw = true
    private var resumed = false

    private var world: World? = null
    private var time = 0f
    private var vw = 0
    private var vh = 0
    private var T = 48
    private var camX = 0f
    private var camY = 0f
    @Volatile private var camXi = 0
    @Volatile private var camYi = 0
    private var camSnap = true

    // player
    private var px = 0f; private var py = 0f; private var vx = 0f; private var vy = 0f
    private var onGround = false
    private var face = 1
    private var airJumps = 1
    private var coyote = 0f
    private var jumpBuf = 0f
    private var walkT = 0f
    private var punchT = 0f
    private var punchCd = 0f
    private var emote = 0
    private var emoteT = 0f
    private var stepT = 0f
    private var flapT = 0f
    private var bubble = ""
    private var bubbleT = 0f
    private var lastVy = 0f

    // input (written by UI thread)
    @Volatile private var holdL = false
    @Volatile private var holdR = false
    @Volatile private var jumpHeld = false
    @Volatile private var jumpReq = false
    @Volatile private var wDown = false
    @Volatile private var wNew = false
    @Volatile private var wx = 0f
    @Volatile private var wy = 0f
    private val role = IntArray(32)
    private var worldPtr = -1

    // interaction state
    private var holdTx = -1; private var holdTy = -1; private var holdT = 0f
    private var pressedEmpty = false
    private var hitX = -1; private var hitY = -1; private var hitN = 0; private var lastHit = 0L

    // effects pools
    private val ppx = FloatArray(MP); private val ppy = FloatArray(MP); private val pvx = FloatArray(MP)
    private val pvy = FloatArray(MP); private val plife = FloatArray(MP); private val pmax = FloatArray(MP)
    private val psz = FloatArray(MP); private val pcol = IntArray(MP); private var pn = 0
    private val dx = FloatArray(MD); private val dy = FloatArray(MD); private val dvx = FloatArray(MD)
    private val dvy = FloatArray(MD); private val dage = FloatArray(MD); private val ditem = IntArray(MD)
    private val dcnt = IntArray(MD); private var dn = 0
    private val ftx = FloatArray(MF); private val fty = FloatArray(MF); private val ftl = FloatArray(MF)
    private val ftc = IntArray(MF); private val fts = arrayOfNulls<String>(MF); private var fn = 0

    // console / banner
    private class Con(val text: String, val color: Int, var t: Float)
    private val cons = ArrayList<Con>()
    private var bannerLines: List<String> = emptyList()
    private var bannerT = 0f
    private var bannerMax = 1f
    private var lastToast = ""
    private var lastToastT = 0f

    // fade (world switching / respawn)
    private var fade = 0f
    private var fadeDir = 0
    private var fadeAct: (() -> Unit)? = null

    // housekeeping
    private var saveT = 0f
    private var uiT = 0f
    private var uiDirty = false
    private var fps = 0
    private var fpsFrames = 0
    private var fpsT = 0f
    private var tipStage = 0

    // ---------------------------------------------------------------- paints / layout
    private val src = Rect()
    private val dst = Rect()
    private val rf = RectF()
    private val tilePaint = Paint().apply { isFilterBitmap = false; isAntiAlias = false }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val skyPaint = Paint()
    private val textP = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD }
    private val path = Path()
    private val rL = RectF(); private val rR = RectF(); private val rJ = RectF()
    @Volatile private var insL = 0f
    @Volatile private var insR = 0f
    @Volatile private var insT = 0f
    @Volatile private var insB = 0f
    private val hotR = Array(5) { RectF() }
    private val arrowL = Path(); private val arrowR = Path(); private val arrowJ = Path()
    private var gemsStr = ""; private var gemsCache = -1
    private var lvStr = ""; private var lvCache = -1

    init {
        holder.addCallback(this)
        isFocusable = false
        holder.setFormat(PixelFormat.RGBA_8888)
    }

    private fun dp(v: Float) = v * dens

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        vw = w; vh = h
        T = max(24, (h / ROWS).roundToInt())
        skyPaint.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), 0xFF7FC1E8.toInt(), 0xFFDDF1EA.toInt(), Shader.TileMode.CLAMP)
        layoutControls()
        camSnap = true
    }

    /** Safe-area insets (notch / rounded corners) from the activity. */
    fun setInsets(l: Int, t: Int, r: Int, b: Int) {
        insL = l.toFloat(); insT = t.toFloat(); insR = r.toFloat(); insB = b.toFloat()
        if (vw > 0) layoutControls()
    }

    private fun layoutControls() {
        val w = vw; val h = vh
        val b = dp(16f); val s = dp(66f); val g = dp(10f)
        val bot = h - b - insB
        rL.set(b + insL, bot - s, b + insL + s, bot)
        rR.set(b + insL + s + g, bot - s, b + insL + s + g + s, bot)
        val js = dp(80f); rJ.set(w - b - insR - js, bot - js, w - b - insR, bot)
        val sz = dp(46f); val gap = dp(6f); val total = sz * 5 + gap * 4
        val sx = (w - total) / 2f; val sy = h - dp(10f) - insB - sz
        for (i in 0 until 5) hotR[i].set(sx + i * (sz + gap), sy, sx + i * (sz + gap) + sz, sy + sz)
        arrow(arrowL, rL, -1); arrow(arrowR, rR, 1)
        val cx = rJ.centerX(); val cy = rJ.centerY(); val r = rJ.width() * 0.22f
        arrowJ.reset(); arrowJ.moveTo(cx, cy - r); arrowJ.lineTo(cx + r, cy + r * 0.7f); arrowJ.lineTo(cx - r, cy + r * 0.7f); arrowJ.close()
    }

    private fun arrow(p: Path, r: RectF, d: Int) {
        val cx = r.centerX(); val cy = r.centerY(); val s = r.width() * 0.22f
        p.reset(); p.moveTo(cx + d * s, cy); p.lineTo(cx - d * s * 0.7f, cy - s); p.lineTo(cx - d * s * 0.7f, cy + s); p.close()
    }

    // ---------------------------------------------------------------- lifecycle
    override fun surfaceCreated(h: SurfaceHolder) { surfaceReady = true; if (resumed) startLoop() }
    override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hh: Int) {}
    override fun surfaceDestroyed(h: SurfaceHolder) { surfaceReady = false; stopLoop() }

    fun onResume() { resumed = true; startLoop() }
    fun onPause() { resumed = false; stopLoop(); saveNow() }

    private fun startLoop() {
        if (thread != null || !surfaceReady) return
        running = true
        thread = Thread(this, "bp-game").also { it.start() }
    }

    private fun stopLoop() {
        running = false
        try { thread?.join(800) } catch (_: InterruptedException) { }
        thread = null
    }

    fun saveNow() { world?.save(context); prof.save() }
    fun worldName(): String? = world?.name
    fun worldOwnerLine(): String {
        val w = world ?: return ""
        return if (w.ownerId == null) "Unlocked" else "Locked by ${w.ownerName}"
    }
    fun nextRipe(): Pair<Long, String>? { val w = world ?: return null; val t = w.nextRipe(); return if (t > 0) t to w.name else null }

    fun queue(r: () -> Unit) { jobs.add(Runnable { r() }) }
    fun enterInitial(name: String, type: Int) = queue { loadWorld(name, type) }

    // ---------------------------------------------------------------- loop
    override fun run() {
        var last = System.nanoTime()
        while (running) {
            val t0 = System.nanoTime()
            var dt = (t0 - last) / 1e9f; last = t0
            if (dt > 0.1f) dt = 0.1f
            try { update(dt) } catch (e: Exception) { e.printStackTrace() }
            var c: Canvas? = null
            if (Build.VERSION.SDK_INT >= 26 && useHw) { try { c = holder.lockHardwareCanvas() } catch (e: Exception) { useHw = false } }
            if (c == null) { try { c = holder.lockCanvas() } catch (e: Exception) { c = null } }
            if (c == null) { try { Thread.sleep(8) } catch (_: InterruptedException) { }; continue }
            try { render(c) } catch (e: Exception) { e.printStackTrace() } finally {
                try { holder.unlockCanvasAndPost(c) } catch (_: Exception) { }
            }
            val lim = prof.fpsLimit
            if (lim > 0) {
                val target = 1_000_000_000L / lim
                val spent = System.nanoTime() - t0
                if (spent < target) try { Thread.sleep((target - spent) / 1_000_000L, ((target - spent) % 1_000_000L).toInt()) } catch (_: InterruptedException) { }
            }
            fpsFrames++; fpsT += dt
            if (fpsT >= 0.5f) { fps = (fpsFrames / fpsT).roundToInt(); fpsFrames = 0; fpsT = 0f }
        }
    }

    // ---------------------------------------------------------------- input
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex; down(e.getPointerId(i), e.getX(i), e.getY(i))
            }
            MotionEvent.ACTION_MOVE -> for (i in 0 until e.pointerCount) {
                val id = e.getPointerId(i); if (id >= role.size) continue
                val x = e.getX(i); val y = e.getY(i)
                if (id == worldPtr) { wx = x; wy = y }
                else if (role[id] == 1 || role[id] == 2) {
                    if (rR.contains(x, y)) role[id] = 2 else if (rL.contains(x, y)) role[id] = 1
                    refresh()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> up(e.getPointerId(e.actionIndex))
            MotionEvent.ACTION_CANCEL -> { for (i in role.indices) role[i] = 0; worldPtr = -1; wDown = false; refresh() }
        }
        return true
    }

    private fun down(id: Int, x: Float, y: Float) {
        if (id >= role.size) return
        when {
            rL.contains(x, y) -> role[id] = 1
            rR.contains(x, y) -> role[id] = 2
            rJ.contains(x, y) -> { role[id] = 3; jumpReq = true }
            else -> {
                var hs = -1
                for (i in 0 until 5) if (hotR[i].contains(x, y)) hs = i
                if (hs >= 0) { role[id] = 5; val slot = hs; queue { selectSlot(slot) } }
                else if (worldPtr < 0) { role[id] = 4; worldPtr = id; wx = x; wy = y; wNew = true; wDown = true }
            }
        }
        refresh()
    }

    private fun up(id: Int) {
        if (id >= role.size) return
        if (id == worldPtr) { worldPtr = -1; wDown = false }
        role[id] = 0
        refresh()
    }

    private fun refresh() {
        var l = false; var r = false; var j = false
        for (v in role) { if (v == 1) l = true else if (v == 2) r = true else if (v == 3) j = true }
        holdL = l; holdR = r; jumpHeld = j
    }

    private fun selectSlot(i: Int) {
        val id = if (i == 0) Id.FIST else prof.quick[i - 1]
        if (i != 0 && id == 0) { host.stateChanged(); return }
        prof.select(id); sfx.play("equip", 0.6f)
        if (id != Id.FIST) toast(ItemDB[id].name + " ×" + prof.count(id), C_WHITE)
        else toast("Fist: punch blocks", C_WHITE)
    }

    /** Called from the UI thread when an item is picked in the backpack. */
    fun selectItem(id: Int) = queue { prof.select(id); sfx.play("equip", 0.6f); uiDirty = true }

    // ---------------------------------------------------------------- messages
    fun con(text: String, color: Int = C_WHITE) {
        cons.add(Con(text, color, 0f)); while (cons.size > 7) cons.removeAt(0)
    }

    fun message(text: String, color: Int = C_WHITE) = queue { con(text, color) }

    fun toast(text: String, color: Int = C_YELLOW) {
        if (text == lastToast && time - lastToastT < 1.5f) return
        lastToast = text; lastToastT = time; con(text, color)
    }

    private fun banner(text: String, secs: Float = 2.6f) {
        textP.textSize = dp(18f)
        val out = ArrayList<String>(); val max = vw * 0.7f
        var cur = StringBuilder()
        for (word in text.split(' ')) {
            val test = if (cur.isEmpty()) word else "$cur $word"
            if (textP.measureText(test) > max && cur.isNotEmpty()) { out.add(cur.toString()); cur = StringBuilder(word) } else { cur = StringBuilder(test) }
        }
        if (cur.isNotEmpty()) out.add(cur.toString())
        bannerLines = out.take(4); bannerT = secs; bannerMax = secs
    }

    fun say(raw: String) = queue { sayNow(raw) }

    private fun sayNow(raw: String) {
        val s = raw.trim().take(80); if (s.isEmpty()) return
        if (s.startsWith("/")) { command(s.substring(1)); return }
        con("<${prof.name}> $s", C_WHITE)
        bubble = s.take(42); bubbleT = 4f
        sfx.play("chat")
    }

    private fun command(c: String) {
        val parts = c.trim().split(' ', limit = 2); val arg = parts.getOrNull(1)?.trim() ?: ""
        when (parts[0].lowercase()) {
            "help" -> { con("Commands: /wave /dance /laugh /warp <world> /clear /exit", C_BLUE) }
            "wave" -> { emote = 1; emoteT = 2.4f; con("${prof.name} waves!", C_GREEN); bubble = "o/"; bubbleT = 2.4f }
            "dance" -> { emote = 2; emoteT = 3.5f; con("${prof.name} is dancing!", C_GREEN); bubble = "♪"; bubbleT = 3.5f }
            "laugh" -> { emote = 3; emoteT = 2.2f; con("${prof.name} laughs.", C_GREEN); bubble = "haha!"; bubbleT = 2.2f }
            "clear" -> cons.clear()
            "exit" -> host.exitWorld()
            "warp" -> if (arg.isEmpty()) con("Usage: /warp <world name>", C_YELLOW) else warp(arg)
            else -> con("Unknown command. Try /help", C_YELLOW)
        }
    }

    fun warp(name: String) = queue { if (fadeDir == 0) { banner("Warping to ${World.norm(name).uppercase()}…", 1.4f); sfx.play("door"); fadeThen { loadWorld(name, World.NORMAL) } } }

    // ---------------------------------------------------------------- world loading
    private fun fadeThen(a: () -> Unit) { if (fadeDir != 0) return; fadeDir = 1; fadeAct = a }

    private fun loadWorld(raw: String, type: Int) {
        world?.save(context)
        val n = World.norm(raw)
        var w = World.load(context, n)
        var created = false
        if (w == null) { w = World(n); w.type = type; w.generate(); w.save(context); created = true }
        world = w
        respawn()
        pn = 0; dn = 0; fn = 0; hitX = -1; holdTx = -1
        con(if (created) "Created world ${n.uppercase()}!" else "Welcome to ${n.uppercase()}", C_GREEN)
        if (w.ownerId == null) con("World is unlocked. Place a World Lock to protect it.", C_YELLOW)
        else con("[LOCKED] World owned by ${w.ownerName}", C_BLUE)
        if (w.ownerId == prof.uid) con("You own this world.", C_GREEN)
        if (tipStage == 0) { con("Tip: tap blocks to punch, hold to keep punching.", C_BLUE); tipStage = 1 }
        else if (tipStage == 1) { con("Tip: jump twice for a double jump!", C_BLUE); tipStage = 2 }
        prof.lastWorldName = n
        uiDirty = true
    }

    private fun respawn() {
        val w = world ?: return
        px = w.doorX + 0.5f - PW / 2; py = w.doorY + 1f - PH
        vx = 0f; vy = 0f; onGround = false; airJumps = maxAir(); camSnap = true
    }

    private fun maxAir() = if (prof.hasWings) 2 else 1

    // ---------------------------------------------------------------- update
    private fun update(dt: Float) {
        while (true) { val j = jobs.poll() ?: break; j.run() }
        time += dt
        val w = world ?: return
        for (c in cons) c.t += dt
        while (cons.isNotEmpty() && cons[0].t > 9f) cons.removeAt(0)
        if (bannerT > 0f) bannerT -= dt
        if (bubbleT > 0f) bubbleT -= dt
        if (emoteT > 0f) { emoteT -= dt; if (emoteT <= 0f) emote = 0 }
        if (punchT > 0f) punchT -= dt
        if (punchCd > 0f) punchCd -= dt

        if (fadeDir == 1) { fade += dt * 4f; if (fade >= 1f) { fade = 1f; val a = fadeAct; fadeAct = null; fadeDir = -1; a?.invoke() } }
        else if (fadeDir == -1) { fade -= dt * 3f; if (fade <= 0f) { fade = 0f; fadeDir = 0 } }

        if (fadeDir == 0 && world === w) {
            handleWorldInput(dt)
            var rem = dt
            while (rem > 0f) { val s = min(rem, 1f / 90f); physics(w, s); rem -= s }
        } else { vx = 0f }
        walkT += abs(vx) * dt * 1.7f

        updateDrops(w, dt); updateParticles(dt)
        updateCamera(dt)

        // autosave + UI refresh
        saveT += dt
        if (saveT > 6f) { saveT = 0f; if (w.dirty) w.save(context); prof.save() }
        uiT += dt
        if (uiDirty && uiT > 0.25f) { uiT = 0f; uiDirty = false; host.stateChanged() }
    }

    private fun updateCamera(dt: Float) {
        val ww = (W * T).toFloat(); val wh = (H * T).toFloat()
        var tx = (px + PW / 2) * T - vw / 2f
        var ty = (py + PH / 2) * T - vh / 2f - T * 0.4f
        tx = if (ww <= vw) (ww - vw) / 2f else tx.coerceIn(0f, ww - vw)
        ty = if (wh <= vh) (wh - vh) / 2f else ty.coerceIn(0f, wh - vh)
        if (camSnap) { camX = tx; camY = ty; camSnap = false }
        else { val k = min(1f, dt * 9f); camX += (tx - camX) * k; camY += (ty - camY) * k }
    }

    // ---------------------------------------------------------------- physics
    private fun approach(v: Float, target: Float, d: Float) = if (v < target) min(v + d, target) else max(v - d, target)

    private fun solid(w: World, tx: Int, ty: Int): Boolean { val t = w.get(tx, ty); return t in 0..127 && ItemDB.solidTbl[t] }

    private fun physics(w: World, dt: Float) {
        val dir = (if (holdR) 1 else 0) - (if (holdL) 1 else 0)
        val speed = if (prof.worn(Slot.FEET) == Id.SNEAKERS) SPEED * 1.15f else SPEED
        vx = approach(vx, dir * speed, (if (dir == 0) FRIC else if (onGround) ACC_G else ACC_A) * dt)
        if (dir != 0) face = dir

        // --- jumping: buffered + coyote time + air jumps (double jump / wings)
        coyote = if (onGround) COYOTE else coyote - dt
        if (jumpReq) { jumpReq = false; jumpBuf = BUFFER }
        if (jumpBuf > 0f) {
            jumpBuf -= dt
            if (onGround || coyote > 0f) doJump(false)
            else if (airJumps > 0) { airJumps--; doJump(true) }
        }

        vy += GRAV * dt
        val maxFall = if (prof.hasWings && jumpHeld && vy > 0f && !onGround) GLIDE else MAXFALL
        if (vy > maxFall) vy = if (maxFall == GLIDE) max(maxFall, vy - 80f * dt) else maxFall
        lastVy = vy

        // --- X
        px += vx * dt
        if (vx > 0f) { val tx = floor(px + PW).toInt(); if (hitCol(w, tx)) { px = tx - PW - 0.001f; vx = 0f } }
        else if (vx < 0f) { val tx = floor(px).toInt(); if (hitCol(w, tx)) { px = tx + 1f + 0.001f; vx = 0f } }
        px = px.coerceIn(0f, W - PW)

        // --- Y
        val oldBottom = py + PH
        py += vy * dt
        val wasGround = onGround
        onGround = false
        val c0 = floor(px + 0.02f).toInt(); val c1 = floor(px + PW - 0.02f).toInt()
        if (vy > 0f) {
            val ty = floor(py + PH).toInt()
            var hit = false
            for (cx in c0..c1) {
                val t = w.get(cx, ty)
                if (solid(w, cx, ty) || (t == Id.PLATFORM && oldBottom <= ty + 0.03f)) { hit = true; break }
            }
            if (hit) {
                py = ty - PH - 0.001f
                if (!wasGround && lastVy > 9f) { sfx.play("land", min(1f, lastVy / 18f)); dust(px + PW / 2, py + PH, 5) }
                vy = 0f; onGround = true; airJumps = maxAir()
            }
        } else if (vy < 0f) {
            val ty = floor(py).toInt()
            var hit = false
            for (cx in c0..c1) if (solid(w, cx, ty)) { hit = true; break }
            if (hit) { py = ty + 1f + 0.001f; vy = 0f }
        }

        // --- feedback: steps, glide
        if (onGround && abs(vx) > 2f) { stepT -= dt; if (stepT <= 0f) { stepT = 0.27f; sfx.play("step", 0.5f, 0.9f + rnd.nextFloat() * 0.2f) } } else stepT = 0f
        if (prof.hasWings && jumpHeld && !onGround && vy > 0f && vy <= GLIDE + 0.5f) {
            flapT -= dt
            if (flapT <= 0f) { flapT = 0.38f; sfx.play("flap", 0.5f); feather(px + PW / 2, py + PH * 0.5f) }
        }

        // --- hazards
        if (py > H + 3f) die("You fell out of the world!")
        else if (touchesLava(w)) die("Ouch! You touched lava.")
    }

    private fun doJump(air: Boolean) {
        vy = -JUMP; onGround = false; coyote = 0f; jumpBuf = 0f
        if (air) { sfx.play("djump"); puff(px + PW / 2, py + PH, if (prof.hasWings) 12 else 8) }
        else { sfx.play("jump"); dust(px + PW / 2, py + PH, 3) }
    }

    private fun hitCol(w: World, tx: Int): Boolean {
        val r0 = floor(py + 0.02f).toInt(); val r1 = floor(py + PH - 0.02f).toInt()
        for (r in r0..r1) if (solid(w, tx, r)) return true
        return false
    }

    private fun touchesLava(w: World): Boolean {
        val x0 = floor(px + 0.12f).toInt(); val x1 = floor(px + PW - 0.12f).toInt()
        val y0 = floor(py + 0.2f).toInt(); val y1 = floor(py + PH - 0.05f).toInt()
        for (y in y0..y1) for (x in x0..x1) if (w.get(x, y) == Id.LAVA) return true
        return false
    }

    private fun die(msg: String) {
        if (fadeDir != 0) return
        sfx.play("death"); con(msg, C_RED); con("Respawning at the main door…", C_YELLOW)
        for (i in 0 until 14) addParticle(px + PW / 2, py + PH / 2, (rnd.nextFloat() - 0.5f) * 8f, -rnd.nextFloat() * 8f, 0.7f, 0xFFFF7B30.toInt(), 0.14f)
        fadeThen { respawn() }
    }

    // ---------------------------------------------------------------- world interaction
    private fun canEdit(w: World) = w.ownerId == null || w.ownerId == prof.uid

    private fun deny(w: World) {
        sfx.play("deny"); toast("This world is locked by ${w.ownerName}.", C_RED)
    }

    private fun inReach(tx: Int, ty: Int): Boolean =
        hypot(tx + 0.5f - (px + PW / 2), ty + 0.5f - (py + PH / 2)) <= REACH

    private fun handleWorldInput(dt: Float) {
        if (!wDown) { holdTx = -1; return }
        val tx = floor((wx + camXi) / T).toInt(); val ty = floor((wy + camYi) / T).toInt()
        val w = world ?: return
        if (wNew) {
            wNew = false; holdTx = tx; holdTy = ty; holdT = 0f
            pressedEmpty = w.get(tx, ty) == Id.AIR
            interact(w, tx, ty, true)
        } else if (tx != holdTx || ty != holdTy) {
            holdTx = tx; holdTy = ty; holdT = 0f
            pressedEmpty = w.get(tx, ty) == Id.AIR
            interact(w, tx, ty, true)
        } else {
            holdT += dt
            if (!pressedEmpty) interact(w, tx, ty, false)
        }
    }

    private fun interact(w: World, tx: Int, ty: Int, first: Boolean) {
        if (!w.inb(tx, ty)) return
        val id = w.get(tx, ty)
        if (id == Id.AIR) { if (first) place(w, tx, ty); return }
        val k = ty * W + tx
        when (id) {
            Id.MAIN_DOOR -> if (first) {
                if (!inReach(tx, ty)) return toast("Too far away.")
                sfx.play("door", 0.7f)
                host.confirm("Exit World", "Leave ${w.name.uppercase()} and go back to the lobby?") { host.exitWorld() }
            }
            Id.DOOR -> if (first) {
                if (!inReach(tx, ty)) return toast("Too far away.")
                val dest = w.links.get(k) ?: "start"
                sfx.play("door"); banner("Entering ${World.norm(dest).uppercase()}…", 1.4f)
                fadeThen { loadWorld(dest, World.NORMAL) }
            } else if (holdT > 0.7f) punch(w, tx, ty)
            Id.SIGN -> if (first) {
                if (!inReach(tx, ty)) return toast("Too far away.")
                val t = w.texts.get(k) ?: ""
                sfx.play("notify", 0.5f); banner(if (t.isEmpty()) "(blank sign)" else t, 3.5f)
            } else if (holdT > 0.7f) punch(w, tx, ty)
            Id.TREE -> if (first) harvest(w, tx, ty)
            Id.WORLD_LOCK -> if (first) {
                val me = w.ownerId == prof.uid
                toast(if (me) "Your World Lock. Hold to remove it." else "World Lock owned by ${w.ownerName}.", C_BLUE)
            } else if (holdT > 0.8f) punch(w, tx, ty)
            else -> punch(w, tx, ty)
        }
    }

    private fun overlapsPlayer(tx: Int, ty: Int) =
        tx + 1 > px && tx < px + PW && ty + 1 > py && ty < py + PH

    private fun place(w: World, tx: Int, ty: Int) {
        val sel = prof.sel
        if (sel == Id.FIST || prof.count(sel) <= 0) return
        val item = ItemDB[sel]
        if (item.slot != null) return toast("Equip clothes from your backpack.")
        if (!item.placeable) return toast("${item.name} can't be placed. Open the backpack to use it.")
        if (!inReach(tx, ty)) return toast("Too far away.")
        if (!canEdit(w)) return deny(w)
        if (item.solid && overlapsPlayer(tx, ty)) return
        face = if (tx + 0.5f > px + PW / 2) 1 else -1; punchT = 0.15f
        val k = ty * W + tx
        if (ItemDB.isSeed(sel)) {
            val below = w.get(tx, ty + 1)
            if (!ItemDB.solidTbl[below.coerceIn(0, 127)] && below != Id.PLATFORM) return toast("Seeds need solid ground to grow.")
            w.set(tx, ty, Id.TREE); w.trees.put(k, Tree(ItemDB.blockOfSeed(sel), System.currentTimeMillis()))
            prof.take(sel, 1); sfx.play("plant"); dust(tx + 0.5f, ty + 1f, 4); uiDirty = true
            return
        }
        when (sel) {
            Id.DOOR -> host.promptText("Door destination", "World name", "start", 20) { v ->
                queue {
                    if (w === world && w.get(tx, ty) == Id.AIR && prof.take(Id.DOOR, 1)) {
                        w.set(tx, ty, Id.DOOR); w.links.put(k, World.norm(v)); sfx.play("place"); uiDirty = true
                    }
                }
            }
            Id.SIGN -> host.promptText("Sign text", "Say something…", "", 80) { v ->
                queue {
                    if (w === world && w.get(tx, ty) == Id.AIR && prof.take(Id.SIGN, 1)) {
                        w.set(tx, ty, Id.SIGN); w.texts.put(k, v.trim()); sfx.play("place"); uiDirty = true
                    }
                }
            }
            Id.WORLD_LOCK -> {
                if (w.ownerId != null) return toast("This world is already locked.", C_RED)
                prof.take(Id.WORLD_LOCK, 1)
                w.set(tx, ty, Id.WORLD_LOCK)
                w.ownerId = prof.uid; w.ownerName = prof.name; w.lockX = tx; w.lockY = ty
                sfx.play("lock"); banner("World Locked! Only you can build here.", 3f)
                con("[WORLD LOCKED] by ${prof.name}", C_BLUE)
                prof.addNote("Locked world ${w.name.uppercase()}")
                addXpAndCheck(10); uiDirty = true
            }
            else -> { prof.take(sel, 1); w.set(tx, ty, sel); sfx.play("place"); uiDirty = true }
        }
    }

    private fun harvest(w: World, tx: Int, ty: Int) {
        val k = ty * W + tx
        val tr = w.trees.get(k)
        if (tr == null) { w.set(tx, ty, Id.AIR); return }
        if (!inReach(tx, ty)) return toast("Too far away.")
        val left = tr.ripeAt() - System.currentTimeMillis()
        if (left > 0) { toast("Growing… ${left / 1000 + 1}s left.", C_YELLOW); return }
        if (!canEdit(w)) return deny(w)
        w.set(tx, ty, Id.AIR)
        face = if (tx + 0.5f > px + PW / 2) 1 else -1; punchT = 0.2f
        val cx = tx + 0.5f; val cy = ty + 0.5f
        val gems = 3 + rnd.nextInt(7)
        var left2 = gems
        while (left2 > 0) { val g = min(left2, 1 + rnd.nextInt(3)); spawnDrop(cx, cy, GEM, g); left2 -= g }
        spawnDrop(cx, cy, tr.seed, 2 + rnd.nextInt(3))
        if (rnd.nextInt(10) < 4) spawnDrop(cx, cy, Id.SEED + tr.seed, 1)
        for (i in 0 until 10) addParticle(cx, cy, (rnd.nextFloat() - 0.5f) * 6f, -rnd.nextFloat() * 5f, 0.7f, 0xFF6FCB5A.toInt(), 0.12f)
        sfx.play("harvest"); floatText(cx, cy - 0.6f, "Harvested!", C_GREEN)
        prof.addQuest()
        addXpAndCheck(5)
    }

    private fun punch(w: World, tx: Int, ty: Int) {
        if (punchCd > 0f) return
        val id = w.get(tx, ty); if (id == Id.AIR) return
        if (!inReach(tx, ty)) return toast("Too far away.")
        if (id == Id.WORLD_LOCK) {
            if (w.ownerId == prof.uid) {
                punchCd = 0.5f
                host.confirm("Remove World Lock", "Unlock ${w.name.uppercase()}? Anyone will be able to build here.") { queue { unlockWorld(w) } }
            } else deny(w)
            return
        }
        if (!canEdit(w)) { punchCd = 0.4f; return deny(w) }
        val item = ItemDB[id]
        if (item.hits <= 0) { punchCd = 0.4f; sfx.play("deny", 0.6f); return toast("It's too strong to break.", C_YELLOW) }
        face = if (tx + 0.5f > px + PW / 2) 1 else -1
        val power = if (prof.hasPickaxe) 2 else 1
        punchCd = if (prof.hasPickaxe) 0.2f else 0.3f; punchT = 0.2f
        val now = System.currentTimeMillis()
        if (tx != hitX || ty != hitY || now - lastHit > 4000) { hitX = tx; hitY = ty; hitN = 0 }
        hitN += power; lastHit = now
        sfx.play("punch", 0.9f, 0.9f + rnd.nextFloat() * 0.25f)
        val col = Atlas.avg[id and 63]
        for (i in 0 until 3) addParticle(tx + 0.5f, ty + 0.5f, (rnd.nextFloat() - 0.5f) * 4f - face * 1.5f, -rnd.nextFloat() * 3f, 0.45f, col, 0.1f)
        if (hitN >= item.hits) breakTile(w, tx, ty, id)
    }

    private fun breakTile(w: World, tx: Int, ty: Int, id: Int) {
        val item = ItemDB[id]
        val cx = tx + 0.5f; val cy = ty + 0.5f
        w.set(tx, ty, Id.AIR)
        hitX = -1; hitN = 0
        sfx.play("break")
        val col = Atlas.avg[id and 63]
        for (i in 0 until 10) addParticle(cx, cy, (rnd.nextFloat() - 0.5f) * 7f, -rnd.nextFloat() * 5f, 0.6f, col, 0.14f)
        if (item.gemMax > 0 && rnd.nextInt(100) < 75) spawnDrop(cx, cy, GEM, item.gemMin + rnd.nextInt(item.gemMax - item.gemMin + 1))
        when (id) {
            Id.DOOR, Id.SIGN, Id.PLATFORM -> spawnDrop(cx, cy, id, 1)
            Id.LAVA -> {}
            else -> {
                if (rnd.nextInt(100) < 35) spawnDrop(cx, cy, id, 1)
                if (item.growSec > 0 && rnd.nextInt(100) < 12) spawnDrop(cx, cy, Id.SEED + id, 1)
            }
        }
        addXpAndCheck(1)
    }

    private fun unlockWorld(w: World) {
        if (w !== world || w.ownerId != prof.uid) return
        if (w.get(w.lockX, w.lockY) == Id.WORLD_LOCK) w.set(w.lockX, w.lockY, Id.AIR)
        w.ownerId = null; w.ownerName = ""; w.lockX = -1; w.lockY = -1; w.dirty = true
        prof.add(Id.WORLD_LOCK, 1)
        sfx.play("unlock"); banner("World Unlocked", 2f); con("[WORLD UNLOCKED]", C_YELLOW)
        prof.addNote("Unlocked world ${w.name.uppercase()}"); uiDirty = true
    }

    private fun addXpAndCheck(n: Int) {
        val lv = prof.addXp(n)
        if (lv > 0) {
            val bonus = 20 * lv
            prof.addGems(bonus)
            sfx.play("levelup"); banner("LEVEL UP! You are now level $lv  (+$bonus gems)", 3.2f)
            con("Level up! Level $lv reached. +$bonus gems", C_YELLOW)
            prof.addNote("Reached level $lv")
        }
        uiDirty = true
    }

    // ---------------------------------------------------------------- drops, particles, floating text
    private fun spawnDrop(x: Float, y: Float, item: Int, count: Int) {
        if (dn >= MD) return
        val i = dn++
        dx[i] = x; dy[i] = y; dvx[i] = (rnd.nextFloat() - 0.5f) * 5f; dvy[i] = -2f - rnd.nextFloat() * 3f
        dage[i] = 0f; ditem[i] = item; dcnt[i] = count
    }

    private fun removeDrop(i: Int) {
        val l = --dn
        dx[i] = dx[l]; dy[i] = dy[l]; dvx[i] = dvx[l]; dvy[i] = dvy[l]; dage[i] = dage[l]; ditem[i] = ditem[l]; dcnt[i] = dcnt[l]
    }

    private fun updateDrops(w: World, dt: Float) {
        val pcx = px + PW / 2; val pcy = py + PH * 0.55f
        var i = 0
        while (i < dn) {
            dage[i] += dt
            if (dage[i] > 150f) { removeDrop(i); continue }
            val ddx = pcx - dx[i]; val ddy = pcy - dy[i]
            val d2 = ddx * ddx + ddy * ddy
            if (dage[i] > 0.4f && d2 < 2.9f) {
                val k = min(1f, dt * 12f)
                dx[i] += ddx * k; dy[i] += ddy * k
                if (d2 < 0.2f) { collect(ditem[i], dcnt[i], dx[i], dy[i]); removeDrop(i); continue }
            } else {
                dvy[i] = min(dvy[i] + GRAV * 0.6f * dt, 14f)
                var nx = dx[i] + dvx[i] * dt; val ny = dy[i] + dvy[i] * dt
                if (solid(w, floor(nx).toInt(), floor(dy[i]).toInt())) { dvx[i] = -dvx[i] * 0.3f; nx = dx[i] }
                dx[i] = nx.coerceIn(0.2f, W - 0.2f)
                val below = w.get(floor(dx[i]).toInt(), floor(ny + 0.2f).toInt())
                if (dvy[i] > 0f && (solid(w, floor(dx[i]).toInt(), floor(ny + 0.2f).toInt()) || (below == Id.PLATFORM && ny + 0.2f - floor(ny + 0.2f) < 0.4f))) {
                    dy[i] = floor(ny + 0.2f) - 0.2f - 0.001f; dvy[i] = 0f; dvx[i] *= 0.7f
                } else dy[i] = ny
                if (dy[i] > H) { removeDrop(i); continue }
            }
            i++
        }
    }

    private fun collect(item: Int, n: Int, x: Float, y: Float) {
        if (item == GEM) { prof.addGems(n); sfx.play("gem", 0.8f, 0.95f + rnd.nextFloat() * 0.2f); floatText(x, y - 0.5f, "+$n", C_BLUE) }
        else { prof.add(item, n); sfx.play("pickup", 0.7f); floatText(x, y - 0.5f, "+$n ${ItemDB[item].name}", C_WHITE) }
        uiDirty = true
    }

    private fun addParticle(x: Float, y: Float, vx0: Float, vy0: Float, life: Float, color: Int, size: Float) {
        if (prof.lowGfx || pn >= MP) return
        val i = pn++
        ppx[i] = x; ppy[i] = y; pvx[i] = vx0; pvy[i] = vy0; plife[i] = life; pmax[i] = life; pcol[i] = color; psz[i] = size
    }

    private fun dust(x: Float, y: Float, n: Int) {
        for (i in 0 until n) addParticle(x + (rnd.nextFloat() - 0.5f) * 0.5f, y - 0.05f, (rnd.nextFloat() - 0.5f) * 2.5f, -rnd.nextFloat() * 1.2f, 0.4f, 0xAAD9D2C0.toInt(), 0.1f)
    }

    private fun puff(x: Float, y: Float, n: Int) {
        for (i in 0 until n) {
            val a = rnd.nextFloat() * 6.283f
            addParticle(x, y, cos(a) * 2.8f, abs(sin(a)) * 1.2f + 0.3f, 0.45f, 0xCCFFFFFF.toInt(), 0.13f)
        }
    }

    private fun feather(x: Float, y: Float) {
        addParticle(x - face * 0.3f, y, -face * (0.5f + rnd.nextFloat()), 0.8f + rnd.nextFloat(), 0.8f, 0xDDFFFFFF.toInt(), 0.08f)
    }

    private fun updateParticles(dt: Float) {
        var i = 0
        while (i < pn) {
            plife[i] -= dt
            if (plife[i] <= 0f) { val l = --pn; ppx[i] = ppx[l]; ppy[i] = ppy[l]; pvx[i] = pvx[l]; pvy[i] = pvy[l]; plife[i] = plife[l]; pmax[i] = pmax[l]; pcol[i] = pcol[l]; psz[i] = psz[l]; continue }
            pvy[i] += 14f * dt; ppx[i] += pvx[i] * dt; ppy[i] += pvy[i] * dt
            i++
        }
        i = 0
        while (i < fn) {
            ftl[i] -= dt; fty[i] -= dt * 1.1f
            if (ftl[i] <= 0f) { val l = --fn; ftx[i] = ftx[l]; fty[i] = fty[l]; ftl[i] = ftl[l]; ftc[i] = ftc[l]; fts[i] = fts[l]; continue }
            i++
        }
    }

    private fun floatText(x: Float, y: Float, s: String, color: Int) {
        if (fn >= MF) { for (j in 1 until MF) { ftx[j - 1] = ftx[j]; fty[j - 1] = fty[j]; ftl[j - 1] = ftl[j]; ftc[j - 1] = ftc[j]; fts[j - 1] = fts[j] }; fn = MF - 1 }
        ftx[fn] = x; fty[fn] = y; ftl[fn] = 1.1f; ftc[fn] = color; fts[fn] = s; fn++
    }

    // ---------------------------------------------------------------- rendering
    private fun drawTile(c: Canvas, idx: Int, x: Int, y: Int, size: Int) {
        val sx = (idx and 15) * 16; val sy = (idx shr 4) * 16
        src.set(sx, sy, sx + 16, sy + 16); dst.set(x, y, x + size, y + size)
        c.drawBitmap(atlas, src, dst, tilePaint)
    }

    private fun drawIcon(c: Canvas, id: Int, l: Float, t: Float, size: Float) {
        dst.set(l.toInt(), t.toInt(), (l + size).toInt(), (t + size).toInt()); Atlas.icon(c, id, dst, tilePaint)
    }

    private fun shadowText(c: Canvas, s: String, x: Float, y: Float, color: Int) {
        textP.color = 0xAA000000.toInt(); c.drawText(s, x + dens, y + dens, textP)
        textP.color = color; c.drawText(s, x, y, textP)
    }

    private fun render(c: Canvas) {
        c.drawRect(0f, 0f, vw.toFloat(), vh.toFloat(), skyPaint)
        val w = world ?: return
        val cxi = camX.roundToInt(); val cyi = camY.roundToInt(); camXi = cxi; camYi = cyi
        val now = System.currentTimeMillis()

        if (!prof.lowGfx) drawClouds(c)

        // ---- tiles
        val x0 = max(0, Math.floorDiv(cxi, T)); val y0 = max(0, Math.floorDiv(cyi, T))
        val x1 = min(W - 1, Math.floorDiv(cxi + vw, T)); val y1 = min(H - 1, Math.floorDiv(cyi + vh, T))
        val lavaFrame = if (!prof.lowGfx && ((time * 2.5f).toInt() and 1) == 1) Id.T_LAVA2 else Id.LAVA
        for (y in y0..y1) {
            val row = y * W; val sy = y * T - cyi
            for (x in x0..x1) {
                val f = w.fg[row + x].toInt()
                val sx = x * T - cxi
                if (f == 0 || ItemDB.clearTbl[f]) { if (w.bg[row + x].toInt() != 0) drawTile(c, Id.T_CAVE_BG, sx, sy, T) }
                if (f == 0) continue
                when (f) {
                    Id.TREE -> drawTree(c, sx, sy, w.trees.get(row + x), now)
                    Id.LAVA -> drawTile(c, lavaFrame, sx, sy, T)
                    else -> drawTile(c, f, sx, sy, T)
                }
            }
        }

        // ---- crack overlay + target outline
        if (hitX >= 0 && hitN > 0 && System.currentTimeMillis() - lastHit < 4000) {
            val it = ItemDB[w.get(hitX, hitY)]
            if (it.hits > 0) {
                val stage = min(3, (hitN * 4 / it.hits))
                drawTile(c, Id.T_CRACK + stage, hitX * T - cxi, hitY * T - cyi, T)
            }
        }
        if (wDown && holdTx >= 0 && w.inb(holdTx, holdTy)) {
            line.color = if (inReach(holdTx, holdTy)) 0xDDFFFFFF.toInt() else 0xAAFF5555.toInt()
            line.strokeWidth = dp(2f)
            c.drawRect((holdTx * T - cxi).toFloat(), (holdTy * T - cyi).toFloat(), ((holdTx + 1) * T - cxi).toFloat(), ((holdTy + 1) * T - cyi).toFloat(), line)
        }

        drawDrops(c, cxi, cyi)
        drawPlayer(c, cxi, cyi)
        drawParticles(c, cxi, cyi)
        drawHud(c, w)

        if (fade > 0f) { fill.color = Color.argb((fade * 255).toInt().coerceIn(0, 255), 0, 0, 0); c.drawRect(0f, 0f, vw.toFloat(), vh.toFloat(), fill) }
    }

    private fun drawClouds(c: Canvas) {
        fill.color = 0x66FFFFFF
        for (i in 0 until 6) {
            val cw = dp(90f + i * 14f); val ch = cw * 0.28f
            val x = ((i * 337f + time * (5f + i * 2.5f)) % (vw + cw * 2)) - cw
            val y = vh * 0.06f + i * vh * 0.07f - camY * 0.25f
            if (y < -ch * 2 || y > vh) continue
            rf.set(x, y, x + cw, y + ch); c.drawRoundRect(rf, ch / 2, ch / 2, fill)
            rf.set(x + cw * 0.2f, y - ch * 0.5f, x + cw * 0.65f, y + ch * 0.5f); c.drawRoundRect(rf, ch / 2, ch / 2, fill)
        }
    }

    private fun drawTree(c: Canvas, sx: Int, sy: Int, tr: Tree?, now: Long) {
        val f = if (tr == null) 1f else min(1f, (now - tr.planted).toFloat() / tr.growMs())
        val cx = sx + T / 2f; val base = sy + T.toFloat()
        fill.color = 0xFF6B4A2B.toInt()
        val th = T * (0.22f + 0.55f * f)
        c.drawRect(cx - T * 0.06f, base - th, cx + T * 0.06f, base, fill)
        fill.color = if (f < 1f) 0xFF7BC96A.toInt() else 0xFF3F9A3A.toInt()
        val r = T * (0.12f + 0.28f * f)
        c.drawCircle(cx, base - th, r, fill)
        if (f >= 1f && tr != null) {
            fill.color = Atlas.avg[tr.seed and 63]
            val fr = T * 0.07f
            c.drawCircle(cx - r * 0.5f, base - th - r * 0.1f, fr, fill)
            c.drawCircle(cx + r * 0.45f, base - th + r * 0.2f, fr, fill)
            c.drawCircle(cx + r * 0.05f, base - th - r * 0.55f, fr, fill)
        }
    }

    private fun drawDrops(c: Canvas, cxi: Int, cyi: Int) {
        for (i in 0 until dn) {
            val bob = if (dvy[i] == 0f) sin(time * 5f + i) * 0.04f else 0f
            val sz = T * 0.42f
            val sx = dx[i] * T - cxi - sz / 2; val sy = (dy[i] + bob) * T - cyi - sz / 2
            if (sx < -sz || sx > vw || sy < -sz || sy > vh) continue
            if (ditem[i] == GEM) drawIcon(c, Id.T_GEM, sx, sy, sz) else drawIcon(c, ditem[i], sx, sy, sz)
        }
    }

    private fun drawParticles(c: Canvas, cxi: Int, cyi: Int) {
        for (i in 0 until pn) {
            val a = (plife[i] / pmax[i]).coerceIn(0f, 1f)
            val base = pcol[i]
            fill.color = Color.argb((((base ushr 24) and 255) * a).toInt(), (base shr 16) and 255, (base shr 8) and 255, base and 255)
            val s = psz[i] * T
            val sx = ppx[i] * T - cxi; val sy = ppy[i] * T - cyi
            c.drawRect(sx - s / 2, sy - s / 2, sx + s / 2, sy + s / 2, fill)
        }
        textP.textSize = dp(13f); textP.textAlign = Paint.Align.CENTER
        for (i in 0 until fn) {
            val a = (ftl[i] / 1.1f).coerceIn(0f, 1f)
            val col = (ftc[i] and 0x00FFFFFF) or ((a * 255).toInt() shl 24)
            val s = fts[i] ?: continue
            textP.color = (0xAA * a).toInt() shl 24; c.drawText(s, ftx[i] * T - cxi + dens, fty[i] * T - cyi + dens, textP)
            textP.color = col; c.drawText(s, ftx[i] * T - cxi, fty[i] * T - cyi, textP)
        }
        textP.textAlign = Paint.Align.LEFT
    }

    // ---- player ------------------------------------------------------------
    private fun drawPlayer(c: Canvas, cxi: Int, cyi: Int) {
        val u = T * PH / 25f
        val cx = (px + PW / 2) * T - cxi; val fy = (py + PH) * T - cyi
        val moving = onGround && abs(vx) > 0.5f
        val swing = if (moving) sin(walkT * 2f) * 3f * u else 0f
        val airSpread = if (!onGround) 1.2f * u else 0f
        val dance = if (emote == 2) abs(sin(time * 9f)) * 2.5f * u else if (emote == 3) abs(sin(time * 14f)) * 1.2f * u else 0f
        val shirt = Profile.COLORS[prof.color]
        c.save(); c.translate(0f, -dance); c.scale(face.toFloat(), 1f, cx, fy)

        // wings (behind body)
        val wing = prof.worn(Slot.BACK)
        if (wing != 0) {
            val flap = if (!onGround) sin(time * (if (vy > 0f && jumpHeld) 7f else 15f)) else sin(time * 2f) * 0.25f
            val demon = wing == Id.WINGS_DEMON
            for (k in 0..1) {
                fill.color = if (k == 0) (if (demon) 0xFF6A1620.toInt() else 0xFFC9D3E6.toInt()) else (if (demon) 0xFF9E2230.toInt() else 0xFFF4F8FF.toInt())
                val ox = cx - 3f * u - k * 2f * u; val oy = fy - 17f * u
                path.reset()
                path.moveTo(ox, oy + 2f * u)
                path.lineTo(ox - 11f * u, oy - (9f + 6f * flap) * u)
                path.lineTo(ox - 14f * u, oy - (1f + 3f * flap) * u)
                path.lineTo(ox - 10f * u, oy + 4f * u)
                path.lineTo(ox - 4f * u, oy + 6f * u)
                path.close()
                c.drawPath(path, fill)
            }
        }

        // legs
        val pants = 0xFF3B4252.toInt()
        val shoeCol = if (prof.worn(Slot.FEET) == Id.SNEAKERS) 0xFFF2F2F7.toInt() else 0xFF4A3426.toInt()
        legs(c, cx - 3.2f * u + swing, fy, u, pants, shoeCol, -airSpread)
        legs(c, cx + 0.2f * u - swing, fy, u, pants, shoeCol, airSpread)
        if (prof.worn(Slot.FEET) == Id.SNEAKERS) { fill.color = 0xFFDC3232.toInt(); c.drawRect(cx - 3.2f * u + swing, fy - 1.4f * u, cx - 0.2f * u + swing, fy - 0.8f * u, fill); c.drawRect(cx + 0.2f * u - swing, fy - 1.4f * u, cx + 3.2f * u - swing, fy - 0.8f * u, fill) }

        // back arm
        val skin = 0xFFF5D3A8.toInt()
        fill.color = shirt; c.drawRect(cx - 5.2f * u, fy - 17f * u + (if (moving) swing * 0.4f else 0f), cx - 3.5f * u, fy - 10f * u + (if (moving) swing * 0.4f else 0f), fill)
        // body
        fill.color = shirt; c.drawRect(cx - 4f * u, fy - 17f * u, cx + 4f * u, fy - 8f * u, fill)
        fill.color = 0x22000000; c.drawRect(cx - 4f * u, fy - 9.2f * u, cx + 4f * u, fy - 8f * u, fill)
        // head
        fill.color = skin; c.drawRect(cx - 4f * u, fy - 25f * u, cx + 4f * u, fy - 17f * u, fill)
        fill.color = 0xFF3A2A1E.toInt(); c.drawRect(cx - 4.3f * u, fy - 25.3f * u, cx + 4.3f * u, fy - 22.6f * u, fill)
        c.drawRect(cx - 4.3f * u, fy - 25.3f * u, cx - 2.6f * u, fy - 19f * u, fill)
        // face
        if (prof.worn(Slot.FACE) == Id.SHADES) { fill.color = 0xFF14141A.toInt(); c.drawRect(cx - 0.5f * u, fy - 22f * u, cx + 4.4f * u, fy - 20f * u, fill); c.drawRect(cx - 1.6f * u, fy - 22f * u, cx - 0.5f * u, fy - 21.3f * u, fill) }
        else { fill.color = 0xFF1B1B22.toInt(); c.drawRect(cx + 1.5f * u, fy - 21.6f * u, cx + 3f * u, fy - 20.2f * u, fill) }
        if (emote == 3) { fill.color = 0xFF7A2E2E.toInt(); c.drawRect(cx + 1f * u, fy - 19.2f * u, cx + 3.4f * u, fy - 18f * u, fill) }
        // hat
        when (prof.worn(Slot.HAT)) {
            Id.TOP_HAT -> { fill.color = 0xFF202026.toInt(); c.drawRect(cx - 5.5f * u, fy - 26.4f * u, cx + 5.5f * u, fy - 25f * u, fill); c.drawRect(cx - 3.3f * u, fy - 32f * u, cx + 3.3f * u, fy - 26.4f * u, fill); fill.color = 0xFFB4283C.toInt(); c.drawRect(cx - 3.3f * u, fy - 28f * u, cx + 3.3f * u, fy - 26.8f * u, fill) }
            Id.CROWN -> { fill.color = 0xFFF4C828.toInt(); c.drawRect(cx - 4f * u, fy - 28f * u, cx + 4f * u, fy - 25.2f * u, fill); for (i in 0..2) c.drawRect(cx - 4f * u + i * 3.2f * u, fy - 30.5f * u, cx - 2.2f * u + i * 3.2f * u, fy - 28f * u, fill); fill.color = 0xFFDC3C3C.toInt(); c.drawRect(cx - 0.8f * u, fy - 27.4f * u, cx + 0.8f * u, fy - 26f * u, fill) }
        }
        // front arm (+ punch / emote animation)
        val shoulderY = fy - 16.4f * u
        val armPhase = if (punchT > 0f) sin(Math.PI.toFloat() * (1f - punchT / 0.2f)) else 0f
        if (punchT > 0f) {
            val len = (3f + 7f * armPhase) * u
            fill.color = shirt; c.drawRect(cx + 2f * u, shoulderY, cx + 2f * u + len, shoulderY + 2.2f * u, fill)
            if (prof.hasPickaxe) drawToolAt(c, cx + 2f * u + len, shoulderY + 1f * u, u, armPhase)
            else { fill.color = skin; c.drawRect(cx + 2f * u + len, shoulderY - 0.4f * u, cx + 2f * u + len + 2.6f * u, shoulderY + 2.6f * u, fill) }
        } else if (emote == 1) {
            val ang = sin(time * 12f) * 0.4f
            c.save(); c.rotate(-150f + ang * 40f, cx + 3.5f * u, shoulderY)
            fill.color = shirt; c.drawRect(cx + 3.5f * u, shoulderY, cx + 5.2f * u, shoulderY + 6.5f * u, fill)
            fill.color = skin; c.drawRect(cx + 3.5f * u, shoulderY + 6.5f * u, cx + 5.2f * u, shoulderY + 8f * u, fill)
            c.restore()
        } else {
            val a = if (moving) -swing * 0.4f else 0f
            fill.color = shirt; c.drawRect(cx + 3.5f * u, shoulderY + a, cx + 5.2f * u, shoulderY + 6.5f * u + a, fill)
            fill.color = skin; c.drawRect(cx + 3.5f * u, shoulderY + 6.5f * u + a, cx + 5.2f * u, shoulderY + 8f * u + a, fill)
            if (prof.hasPickaxe) drawToolAt(c, cx + 4.4f * u, shoulderY + 7f * u + a, u, -0.3f)
        }
        c.restore()

        // name + bubble (unflipped)
        textP.textSize = dp(11f); textP.textAlign = Paint.Align.CENTER
        val topY = fy - 32f * u - dance
        shadowText(c, prof.name, cx, topY, 0xFFFFFFFF.toInt())
        if (bubbleT > 0f && bubble.isNotEmpty()) {
            textP.textSize = dp(12f)
            val tw = textP.measureText(bubble); val pad = dp(7f)
            val by = topY - dp(18f)
            rf.set(cx - tw / 2 - pad, by - dp(15f), cx + tw / 2 + pad, by + dp(6f))
            fill.color = 0xF0FFFFFF.toInt(); c.drawRoundRect(rf, dp(8f), dp(8f), fill)
            textP.color = 0xFF222222.toInt(); c.drawText(bubble, cx, by, textP)
        }
        textP.textAlign = Paint.Align.LEFT
    }

    private fun legs(c: Canvas, x: Float, fy: Float, u: Float, pants: Int, shoe: Int, spread: Float) {
        fill.color = pants; c.drawRect(x + spread, fy - 8f * u, x + spread + 3f * u, fy - 1.6f * u, fill)
        fill.color = shoe; c.drawRect(x + spread, fy - 2f * u, x + spread + 3.6f * u, fy, fill)
    }

    private fun drawToolAt(c: Canvas, x: Float, y: Float, u: Float, phase: Float) {
        c.save(); c.rotate(-30f + phase * 70f, x, y)
        dst.set((x - 1f * u).toInt(), (y - 9f * u).toInt(), (x + 9f * u).toInt(), (y + 1f * u).toInt())
        src.set((Id.PICKAXE and 15) * 16, (Id.PICKAXE shr 4) * 16, (Id.PICKAXE and 15) * 16 + 16, (Id.PICKAXE shr 4) * 16 + 16)
        c.drawBitmap(atlas, src, dst, tilePaint)
        c.restore()
    }

    // ---- HUD ---------------------------------------------------------------
    private fun drawHud(c: Canvas, w: World) {
        val pad = dp(8f) + insL
        val padT = dp(8f) + insT
        // top-left info pill
        textP.textSize = dp(13f); textP.textAlign = Paint.Align.LEFT
        val title = w.name.uppercase()
        val tw = textP.measureText(title)
        val lockW = if (w.ownerId != null) dp(20f) else 0f
        rf.set(pad, padT, pad + tw + dp(20f) + lockW, padT + dp(28f))
        fill.color = 0x99000000.toInt(); c.drawRoundRect(rf, dp(8f), dp(8f), fill)
        shadowText(c, title, pad + dp(10f), padT + dp(19f), C_WHITE)
        if (w.ownerId != null) drawIcon(c, Id.WORLD_LOCK, pad + dp(14f) + tw, padT + dp(6f), dp(16f))

        // gems + level
        if (gemsCache != prof.gems) { gemsCache = prof.gems; gemsStr = prof.gems.toString() }
        val lv = prof.level(); if (lvCache != lv) { lvCache = lv; lvStr = "Lv $lv" }
        val y2 = padT + dp(34f)
        val gw = textP.measureText(gemsStr) + textP.measureText(lvStr) + dp(100f)
        rf.set(pad, y2, pad + gw, y2 + dp(26f)); fill.color = 0x99000000.toInt(); c.drawRoundRect(rf, dp(8f), dp(8f), fill)
        drawIcon(c, Id.T_GEM, pad + dp(6f), y2 + dp(3f), dp(20f))
        shadowText(c, gemsStr, pad + dp(30f), y2 + dp(18f), C_BLUE)
        val lx = pad + dp(34f) + textP.measureText(gemsStr) + dp(10f)
        shadowText(c, lvStr, lx, y2 + dp(18f), C_YELLOW)
        val bx = lx + textP.measureText(lvStr) + dp(6f)
        rf.set(bx, y2 + dp(9f), bx + dp(36f), y2 + dp(17f)); fill.color = 0x66FFFFFF; c.drawRoundRect(rf, dp(4f), dp(4f), fill)
        rf.set(bx, y2 + dp(9f), bx + dp(36f) * prof.levelProgress(), y2 + dp(17f)); fill.color = 0xFFFFD866.toInt(); c.drawRoundRect(rf, dp(4f), dp(4f), fill)

        // console
        var cy = y2 + dp(34f)
        textP.textSize = dp(12f)
        for (m in cons) {
            val a = if (m.t > 7.5f) ((9f - m.t) / 1.5f).coerceIn(0f, 1f) else 1f
            val mw = min(textP.measureText(m.text), vw * 0.55f)
            rf.set(pad, cy, pad + mw + dp(12f), cy + dp(19f))
            fill.color = ((0x88 * a).toInt() shl 24); c.drawRoundRect(rf, dp(6f), dp(6f), fill)
            textP.color = (m.color and 0x00FFFFFF) or ((255 * a).toInt() shl 24)
            c.save(); c.clipRect(rf); c.drawText(m.text, pad + dp(6f), cy + dp(14f), textP); c.restore()
            cy += dp(22f)
        }

        // banner
        if (bannerT > 0f && bannerLines.isNotEmpty()) {
            val a = min(1f, min(bannerT, bannerMax - bannerT + 0.15f) * 4f).coerceIn(0f, 1f)
            textP.textSize = dp(18f); textP.textAlign = Paint.Align.CENTER
            var maxW = 0f; for (l in bannerLines) maxW = max(maxW, textP.measureText(l))
            val hh = bannerLines.size * dp(24f) + dp(16f)
            val top = vh * 0.2f
            rf.set(vw / 2f - maxW / 2 - dp(16f), top, vw / 2f + maxW / 2 + dp(16f), top + hh)
            fill.color = ((0xCC * a).toInt() shl 24); c.drawRoundRect(rf, dp(12f), dp(12f), fill)
            var ty = top + dp(28f)
            for (l in bannerLines) { shadowText(c, l, vw / 2f, ty, (255 * a).toInt() shl 24 or 0x00FFFFFF); ty += dp(24f) }
            textP.textAlign = Paint.Align.LEFT
        }

        drawControls(c)

        if (prof.showFps) { textP.textSize = dp(11f); shadowText(c, "$fps fps", vw - dp(150f), vh - dp(100f), C_GREEN) }
    }

    private fun drawControls(c: Canvas) {
        // movement + jump
        for (i in 0..2) {
            val r = when (i) { 0 -> rL; 1 -> rR; else -> rJ }
            val on = when (i) { 0 -> holdL; 1 -> holdR; else -> jumpHeld }
            fill.color = if (on) 0x88FFFFFF.toInt() else 0x44000000
            if (i == 2) c.drawCircle(r.centerX(), r.centerY(), r.width() / 2, fill) else c.drawRoundRect(r, dp(16f), dp(16f), fill)
            fill.color = if (on) 0xFF222222.toInt() else 0xBBFFFFFF.toInt()
            c.drawPath(when (i) { 0 -> arrowL; 1 -> arrowR; else -> arrowJ }, fill)
        }
        // remaining air jumps indicator
        val n = if (onGround) maxAir() else airJumps
        for (i in 0 until maxAir()) {
            fill.color = if (i < n) 0xFFFFD866.toInt() else 0x55FFFFFF
            c.drawCircle(rJ.centerX() + (i - (maxAir() - 1) / 2f) * dp(14f), rJ.top - dp(8f), dp(4.5f), fill)
        }
        // hotbar
        textP.textSize = dp(11f); textP.textAlign = Paint.Align.RIGHT
        for (i in 0 until 5) {
            val id = if (i == 0) Id.FIST else prof.quick[i - 1]
            val r = hotR[i]
            fill.color = 0x88000000.toInt(); c.drawRoundRect(r, dp(8f), dp(8f), fill)
            if (id != 0) {
                drawIcon(c, id, r.left + dp(7f), r.top + dp(7f), r.width() - dp(14f))
                if (id != Id.FIST) shadowText(c, prof.count(id).toString(), r.right - dp(4f), r.bottom - dp(4f), C_WHITE)
            }
            if (id == prof.sel && id != 0) { line.color = 0xFFFFD866.toInt(); line.strokeWidth = dp(2.5f); c.drawRoundRect(r, dp(8f), dp(8f), line) }
        }
        textP.textAlign = Paint.Align.LEFT
    }
}
