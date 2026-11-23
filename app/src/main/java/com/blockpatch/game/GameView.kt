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
    fun openPanel(name: String)
}

/**
 * Native game surface: fixed-allocation render loop on its own thread, touch controls,
 * Growtopia-style punching / placing / drops / double jump.
 */
class GameView(ctx: Context, private val prof: Profile, private val sfx: Sfx, private val host: GameHost) :
    SurfaceView(ctx), SurfaceHolder.Callback, Runnable {

    companion object {
        const val PW = 0.5f       // hitbox width  (fits 1-block wide gaps)
        const val PH = 0.95f      // hitbox height (fits 1-block high tunnels, like Growtopia)
        const val SPR_H = 1.3f    // drawn sprite height in tiles (taller than the hitbox)
        const val ZMIN = 0.5f
        const val ZMAX = 2.5f
        const val BURN_MAX = 2.0f // seconds in lava before you actually die
        const val PUNCH_CD = 0.32f        // Growtopia-like delay between punches
        const val PUNCH_CD_PICK = 0.20f   // faster with the golden pickaxe
        const val PUNCH_ANIM = 0.24f
        const val FPS = 60
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
        const val ROWS = 15f
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
    private var baseT = 48
    private var zoom = 1f
    @Volatile private var zoomReq = 1f
    private var burn = 0f
    private var flashId = 0
    private var flashT = 0f
    private var inAreaId = 0
    private var inLava = false
    private var inWater = false
    private var lavaMsgT = 0f
    // fishing
    private var fishMode = 0        // 0 idle, 1 waiting, 2 bite
    private var fishT = 0f
    private var fishTx = -1
    private var fishTy = -1
    // pet
    private var petX = 0f
    private var petY = 0f
    private var petInit = false
    // daily quest notifications
    private val qDone = BooleanArray(3)
    private var qDay = 0L
    private var qCheckT = 0f
    // sky cache
    private var skyKey = -1
    private var skyH = -1
    private val skyStops = arrayOf(
        0.00f to intArrayOf(0xFF7FC1E8.toInt(), 0xFFDDF1EA.toInt()),
        0.42f to intArrayOf(0xFF6FB7E8.toInt(), 0xFFE8F0E8.toInt()),
        0.50f to intArrayOf(0xFF3C5AA8.toInt(), 0xFFF2A25C.toInt()),
        0.56f to intArrayOf(0xFF1B2A55.toInt(), 0xFF6E3A5A.toInt()),
        0.70f to intArrayOf(0xFF080D1E.toInt(), 0xFF141C36.toInt()),
        0.90f to intArrayOf(0xFF080D1E.toInt(), 0xFF141C36.toInt()),
        1.00f to intArrayOf(0xFF7FC1E8.toInt(), 0xFFDDF1EA.toInt())
    )
    // pinch zoom (UI thread)
    private var pinchA = -1; private var pinchB = -1
    private var pinchDist0 = 1f; private var pinchZoom0 = 1f
    private var camX = 0f
    private var camY = 0f
    @Volatile private var camXi = 0
    @Volatile private var camYi = 0
    private var camSnap = true

    // player
    private var px = 0f; private var py = 0f; private var vx = 0f; private var vy = 0f
    private var onGround = false
    private var face = 1
    private var airJumps = 0
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
    @Volatile private var wTap = false
    @Volatile private var wNew = false
    @Volatile private var wx = 0f
    @Volatile private var wy = 0f
    private val role = IntArray(32)
    private var worldPtr = -1

    // interaction state
    private var holdTx = -1; private var holdTy = -1; private var holdT = 0f
    private var pressedEmpty = false
    private var curFirst = false
    private var bufTx = -1; private var bufTy = -1; private var bufT = 0f      // buffered tap-punch
    private var pinchActive = false; private var pinchMs = 0L; private var pinchX = 0f; private var pinchY = 0f
    @Volatile private var tap2 = false
    @Volatile private var tap2X = 0f
    @Volatile private var tap2Y = 0f
    private var guideX = -1; private var guideY = -1; private var guideHold = 0f
    private var hitX = -1; private var hitY = -1; private var hitN = 0; private var lastHit = 0L
    private var punchDx = 1f; private var punchDy = 0f   // direction of the last punch
    private var lastBreakTile = -1; private var lastBreakAt = 0L

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
    private class Notif(val title: String, val text: String, val color: Int, val icon: Int, val ttl: Float) {
        var t = 0f; var slide = 0f; var lines: List<String> = emptyList(); var lw = -1f; var y0 = 0f; var y1 = 0f
    }
    private val notifs = ArrayList<Notif>()
    @Volatile private var nbL = 0f
    @Volatile private var nbT = 0f
    @Volatile private var nbR = 0f
    @Volatile private var nbB = 0f
    @Volatile private var nbCount = 0
    private var zoomInit = false
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
    private val hotR = Array(6) { RectF() }
    private val tabR = RectF()
    private val rZi = RectF(); private val rZo = RectF()
    private val rP = RectF()
    @Volatile private var drawerOpen = false
    @Volatile private var dN = 0
    private val dIds = IntArray(24)
    private var drawerT = 0f
    @Volatile private var pbDown = false
    @Volatile private var pbNew = false
    private val arrowL = Path(); private val arrowR = Path(); private val arrowJ = Path()
    private var gemsStr = ""; private var gemsCache = -1
    private var lvStr = ""; private var lvCache = -1

    init {
        holder.addCallback(this)
        isFocusable = false
        holder.setFormat(PixelFormat.RGBA_8888)
    }

    private fun dp(v: Float) = v * dens
    private fun ud(v: Float) = dp(v) * (prof.hudScale / 100f)
    private fun opF() = when (prof.ctrlOp) { 0 -> 0.5f; 2 -> 1.7f; 3 -> 2.6f; else -> 1f }
    private fun a8(base: Int) = (base * opF()).toInt().coerceIn(0, 255)
    fun applyUiSettings() { if (vw > 0) layoutControls() }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        vw = w; vh = h
        if (!zoomInit) { zoomInit = true; zoom = (prof.zoomPct / 100f).coerceIn(ZMIN, ZMAX); zoomReq = zoom }
        baseT = max(24, (h / ROWS).roundToInt())
        T = max(12, (baseT * zoom).roundToInt())
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
        val k = prof.ctrlScale / 100f
        val b = dp(16f); val s = dp(64f) * k; val g = dp(10f) * k
        val bot = h - b - insB
        val js = dp(76f) * k
        if (!prof.leftHand) {
            rL.set(b + insL, bot - s, b + insL + s, bot)
            rR.set(b + insL + s + g, bot - s, b + insL + s + g + s, bot)
            rP.set(w - b - insR - js, bot - js, w - b - insR, bot)
            rJ.set(rP.left - g - js, bot - js, rP.left - g, bot)
        } else {
            rR.set(w - b - insR - s, bot - s, w - b - insR, bot)
            rL.set(rR.left - g - s, bot - s, rR.left - g, bot)
            rP.set(b + insL, bot - js, b + insL + js, bot)
            rJ.set(rP.right + g, bot - js, rP.right + g + js, bot)
        }
        val hk = min(k, 1.2f)
        val sz = dp(46f) * hk; val gap = dp(6f); val total = sz * 6 + gap * 5
        val sx = (w - total) / 2f; val sy = h - dp(8f) - insB - sz
        for (i in 0 until 6) hotR[i].set(sx + i * (sz + gap), sy, sx + i * (sz + gap) + sz, sy + sz)
        tabR.set(w / 2f - dp(34f), sy - dp(22f), w / 2f + dp(34f), sy - dp(2f))
        arrow(arrowL, rL, -1); arrow(arrowR, rR, 1)
        if (prof.zoomBtns) {
            val zs = dp(40f) * min(k, 1.3f); val zt = dp(64f) + insT
            rZi.set(w - dp(8f) - insR - zs, zt, w - dp(8f) - insR, zt + zs)
            rZo.set(rZi.left, zt + zs + dp(6f), rZi.right, zt + zs * 2 + dp(6f))
        } else { rZi.setEmpty(); rZo.setEmpty() }
        val cx = rJ.centerX(); val cy = rJ.centerY(); val r = rJ.width() * 0.22f
        arrowJ.reset(); arrowJ.moveTo(cx, cy - r); arrowJ.lineTo(cx + r, cy + r * 0.7f); arrowJ.lineTo(cx - r, cy + r * 0.7f); arrowJ.close()
    }

    /** Geometry of the docked backpack drawer; pure function of screen size + item count (any thread). */
    private fun drawerGeom(out: RectF): Float {
        val cell = dp(46f); val cols = 8; val pad = dp(8f)
        val n = max(1, dN); val rows = min(3, (n + cols - 1) / cols)
        val wPx = cols * cell + pad * 2; val hPx = rows * cell + pad * 2
        out.set(vw / 2f - wPx / 2, tabR.top - hPx - dp(4f), vw / 2f + wPx / 2, tabR.top - dp(4f))
        return cell
    }

    private fun refreshDrawer() {
        var n = 0
        dIds[n++] = Id.FIST
        for ((id, _) in prof.allItems()) { if (id != Id.FIST && n < dIds.size) dIds[n++] = id }
        dN = n
    }

    private fun toggleDrawer() { drawerOpen = !drawerOpen; if (drawerOpen) refreshDrawer(); sfx.play("equip", 0.4f) }

    private fun drawerTap(x: Float, y: Float) {
        val r = RectF(); val cell = drawerGeom(r)
        if (!r.contains(x, y)) return
        val pad = dp(8f)
        val col = ((x - r.left - pad) / cell).toInt(); val row = ((y - r.top - pad) / cell).toInt()
        if (col !in 0..7 || row < 0) return
        val idx = row * 8 + col
        if (idx >= dN) return
        val id = dIds[idx]; val it = ItemDB[id]
        if (it.slot != null) {
            val on = prof.toggleEquip(id); sfx.play("equip")
            con(if (on) "Equipped ${it.name}" else "Removed ${it.name}", C_GREEN)
        } else if (it.placeable || id == Id.FIST) {
            prof.select(id); sfx.play("equip", 0.6f); toast(it.name, C_WHITE); drawerOpen = false
        } else toast(it.name + ": " + it.desc, C_BLUE)
        refreshDrawer()
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
            val target = 1_000_000_000L / FPS
            val spent = System.nanoTime() - t0
            if (spent < target) try { Thread.sleep((target - spent) / 1_000_000L, ((target - spent) % 1_000_000L).toInt()) } catch (_: InterruptedException) { }
        }
    }

    // ---------------------------------------------------------------- input
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex; down(e.getPointerId(i), e.getX(i), e.getY(i))
            }
            MotionEvent.ACTION_MOVE -> {
                if (pinchA >= 0 && pinchB >= 0) {
                    val ia = e.findPointerIndex(pinchA); val ib = e.findPointerIndex(pinchB)
                    if (ia >= 0 && ib >= 0) {
                        val d = hypot(e.getX(ia) - e.getX(ib), e.getY(ia) - e.getY(ib))
                        if (pinchDist0 <= 0f) { pinchDist0 = max(d, 1f); pinchZoom0 = zoomReq }
                        else {
                            if (!pinchActive && abs(d - pinchDist0) > dp(28f)) {
                                pinchActive = true; wDown = false; wNew = false; wTap = false; holdTx = -1   // now it's a real pinch
                            }
                            if (pinchActive) zoomReq = (pinchZoom0 * d / pinchDist0).coerceIn(ZMIN, ZMAX)
                        }
                    }
                }
                for (i in 0 until e.pointerCount) {
                val id = e.getPointerId(i); if (id >= role.size) continue
                val x = e.getX(i); val y = e.getY(i)
                if (id == worldPtr) { wx = x; wy = y }
                else if (role[id] == 1 || role[id] == 2) {
                    val cr = ctrlAt(x, y); if (cr == 1 || cr == 2) role[id] = cr
                    refresh()
                }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> up(e.getPointerId(e.actionIndex))
            MotionEvent.ACTION_CANCEL -> { for (i in role.indices) role[i] = 0; worldPtr = -1; wDown = false; pinchA = -1; pinchB = -1; pinchActive = false; refresh() }
        }
        return true
    }

    /** Which control button (1=left, 2=right, 3=jump, 6=punch) a touch belongs to. Hit areas are padded so edge taps and the gaps between buttons still count. */
    private fun ctrlAt(x: Float, y: Float): Int {
        for (h in hotR) if (h.contains(x, y)) return 0
        if (tabR.contains(x, y) || rZi.contains(x, y) || rZo.contains(x, y)) return 0
        val m = dp(14f)
        var best = 0; var bd = Float.MAX_VALUE
        for (i in 0..3) {
            val r = when (i) { 0 -> rL; 1 -> rR; 2 -> rJ; else -> rP }
            if (x >= r.left - m && x <= r.right + m && y >= r.top - m && y <= r.bottom + m) {
                val d = hypot(x - r.centerX(), y - r.centerY())
                if (d < bd) { bd = d; best = when (i) { 0 -> 1; 1 -> 2; 2 -> 3; else -> 6 } }
            }
        }
        return best
    }

    private fun down(id: Int, x: Float, y: Float) {
        if (id >= role.size) return
        val nbHit = nbCount > 0 && x >= nbL && x <= nbR && y >= nbT && y <= nbB
        val cr = if (nbHit) 0 else ctrlAt(x, y)
        if (cr != 0) {
            role[id] = cr
            if (cr == 3) jumpReq = true else if (cr == 6) pbNew = true else sfx.play("tap", 0.3f)
            refresh(); return
        }
        when {
            nbCount > 0 && x >= nbL && x <= nbR && y >= nbT && y <= nbB -> { role[id] = 5; sfx.play("tap", 0.5f); val yy = y; queue { dismissNote(yy) } }
            rL.contains(x, y) -> { role[id] = 1; sfx.play("tap", 0.3f) }
            rR.contains(x, y) -> { role[id] = 2; sfx.play("tap", 0.3f) }
            rJ.contains(x, y) -> { role[id] = 3; jumpReq = true }
            rP.contains(x, y) -> { role[id] = 6; pbNew = true }
            tabR.contains(x, y) -> { role[id] = 5; queue { toggleDrawer() } }
            rZi.contains(x, y) -> { role[id] = 5; sfx.play("tap", 0.5f); zoomReq = (zoomReq * 1.25f).coerceIn(ZMIN, ZMAX) }
            rZo.contains(x, y) -> { role[id] = 5; sfx.play("tap", 0.5f); zoomReq = (zoomReq / 1.25f).coerceIn(ZMIN, ZMAX) }
            drawerOpen && RectF().also { drawerGeom(it) }.contains(x, y) -> { role[id] = 5; val fx = x; val fy = y; queue { drawerTap(fx, fy) } }
            else -> {
                var hs = -1
                for (i in 0 until 6) if (hotR[i].contains(x, y)) hs = i
                if (hs >= 0) { role[id] = 5; val slot = hs; queue { selectSlot(slot) } }
                else if (worldPtr < 0) { role[id] = 4; worldPtr = id; wx = x; wy = y; wNew = true; wDown = true; wTap = true }
                else if (pinchA < 0) {
                    // 2nd finger on the world: a pinch only starts once the fingers actually move apart / together.
                    // A quick tap with the 2nd finger is a normal tap (see up()).
                    role[id] = 7; pinchA = worldPtr; pinchB = id
                    pinchActive = false; pinchMs = System.currentTimeMillis(); pinchX = x; pinchY = y
                    pinchDist0 = 0f; pinchZoom0 = zoomReq   // distance is captured on the first MOVE
                }
            }
        }
        refresh()
    }

    private fun up(id: Int) {
        if (id >= role.size) return
        if (id == pinchB && !pinchActive && System.currentTimeMillis() - pinchMs < 260L) { tap2X = pinchX; tap2Y = pinchY; tap2 = true }
        if (id == pinchA || id == pinchB) { pinchA = -1; pinchB = -1; pinchActive = false }
        if (id == worldPtr) { worldPtr = -1; wDown = false }
        role[id] = 0
        refresh()
    }

    private fun refresh() {
        var l = false; var r = false; var j = false; var p = false
        for (v in role) { if (v == 1) l = true else if (v == 2) r = true else if (v == 3) j = true else if (v == 6) p = true }
        holdL = l; holdR = r; jumpHeld = j; pbDown = p
    }

    private fun selectSlot(i: Int) {
        val id = if (i == 0) Id.FIST else prof.quick[i - 1]
        if (i != 0 && id == 0) { host.stateChanged(); return }
        prof.select(id); sfx.play("equip", 0.6f)
        if (id != Id.FIST) toast(ItemDB[id].name + "  x" + prof.count(id), C_WHITE)
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

    private fun farAway() { sfx.play("deny", 0.35f, 1.3f); toast("Too far away.") }

    private fun banner(text: String, secs: Float = 2.6f) = pushNote(text, "", C_BLUE, 0, secs)

    /** Growtopia-style notification box at the top of the screen (game thread). */
    private fun pushNote(title: String, text: String = "", color: Int = C_BLUE, icon: Int = 0, secs: Float = 4f, log: Boolean = false) {
        val dup = notifs.firstOrNull { it.title == title && it.text == text }
        if (dup != null) { dup.t = 0f; return }
        notifs.add(Notif(title, text, color, icon, secs))
        sfx.play("notify", 0.35f)
        while (notifs.size > 3) notifs.removeAt(0)
        if (log) prof.addNote(if (text.isEmpty()) title else "$title: $text")
    }

    /** Thread-safe version for the activities (shop, quests...). */
    fun notifyUser(title: String, text: String = "", color: Int = C_BLUE, icon: Int = 0) = queue { pushNote(title, text, color, icon, 4f, true) }

    private fun dismissNote(y: Float) { for (n in notifs) if (y >= n.y0 && y <= n.y1) n.t = max(n.t, n.ttl - 0.3f) }

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
            "help" -> { con("Commands: /wave /dance /laugh /warp <world> /time <auto|day|sunset|night> /weather <auto|clear|rain|snow> /badges /quests /stats /pet /fish /clear /exit", C_BLUE) }
            "wave" -> { emote = 1; emoteT = 2.4f; con("${prof.name} waves!", C_GREEN); bubble = "o/"; bubbleT = 2.4f }
            "dance" -> { emote = 2; emoteT = 3.5f; con("${prof.name} is dancing!", C_GREEN); bubble = "*"; bubbleT = 3.5f }
            "laugh" -> { emote = 3; emoteT = 2.2f; con("${prof.name} laughs.", C_GREEN); bubble = "haha!"; bubbleT = 2.2f }
            "clear" -> cons.clear()
            "exit" -> host.exitWorld()
            "warp" -> if (arg.isEmpty()) con("Usage: /warp <world name>", C_YELLOW) else warp(arg)
            "badges" -> host.openPanel("badges")
            "quests" -> host.openPanel("quests")
            "stats" -> host.openPanel("stats")
            "pet" -> {
                if (prof.pet != 0) { prof.togglePet(prof.pet); toast("Pet dismissed.", C_BLUE) }
                else host.openPanel("pets")
            }
            "fish" -> con("Equip a Fishing Rod (Store > Fishing), then tap water. Tap again when you see '!' to reel in.", C_BLUE)
            "time" -> {
                val v = arg.lowercase()
                val mode = when (v) { "auto" -> 0; "day" -> 1; "sunset", "dusk" -> 2; "night" -> 3; else -> -1 }
                if (mode < 0) con("Usage: /time <auto|day|sunset|night>", C_YELLOW)
                else { prof.skyMode = mode; prof.save(); toast("Sky: " + arrayOf("Auto", "Day", "Sunset", "Night")[mode], C_BLUE) }
            }
            "weather" -> {
                val v = arg.lowercase()
                val mode = when (v) { "auto" -> 0; "clear", "sunny" -> 1; "rain" -> 2; "snow" -> 3; else -> -1 }
                if (mode < 0) con("Usage: /weather <auto|clear|rain|snow>", C_YELLOW)
                else { prof.weatherMode = mode; prof.save(); toast("Weather: " + arrayOf("Auto", "Clear", "Rain", "Snow")[mode], C_BLUE) }
            }
            else -> con("Unknown command. Try /help", C_YELLOW)
        }
    }

    fun warp(name: String) = queue { if (fadeDir == 0) { banner("Warping to ${World.norm(name).uppercase()}...", 1.4f); sfx.play("door"); fadeThen { loadWorld(name, World.NORMAL) } } }

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
        pn = 0; dn = 0; fn = 0; hitX = -1; holdTx = -1; flashId = 0; flashT = 0f; inAreaId = 0
        fishMode = 0; fishTx = -1; fishTy = -1; petInit = false; inWater = false
        prof.bump(Stat.WORLDS)
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
        burn = 0f; inLava = false; inWater = false; petInit = false
        fishMode = 0; fishTx = -1; fishTy = -1
    }

    private fun maxAir(): Int {
        var n = if (prof.hasWings) 1 else 0
        if (prof.pet == Id.PET_DRAGON) n += 1
        return n
    }

    // ---------------------------------------------------------------- update
    private fun update(dt: Float) {
        while (true) { val j = jobs.poll() ?: break; j.run() }
        for (a in prof.drainBadges()) {
            sfx.play("levelup", 0.9f)
            pushNote("Badge unlocked!", "${a.name}  +${a.reward} gems", C_YELLOW, a.icon, 4.5f, true)
            con("[BADGE] ${a.name}", C_YELLOW)
        }
        if (zoomReq != zoom) { zoom = zoomReq; T = max(12, (baseT * zoom).roundToInt()); camSnap = true; prof.zoomPct = (zoom * 100f).roundToInt() }
        time += dt
        val w = world ?: return
        for (c in cons) c.t += dt
        while (cons.isNotEmpty() && cons[0].t > 9f) cons.removeAt(0)
        if (bannerT > 0f) bannerT -= dt
        if (flashT > 0f) flashT -= dt
        if (guideHold > 0f) guideHold -= dt
        if (notifs.isNotEmpty()) {
            val itn = notifs.iterator()
            while (itn.hasNext()) { val n = itn.next(); n.t += dt; n.slide = min(1f, n.slide + dt * 5f); if (n.t >= n.ttl) itn.remove() }
        }
        nbCount = notifs.size
        if (bubbleT > 0f) bubbleT -= dt
        if (emoteT > 0f) { emoteT -= dt; if (emoteT <= 0f) emote = 0 }
        if (punchT > 0f) punchT -= dt
        if (punchCd > 0f) punchCd -= dt

        // fishing minigame
        if (fishMode > 0) {
            fishT -= dt
            if (fishMode == 1 && fishT <= 0f) {
                fishMode = 2; fishT = 1.15f
                sfx.play("notify", 0.85f)
                floatText(fishTx + 0.5f, fishTy + 0.1f, "!", C_YELLOW)
            } else if (fishMode == 2 && fishT <= 0f) {
                fishMode = 0
                toast("The fish got away...", C_YELLOW)
            }
        }

        // daily quest completion banners (once per quest per day)
        if (prof.questDay != qDay) { qDay = prof.questDay; qDone.fill(false) }
        qCheckT += dt
        if (qCheckT >= 1f) {
            qCheckT = 0f
            val qs = Daily.today()
            for (i in 0 until minOf(3, qs.size)) {
                if (!qDone[i] && !prof.isQuestClaimed(i) && prof.questProgress(i) >= qs[i].goal) {
                    qDone[i] = true
                    pushNote("Daily quest ready!", qs[i].title, C_GREEN, Id.T_GEM, 3.5f)
                }
            }
        }

        if (fadeDir == 1) { fade += dt * 4f; if (fade >= 1f) { fade = 1f; val a = fadeAct; fadeAct = null; fadeDir = -1; a?.invoke() } }
        else if (fadeDir == -1) { fade -= dt * 3f; if (fade <= 0f) { fade = 0f; fadeDir = 0 } }

        if (fadeDir == 0 && world === w) {
            if (bufT > 0f) {
                bufT -= dt
                if (punchCd <= 0f) { val bx = bufTx; val by = bufTy; bufT = 0f; bufTx = -1; if (bx >= 0) { curFirst = false; punch(w, bx, by) } }
            }
            handleWorldInput(dt)
            var rem = dt
            while (rem > 0f) { val s = min(rem, 1f / 90f); physics(w, s); rem -= s }
        } else { vx = 0f }
        if (fadeDir == 0 && w.areas.isNotEmpty()) {
            val ar = w.areaAt(floor(px + PW / 2).toInt(), floor(py + PH / 2).toInt())
            val aid = ar?.id ?: 0
            if (aid != inAreaId) {
                inAreaId = aid
                if (ar != null) pushNote(ItemDB[ar.type].name + " area", if (ar.ownerId == prof.uid) "This area is protected by you." else "Locked by ${ar.ownerName}.", C_BLUE, ar.type, 2.5f)
            }
        } else if (w.areas.isEmpty()) inAreaId = 0
        walkT += abs(vx) * dt * 1.7f

        updateDrops(w, dt); updateParticles(dt)
        updatePet(dt)
        updateCamera(dt)

        // autosave + UI refresh
        saveT += dt
        if (saveT > 6f) { saveT = 0f; if (w.dirty) w.save(context); prof.save() }
        uiT += dt
        if (drawerOpen) { drawerT += dt; if (drawerT > 0.3f) { drawerT = 0f; refreshDrawer() } }
        if (uiDirty && uiT > 0.25f) { uiT = 0f; uiDirty = false; host.stateChanged() }
    }

    private fun updateCamera(dt: Float) {
        val ww = (W * T).toFloat(); val wh = (H * T).toFloat()
        var tx = (px + PW / 2) * T - vw / 2f
        var ty = (py + PH - SPR_H / 2f) * T - vh / 2f - T * 0.2f
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
        val wet = touchesWater(w)
        if (wet != inWater) {
            inWater = wet
            if (wet) { sfx.play("splash", 0.7f); puff(px + PW / 2, py + PH * 0.7f, 5) }
        }
        var speed = if (prof.worn(Slot.FEET) == Id.SNEAKERS) SPEED * 1.15f else SPEED
        if (prof.pet == Id.PET_DOG) speed *= 1.08f
        if (inLava) speed *= 0.55f
        if (inWater) speed *= 0.68f
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
        if (inLava) {
            if (vy > 3.5f) vy = max(3.5f, vy - 60f * dt)       // lava is thick: sink slowly
            if (jumpHeld) vy = min(vy, -5.5f)                   // hold jump to swim out
            airJumps = maxAir()
        }
        if (inWater) {
            if (vy > 3.5f) vy = max(3.5f, vy - 55f * dt)        // water is thick: sink slowly
            if (jumpHeld) vy = min(vy, -4.8f)                   // hold jump to swim up
            airJumps = maxAir()
        }
        var maxFall = if (prof.hasWings && jumpHeld && vy > 0f && !onGround) GLIDE else MAXFALL
        if (inWater) maxFall = 4f
        if (vy > maxFall) vy = if (maxFall == GLIDE) max(maxFall, vy - 80f * dt) else maxFall
        lastVy = vy

        // --- X
        px += vx * dt
        if (vx > 0f) { val tx = floor(px + PW).toInt(); if (hitCol(w, tx) && !(dir > 0 && ledgeAssist(w, tx))) { px = tx - PW - 0.001f; vx = 0f } }
        else if (vx < 0f) { val tx = floor(px).toInt(); if (hitCol(w, tx) && !(dir < 0 && ledgeAssist(w, tx))) { px = tx + 1f + 0.001f; vx = 0f } }
        px = px.coerceIn(0f, W - PW)

        // --- Y
        val oldBottom = py + PH
        py += vy * dt
        val wasGround = onGround
        onGround = false
        val probe = 0.10f
        val c0 = floor(px + probe).toInt(); val c1 = floor(px + PW - probe).toInt()
        if (vy > 0f) {
            val ty = floor(py + PH).toInt()
            fun landingBlocked(cx: Int): Boolean {
                val t = w.get(cx, ty)
                return solid(w, cx, ty) || (t == Id.PLATFORM && oldBottom <= ty + 0.06f)
            }
            var hit = landingBlocked(c0) || landingBlocked(c1)
            // Corner correction: when only one foot is caught on a lip but the tile
            // under the player's centre is open, slide into the gap (cave entrances).
            if (hit && c0 != c1 && landingBlocked(c0) != landingBlocked(c1)) {
                val clear = if (landingBlocked(c0)) c1 else c0
                val targetPx = (clear + 0.5f - PW / 2).coerceIn(0f, W - PW)
                val nx = px + (targetPx - px).coerceIn(-0.3f, 0.3f)
                val nc0 = floor(nx + probe).toInt(); val nc1 = floor(nx + PW - probe).toInt()
                if (!landingBlocked(nc0) && !landingBlocked(nc1)) { px = nx; hit = false }
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
            if (hit && c0 != c1) {
                // Corner correction: the head clipped a lip but the other side is open -> slide into the hole,
                // so jumping up through a one-block gap works even if you are a little off-centre.
                val b0 = solid(w, c0, ty); val b1 = solid(w, c1, ty)
                if (b0 != b1) {
                    val clear = if (b0) c1 else c0
                    val targetPx = (clear + 0.5f - PW / 2).coerceIn(0f, W - PW)
                    val nx = px + (targetPx - px).coerceIn(-0.4f, 0.4f)
                    val n0 = floor(nx + probe).toInt(); val n1 = floor(nx + PW - probe).toInt()
                    var still = false
                    for (cx in n0..n1) if (solid(w, cx, ty)) { still = true; break }
                    if (!still) {
                        val a = floor(nx).toInt(); val b = floor(nx + PW).toInt()
                        val rb = floor(py + PH - 0.12f).toInt()
                        for (r in (ty + 1)..rb) if (solid(w, a, r) || solid(w, b, r)) { still = true; break }
                    }
                    if (!still) { px = nx; hit = false }
                }
            }
            if (hit) {
                py = ty + 1f + 0.001f
                if (lastVy < -7f) sfx.play("bump", min(1f, -lastVy / 14f))
                vy = 0f
            }
        }

        // --- feedback: steps, glide
        if (onGround && abs(vx) > 2f) { stepT -= dt; if (stepT <= 0f) { stepT = 0.27f; sfx.play("step", 0.5f, 0.9f + rnd.nextFloat() * 0.2f) } } else stepT = 0f
        if (prof.hasWings && jumpHeld && !onGround && vy > 0f && vy <= GLIDE + 0.5f) {
            flapT -= dt
            if (flapT <= 0f) { flapT = 0.38f; sfx.play("flap", 0.5f); feather(px + PW / 2, py + PH * 0.5f) }
        }

        // --- hazards
        if (py > H + 3f) die("You fell out of the world!")
        else lavaTick(w, dt)
    }

    /** Growtopia-style lava: it burns you, you have a moment to climb out before you actually die. */
    private fun lavaTick(w: World, dt: Float) {
        val now = touchesLava(w)
        if (now) {
            if (!inLava) { sfx.play("sizzle", 0.8f); pushNote("Ouch! Lava!", "Hold jump to swim out before you burn up.", C_RED, Id.LAVA, 3f); lavaMsgT = 0f }
            burn += dt; lavaMsgT -= dt
            if (lavaMsgT <= 0f) {
                lavaMsgT = 0.25f
                addParticle(px + PW / 2, py + PH * 0.5f, (rnd.nextFloat() - 0.5f) * 3f, -1f - rnd.nextFloat() * 2f, 0.5f, 0xFFFF8A30.toInt(), 0.12f)
            }
            if (burn >= BURN_MAX) { burn = 0f; inLava = false; die("You burned up in the lava!"); return }
        } else burn = max(0f, burn - dt * 0.8f)
        inLava = now
    }

    /** Jumping onto a ledge: if the feet only clip the top of a block by a little, hop up onto it instead of snagging. */
    private fun ledgeAssist(w: World, tx: Int): Boolean {
        if (onGround) return false
        val footRow = floor(py + PH - 0.02f).toInt()
        val overlap = (py + PH) - footRow
        if (overlap < 0f || overlap > 0.34f) return false
        val newPy = footRow - PH - 0.001f
        val rTop = floor(newPy + 0.02f).toInt()
        val ca = floor(px + 0.05f).toInt(); val cb = floor(px + PW - 0.05f).toInt()
        for (r in rTop until footRow) for (cx in min(ca, tx)..max(cb, tx)) if (solid(w, cx, r)) return false
        py = newPy
        if (vy > 0f) vy = 0f
        return true
    }

    private fun doJump(air: Boolean) {
        vy = -JUMP; onGround = false; coyote = 0f; jumpBuf = 0f
        if (air) { sfx.play("djump"); puff(px + PW / 2, py + PH, if (prof.hasWings) 12 else 8) }
        else { sfx.play("jump"); dust(px + PW / 2, py + PH, 3) }
    }

    /** Horizontal collision. While airborne the probe is inset so the player can slip
     *  through one-block gaps in cave walls instead of snagging the corner. */
    private fun hitCol(w: World, tx: Int): Boolean {
        val inset = if (onGround) 0.02f else 0.12f
        val r0 = floor(py + inset).toInt(); val r1 = floor(py + PH - inset).toInt()
        for (r in r0..r1) if (solid(w, tx, r)) return true
        return false
    }

    private fun touchesLava(w: World): Boolean {
        val x0 = floor(px + 0.12f).toInt(); val x1 = floor(px + PW - 0.12f).toInt()
        val y0 = floor(py + 0.2f).toInt(); val y1 = floor(py + PH - 0.05f).toInt()
        for (y in y0..y1) for (x in x0..x1) if (w.get(x, y) == Id.LAVA) return true
        return false
    }

    private fun touchesWater(w: World): Boolean {
        val x0 = floor(px + 0.12f).toInt(); val x1 = floor(px + PW - 0.12f).toInt()
        val y0 = floor(py + 0.2f).toInt(); val y1 = floor(py + PH - 0.05f).toInt()
        for (y in y0..y1) for (x in x0..x1) if (w.get(x, y) == Id.WATER) return true
        return false
    }

    private fun die(msg: String) {
        if (fadeDir != 0) return
        sfx.play("death"); con(msg, C_RED); con("Respawning at the main door...", C_YELLOW)
        for (i in 0 until 14) addParticle(px + PW / 2, py + PH / 2, (rnd.nextFloat() - 0.5f) * 8f, -rnd.nextFloat() * 8f, 0.7f, 0xFFFF7B30.toInt(), 0.14f)
        fadeThen { respawn() }
    }

    // ---------------------------------------------------------------- fishing & pets
    private fun startFishing(tx: Int, ty: Int) {
        if (fishMode > 0) return
        if (!inReach(tx, ty)) return farAway()
        fishTx = tx; fishTy = ty; fishMode = 1; fishT = 0.9f + rnd.nextFloat() * 1.9f
        sfx.play("flap", 0.35f, 1.4f)
        floatText(tx + 0.5f, ty + 0.1f, "Cast!", C_BLUE)
    }

    private fun catchFish(w: World) {
        if (fishMode == 0) return
        fishMode = 0
        val roll = rnd.nextInt(100)
        val fish = when {
            roll < 55 -> Id.FISH_SARDINE
            roll < 82 -> Id.FISH_TILAPIA
            roll < 95 -> Id.FISH_TUNA
            else -> Id.FISH_KOI
        }
        prof.bump(Stat.FISH)
        addXpAndCheck(if (fish == Id.FISH_KOI) 12 else 4)
        // the fish pops out at the player and is collected by the normal drop logic
        spawnDrop(px + PW / 2, py + PH * 0.35f, fish, 1)
        sfx.play("pickup")
        floatText(fishTx + 0.5f, fishTy - 0.2f, "Caught!", C_GREEN)
        pushNote("Fish on!", "You caught a ${ItemDB[fish].name}.", C_GREEN, fish, 3f)
    }

    private fun updatePet(dt: Float) {
        if (prof.pet == 0) { petInit = false; return }
        val tx = px - face * 1.25f
        val ty = py + 0.05f
        if (!petInit) { petX = tx; petY = ty; petInit = true }
        val k = min(1f, dt * 7f)
        petX += (tx - petX) * k
        petY += (ty - petY) * k
        if (abs(petX - tx) > 4f || abs(petY - ty) > 4f) { petX = tx; petY = ty }
    }

    // ---------------------------------------------------------------- world interaction
    /** need: 1 = building (place / plant), 2 = punching (break / harvest). */
    private fun canTile(w: World, tx: Int, ty: Int, need: Int): Boolean {
        if (w.ownerId != null) return w.ownerId == prof.uid          // World Lock: owner only
        val a = w.areaAt(tx, ty) ?: return true                      // unlocked tile
        if (a.ownerId == prof.uid) return true
        return a.type == Id.BUILDER_LOCK && (a.mode and need) != 0   // Builder's Lock permissions
    }
    private fun canBuild(w: World, tx: Int, ty: Int) = canTile(w, tx, ty, 1)
    private fun canBreak(w: World, tx: Int, ty: Int) = canTile(w, tx, ty, 2)

    private fun modeLabel(m: Int) = when (m and 3) {
        0 -> "others can't build or punch"
        1 -> "others can only build"
        2 -> "others can only punch"
        else -> "others can build and punch"
    }

    private fun deny(w: World, tx: Int = -1, ty: Int = -1) {
        sfx.play("deny")
        val a = if (w.ownerId == null) w.areaAt(tx, ty) else null
        if (a != null) {
            flashId = a.id; flashT = 2f
            val extra = if (a.type == Id.BUILDER_LOCK) " (${modeLabel(a.mode)})" else ""
            toast("This area is locked by ${a.ownerName}.$extra", C_RED)
        } else toast("This world is locked by ${w.ownerName}.", C_RED)
    }

    private fun tapAreaLock(w: World, tx: Int, ty: Int) {
        if (!inReach(tx, ty)) return farAway()
        val a = w.areaAt(tx, ty) ?: return toast("This lock has no area.", C_YELLOW)
        flashId = a.id; flashT = 3f
        val nm = ItemDB[a.type].name
        if (a.ownerId != prof.uid) return toast("$nm owned by ${a.ownerName}.", C_BLUE)
        if (a.type == Id.BUILDER_LOCK) {
            a.mode = (a.mode + 1) and 3; w.dirty = true; sfx.play("equip", 0.6f)
            toast("$nm: ${modeLabel(a.mode)}. Tap to change.", C_GREEN)
        } else toast("Your $nm: ${a.tiles.size}/${ItemDB.lockCap(a.type)} tiles. Hold to remove.", C_BLUE)
    }

    private fun placeAreaLock(w: World, tx: Int, ty: Int, sel: Int) {
        if (w.ownerId != null) return toast("This world already has a World Lock.", C_RED)
        val tiles = w.planClaim(tx, ty, ItemDB.lockCap(sel)) ?: return toast("This spot is already locked.", C_RED)
        if (!prof.take(sel, 1)) return
        w.set(tx, ty, sel)
        val a = w.addArea(tx, ty, sel, prof.uid, prof.name, if (sel == Id.BUILDER_LOCK) 1 else 0, tiles)
        flashId = a.id; flashT = 3f
        prof.bump(Stat.PLACE); prof.bump(Stat.LOCK)
        val nm = ItemDB[sel].name
        sfx.play("lock"); pushNote("$nm placed", "${tiles.size} tiles locked.", C_BLUE, sel, 3.5f, true)
        con("[${nm.uppercase()}] ${tiles.size}/${ItemDB.lockCap(sel)} tiles protected", C_BLUE)
        if (sel == Id.BUILDER_LOCK) con("Builder's Lock: others can only build. Tap it to change.", C_YELLOW)
        addXpAndCheck(5); uiDirty = true
    }

    private fun removeAreaLock(w: World, a: AreaLock) {
        if (w !== world || !w.areas.containsKey(a.id)) return
        w.removeArea(a); w.set(a.x, a.y, Id.AIR)
        prof.add(a.type, 1); sfx.play("unlock")
        pushNote("${ItemDB[a.type].name} removed", "${a.tiles.size} tiles are now unprotected.", C_YELLOW, a.type, 3f, true)
        con("[LOCK REMOVED] ${a.tiles.size} tiles are now unprotected", C_YELLOW); uiDirty = true
    }

    private fun lockColor(type: Int) = when (type) {
        Id.SMALL_LOCK -> 0x8FA3B8
        Id.BIG_LOCK -> 0x3FE07A
        Id.HUGE_LOCK -> 0xFF5544
        else -> 0xFFC83A
    }

    private fun inReach(tx: Int, ty: Int): Boolean =
        hypot(tx + 0.5f - (px + PW / 2), ty + 0.5f - (py + PH / 2)) <= REACH

    private fun handlePunchButton(w: World, dt: Float) {
        val hasItem = prof.sel != Id.FIST && prof.count(prof.sel) > 0 && ItemDB[prof.sel].slot == null
        val cx = px + PW / 2; val row = floor(py + PH * 0.55f).toInt()
        val c1 = floor(cx + face * (PW / 2 + 0.35f)).toInt()
        var tx = c1
        if (!hasItem && w.get(c1, row) == Id.AIR && w.get(c1 + face, row) != Id.AIR) tx = c1 + face
        if (pbNew) {
            pbNew = false; holdTx = tx; holdTy = row; holdT = 0f
            pressedEmpty = w.get(tx, row) == Id.AIR
            interact(w, tx, row, true)
            if (pressedEmpty && !hasItem && fishMode == 0) {
                punchT = PUNCH_ANIM * 0.8f; punchDx = face.toFloat(); punchDy = 0f
                sfx.play("swing", 0.6f, 0.95f + rnd.nextFloat() * 0.15f)
            }
            return
        }
        if (tx != holdTx || row != holdTy) {
            holdTx = tx; holdTy = row; holdT = 0f
            pressedEmpty = w.get(tx, row) == Id.AIR
            if (!pressedEmpty) interact(w, tx, row, false)
        } else {
            holdT += dt
            if (!pressedEmpty) interact(w, tx, row, false)
        }
    }

    private fun handleWorldInput(dt: Float) {
        val wp = world
        if (tap2) {
            tap2 = false
            if (wp != null) {
                val t2x = floor((tap2X + camXi) / T).toInt(); val t2y = floor((tap2Y + camYi) / T).toInt()
                interact(wp, t2x, t2y, true); guideX = t2x; guideY = t2y; guideHold = 0.8f
            }
        }
        if (pbDown || pbNew) { if (wp != null) handlePunchButton(wp, dt); return }
        // A tap is latched (wTap) so a press+release inside one frame is never lost.
        val tapped = wTap
        wTap = false
        if (!wDown && !tapped) { holdTx = -1; return }
        val tx = floor((wx + camXi) / T).toInt(); val ty = floor((wy + camYi) / T).toInt()
        val w = world ?: return
        if (wNew || tapped) {
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
        curFirst = first
        val id = w.get(tx, ty)
        if (fishMode > 0) {
            if (!first) return
            if (fishMode == 2) catchFish(w) else toast("Wait for a bite...", C_YELLOW)
            return
        }
        if (id == Id.AIR) { if (first) place(w, tx, ty); return }
        val k = ty * W + tx
        when (id) {
            Id.WATER -> if (first) {
                if (!prof.hasRod) return toast("Equip a Fishing Rod to fish here.", C_YELLOW)
                startFishing(tx, ty)
            }
            Id.MAIN_DOOR -> if (first) {
                if (!inReach(tx, ty)) return farAway()
                sfx.play("door", 0.7f)
                host.confirm("Exit World", "Leave ${w.name.uppercase()} and go back to the lobby?") { host.exitWorld() }
            }
            Id.DOOR -> if (first) {
                if (!inReach(tx, ty)) return farAway()
                val dest = w.links.get(k) ?: "start"
                sfx.play("door"); banner("Entering ${World.norm(dest).uppercase()}...", 1.4f)
                fadeThen { loadWorld(dest, World.NORMAL) }
            } else if (holdT > 0.7f) punch(w, tx, ty)
            Id.SIGN -> if (first) {
                if (!inReach(tx, ty)) return farAway()
                val t = w.texts.get(k) ?: ""
                sfx.play("notify", 0.5f); pushNote("Sign", if (t.isEmpty()) "(blank sign)" else t, C_WHITE, Id.SIGN, 4f)
            } else if (holdT > 0.7f) punch(w, tx, ty)
            Id.TREE -> if (first) harvest(w, tx, ty)
            Id.SMALL_LOCK, Id.BIG_LOCK, Id.HUGE_LOCK, Id.BUILDER_LOCK -> if (first) tapAreaLock(w, tx, ty)
                else if (holdT > 0.8f) punch(w, tx, ty)
            Id.WORLD_LOCK, Id.DIAMOND_LOCK, Id.BLUE_LOCK -> if (first) {
                val me = w.ownerId == prof.uid
                toast(if (me) "Your ${ItemDB[id].name}. Hold to remove it." else "${ItemDB[id].name} owned by ${w.ownerName}.", C_BLUE)
            } else if (holdT > 0.8f) punch(w, tx, ty)
            else -> punch(w, tx, ty)
        }
    }

    private fun overlapsPlayer(tx: Int, ty: Int) =
        tx + 1 > px && tx < px + PW && ty + 1 > py && ty < py + PH

    private fun place(w: World, tx: Int, ty: Int) {
        val sel = prof.sel
        if (sel == Id.FIST || prof.count(sel) <= 0) return
        val k = ty * W + tx
        // don't instantly rebuild a tile that was just punched apart
        if (k == lastBreakTile && System.currentTimeMillis() - lastBreakAt < 500L) return
        val item = ItemDB[sel]
        if (item.slot != null) return toast("Equip clothes from your backpack.")
        if (!item.placeable) return toast("${item.name} can't be placed. Open the backpack to use it.")
        if (!inReach(tx, ty)) return farAway()
        if (!canBuild(w, tx, ty)) return deny(w, tx, ty)
        if (item.solid && overlapsPlayer(tx, ty)) { sfx.play("deny", 0.4f); return }
        face = if (tx + 0.5f > px + PW / 2) 1 else -1; punchT = 0.15f
        if (ItemDB.isSeed(sel)) {
            val below = w.get(tx, ty + 1)
            if (!ItemDB.solidTbl[below.coerceIn(0, 127)] && below != Id.PLATFORM) return toast("Seeds need solid ground to grow.")
            w.set(tx, ty, Id.TREE); w.trees.put(k, Tree(ItemDB.blockOfSeed(sel), System.currentTimeMillis()))
            prof.take(sel, 1); prof.bump(Stat.PLACE)
            sfx.play("plant"); dust(tx + 0.5f, ty + 1f, 4); uiDirty = true
            return
        }
        when (sel) {
            Id.DOOR -> host.promptText("Door destination", "World name", "start", 20) { v ->
                queue {
                    if (w === world && w.get(tx, ty) == Id.AIR && prof.take(Id.DOOR, 1)) {
                        w.set(tx, ty, Id.DOOR); w.links.put(k, World.norm(v)); prof.bump(Stat.PLACE); sfx.play("place"); uiDirty = true
                    }
                }
            }
            Id.SIGN -> host.promptText("Sign text", "Say something...", "", 80) { v ->
                queue {
                    if (w === world && w.get(tx, ty) == Id.AIR && prof.take(Id.SIGN, 1)) {
                        w.set(tx, ty, Id.SIGN); w.texts.put(k, v.trim()); prof.bump(Stat.PLACE); sfx.play("place"); uiDirty = true
                    }
                }
            }
            Id.SMALL_LOCK, Id.BIG_LOCK, Id.HUGE_LOCK, Id.BUILDER_LOCK -> placeAreaLock(w, tx, ty, sel)
            Id.WORLD_LOCK, Id.DIAMOND_LOCK, Id.BLUE_LOCK -> {
                if (w.ownerId != null) return toast("This world is already locked.", C_RED)
                if (w.hasForeignAreas(prof.uid)) return toast("Other players have area locks here.", C_RED)
                prof.take(sel, 1)
                w.set(tx, ty, sel)
                w.ownerId = prof.uid; w.ownerName = prof.name; w.lockX = tx; w.lockY = ty
                prof.bump(Stat.PLACE); prof.bump(Stat.LOCK)
                sfx.play("lock"); pushNote("World Locked!", "Only you can build here.", C_GREEN, Id.WORLD_LOCK, 3.5f, true)
                con("[WORLD LOCKED] by ${prof.name}", C_BLUE)
                prof.addNote("Locked world ${w.name.uppercase()}")
                addXpAndCheck(10); uiDirty = true
            }
            else -> { prof.take(sel, 1); w.set(tx, ty, sel); prof.bump(Stat.PLACE); sfx.play("place"); uiDirty = true }
        }
    }

    private fun harvest(w: World, tx: Int, ty: Int) {
        val k = ty * W + tx
        val tr = w.trees.get(k)
        if (tr == null) { w.set(tx, ty, Id.AIR); return }
        if (!inReach(tx, ty)) return farAway()
        val left = tr.ripeAt() - System.currentTimeMillis()
        if (left > 0) { toast("Growing... ${left / 1000 + 1}s left.", C_YELLOW); return }
        if (!canBreak(w, tx, ty)) return deny(w, tx, ty)
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
        prof.bump(Stat.HARVEST)
        addXpAndCheck(5)
    }

    private fun punch(w: World, tx: Int, ty: Int) {
        if (punchCd > 0f) {
            // a tap that lands during the punch cooldown is remembered instead of being lost
            if (curFirst && w.get(tx, ty) != Id.AIR) { bufTx = tx; bufTy = ty; bufT = 0.35f }
            return
        }
        val id = w.get(tx, ty); if (id == Id.AIR) return
        if (!inReach(tx, ty)) return farAway()
        if (ItemDB.isAreaLock(id)) {
            val a = w.areaAt(tx, ty)
            if (a != null && a.ownerId == prof.uid) {
                punchCd = 0.5f
                host.confirm("Remove ${ItemDB[id].name}", "Remove this lock? Its ${a.tiles.size} tiles will no longer be protected.") { queue { removeAreaLock(w, a) } }
            } else deny(w, tx, ty)
            return
        }
        if (ItemDB.isWorldLock(id)) {
            if (w.ownerId == prof.uid) {
                punchCd = 0.5f
                host.confirm("Remove World Lock", "Unlock ${w.name.uppercase()}? Anyone will be able to build here.") { queue { unlockWorld(w) } }
            } else deny(w)
            return
        }
        if (!canBreak(w, tx, ty)) { punchCd = 0.4f; return deny(w, tx, ty) }
        val item = ItemDB[id]
        if (item.hits <= 0) { punchCd = 0.4f; sfx.play("deny", 0.6f); return toast("It's too strong to break.", C_YELLOW) }
        face = if (tx + 0.5f > px + PW / 2) 1 else -1
        // point the punch at the tile that was tapped (Growtopia-style directional punch)
        val ax = (tx + 0.5f) - (px + PW / 2)
        val ay = (ty + 0.5f) - (py + PH * 0.55f)
        val al = hypot(ax, ay).coerceAtLeast(0.001f)
        punchDx = ax / al; punchDy = ay / al
        val power = if (prof.hasPickaxe) 2 else 1
        punchCd = if (prof.hasPickaxe) PUNCH_CD_PICK else PUNCH_CD
        punchT = PUNCH_ANIM
        val now = System.currentTimeMillis()
        if (tx != hitX || ty != hitY || now - lastHit > 3000L) { hitX = tx; hitY = ty; hitN = 0 }
        hitN += power; lastHit = now
        sfx.play("punch", 0.9f, 0.9f + rnd.nextFloat() * 0.25f)
        val col = Atlas.avg[id % Atlas.TILES]
        for (i in 0 until 3) addParticle(tx + 0.5f, ty + 0.5f, (rnd.nextFloat() - 0.5f) * 4f - face * 1.5f, -rnd.nextFloat() * 3f, 0.45f, col, 0.1f)
        if (hitN >= item.hits) breakTile(w, tx, ty, id)
    }

    private fun breakTile(w: World, tx: Int, ty: Int, id: Int) {
        val item = ItemDB[id]
        val cx = tx + 0.5f; val cy = ty + 0.5f
        w.set(tx, ty, Id.AIR)
        hitX = -1; hitN = 0
        // short grace period so the next tap doesn't instantly rebuild the broken tile
        lastBreakTile = ty * W + tx; lastBreakAt = System.currentTimeMillis()
        sfx.play("break")
        val col = Atlas.avg[id % Atlas.TILES]
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
        prof.bump(Stat.BREAK)
        addXpAndCheck(1)
    }

    private fun unlockWorld(w: World) {
        if (w !== world || w.ownerId != prof.uid) return
        val lid = w.get(w.lockX, w.lockY)
        if (ItemDB.isWorldLock(lid)) w.set(w.lockX, w.lockY, Id.AIR)
        w.ownerId = null; w.ownerName = ""; w.lockX = -1; w.lockY = -1; w.dirty = true
        prof.add(if (ItemDB.isWorldLock(lid)) lid else Id.WORLD_LOCK, 1)
        sfx.play("unlock"); pushNote("World Unlocked", "Anyone can build here now.", C_YELLOW, Id.WORLD_LOCK, 3f, true); con("[WORLD UNLOCKED]", C_YELLOW)
        prof.addNote("Unlocked world ${w.name.uppercase()}"); uiDirty = true
    }

    private fun addXpAndCheck(n: Int) {
        val lv = prof.addXp(n)
        if (lv > 0) {
            val bonus = 20 * lv
            prof.addGems(bonus)
            sfx.play("levelup"); pushNote("LEVEL UP!", "You are now level $lv  (+$bonus gems)", C_YELLOW, Id.T_GEM, 4f, true)
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
            if (dage[i] > 0.4f && d2 < (if (prof.pet == Id.PET_SLIME) 7.5f else 2.9f)) {
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
        if (item == GEM) { val gain = n + if (prof.pet == Id.PET_CAT) 1 else 0; prof.addGems(gain); sfx.play("gem", 0.8f, 0.95f + rnd.nextFloat() * 0.2f); floatText(x, y - 0.5f, "+$gain", C_BLUE) }
        else {
            prof.add(item, n); sfx.play("pickup", 0.7f); floatText(x, y - 0.5f, "+$n ${ItemDB[item].name}", C_WHITE)
            prof.autoCompress()?.let { m -> sfx.play("lock"); pushNote("Locks compressed", m, C_BLUE, Id.DIAMOND_LOCK, 4f, true) }
        }
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
        if (vw <= 0 || vh <= 0) return
        drawSky(c)
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
                    Id.WATER -> drawWater(c, sx, sy)
                    else -> drawTile(c, f, sx, sy, T)
                }
            }
        }

        // ---- locked-area overlay (flashes on tap/placement; faint for all areas while holding a lock)
        val showAll = ItemDB.isAreaLock(prof.sel)
        if (w.areas.isNotEmpty() && (showAll || flashT > 0f)) {
            for (a in w.areas.values) {
                val flash = a.id == flashId && flashT > 0f
                if (!flash && !showAll) continue
                val al = if (flash) (80 + 45 * sin(time * 8f)).toInt().coerceIn(0, 255) else 40
                fill.color = (al shl 24) or lockColor(a.type)
                for (k in a.tiles) {
                    val ax = k % W; val ay = k / W
                    if (ax < x0 || ax > x1 || ay < y0 || ay > y1) continue
                    val sx = (ax * T - cxi).toFloat(); val sy = (ay * T - cyi).toFloat()
                    c.drawRect(sx, sy, sx + T, sy + T, fill)
                }
            }
        }

        // ---- crack overlay + target outline
        if (hitX >= 0 && hitN > 0 && System.currentTimeMillis() - lastHit < 3000) {
            val it = ItemDB[w.get(hitX, hitY)]
            if (it.hits > 0) {
                val stage = min(3, (hitN * 4 / it.hits))
                drawTile(c, Id.T_CRACK + stage, hitX * T - cxi, hitY * T - cyi, T)
            }
        }
        drawGuide(c, w, cxi, cyi)

        drawDrops(c, cxi, cyi)
        drawFishing(c, cxi, cyi)
        drawPet(c, cxi, cyi)
        drawPlayer(c, cxi, cyi)
        drawParticles(c, cxi, cyi)
        drawNight(c)
        drawWeather(c)
        if (burn > 0f) { fill.color = Color.argb((90f * (burn / BURN_MAX)).toInt().coerceIn(0, 120), 255, 60, 20); c.drawRect(0f, 0f, vw.toFloat(), vh.toFloat(), fill) }
        drawHud(c, w)

        if (fade > 0f) { fill.color = Color.argb((fade * 255).toInt().coerceIn(0, 255), 0, 0, 0); c.drawRect(0f, 0f, vw.toFloat(), vh.toFloat(), fill) }
    }

    // ---- placement / punch guide box --------------------------------------
    /** 0 = nothing to show, 1 = you can place here, 2 = blocked, 3 = punch / tap target. */
    private fun guideState(w: World, tx: Int, ty: Int): Int {
        if (!w.inb(tx, ty)) return 0
        val id = w.get(tx, ty)
        val reach = inReach(tx, ty)
        if (id != Id.AIR) return if (reach) 3 else 2
        val sel = prof.sel
        if (sel == Id.FIST || prof.count(sel) <= 0 || fishMode > 0) return 0
        val item = ItemDB[sel]
        if (item.slot != null || !item.placeable) return 2
        if (!reach || !canBuild(w, tx, ty)) return 2
        if (item.solid && overlapsPlayer(tx, ty)) return 2
        if (ItemDB.isSeed(sel)) {
            val below = w.get(tx, ty + 1)
            if (!ItemDB.solidTbl[below.coerceIn(0, 127)] && below != Id.PLATFORM) return 2
        }
        if (ItemDB.isAreaLock(sel) && (w.ownerId != null || w.areaAt(tx, ty) != null)) return 2
        if (ItemDB.isWorldLock(sel) && w.ownerId != null) return 2
        return 1
    }

    private fun drawGuide(c: Canvas, w: World, cxi: Int, cyi: Int) {
        var gx = -1; var gy = -1; var a = 1f
        if (holdTx >= 0 && w.inb(holdTx, holdTy)) {
            gx = holdTx; gy = holdTy; guideX = gx; guideY = gy; guideHold = 0.8f          // finger / punch button is down
        } else if (guideHold > 0f) {
            gx = guideX; gy = guideY; a = (guideHold / 0.8f).coerceIn(0f, 1f)            // fades out after release
        } else {
            val sel = prof.sel                                                              // idle: show where the item will go
            if (sel != Id.FIST && prof.count(sel) > 0 && fishMode == 0 && ItemDB[sel].slot == null && ItemDB[sel].placeable) {
                gx = floor(px + PW / 2 + face * (PW / 2 + 0.35f)).toInt(); gy = floor(py + PH * 0.55f).toInt(); a = 0.55f
            }
        }
        if (gx < 0 || !w.inb(gx, gy)) return
        val st = guideState(w, gx, gy)
        if (st == 0 || (a < 0.6f && guideHold <= 0f && st == 3)) return
        val col = when (st) { 1 -> 0x4CE06B; 3 -> 0xFFFFFF; else -> 0xFF5555 }
        val s = T.toFloat(); val sx = (gx * T - cxi).toFloat(); val sy = (gy * T - cyi).toFloat()
        val pulse = 0.78f + 0.22f * sin(time * 8f)
        if (st == 1) {   // ghost of the block / seed about to be placed
            val sel = prof.sel
            val gid = if (ItemDB.isSeed(sel)) ItemDB.blockOfSeed(sel) else sel
            fill.color = ((80 * a).toInt() shl 24) or (Atlas.avg[gid % Atlas.TILES] and 0xFFFFFF)
            c.drawRect(sx, sy, sx + s, sy + s, fill)
        }
        fill.color = ((if (st == 2) 60 else 40) * a).toInt() shl 24 or col
        c.drawRect(sx, sy, sx + s, sy + s, fill)
        line.color = ((255 * a * pulse).toInt().coerceIn(0, 255) shl 24) or col
        line.strokeWidth = dp(2f)
        c.drawRect(sx + 1f, sy + 1f, sx + s - 1f, sy + s - 1f, line)
        val k = s * 0.3f
        line.strokeWidth = dp(3.5f)
        c.drawLine(sx, sy, sx + k, sy, line); c.drawLine(sx, sy, sx, sy + k, line)
        c.drawLine(sx + s, sy, sx + s - k, sy, line); c.drawLine(sx + s, sy, sx + s, sy + k, line)
        c.drawLine(sx, sy + s, sx + k, sy + s, line); c.drawLine(sx, sy + s, sx, sy + s - k, line)
        c.drawLine(sx + s, sy + s, sx + s - k, sy + s, line); c.drawLine(sx + s, sy + s, sx + s, sy + s - k, line)
        if (st == 2) {   // blocked: red cross
            val m = s * 0.3f; line.strokeWidth = dp(3f)
            c.drawLine(sx + m, sy + m, sx + s - m, sy + s - m, line); c.drawLine(sx + s - m, sy + m, sx + m, sy + s - m, line)
        }
    }

    // ---- sky / weather / water -------------------------------------------
    private fun mix(a: Int, b: Int, t: Float): Int {
        val ar = (a shr 16) and 255; val ag = (a shr 8) and 255; val ab = a and 255
        val br = (b shr 16) and 255; val bg = (b shr 8) and 255; val bb = b and 255
        val r = (ar + (br - ar) * t).toInt().coerceIn(0, 255)
        val g = (ag + (bg - ag) * t).toInt().coerceIn(0, 255)
        val bl = (ab + (bb - ab) * t).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
    }

    private fun skyPhase(): Float = when (prof.skyMode) {
        1 -> 0.22f
        2 -> 0.52f
        3 -> 0.78f
        else -> (time / 240f) % 1f
    }

    private fun nightFactor(p: Float): Float = when {
        p < 0.50f -> 0f
        p < 0.56f -> (p - 0.50f) / 0.06f
        p < 0.88f -> 1f
        p < 0.96f -> 1f - (p - 0.88f) / 0.08f
        else -> 0f
    }

    private fun drawSky(c: Canvas) {
        val p = skyPhase()
        var a = skyStops[0]; var b = skyStops[skyStops.size - 1]
        for (i in 0 until skyStops.size - 1) {
            if (p >= skyStops[i].first && p <= skyStops[i + 1].first) { a = skyStops[i]; b = skyStops[i + 1]; break }
        }
        val span = max(0.0001f, b.first - a.first)
        val t = ((p - a.first) / span).coerceIn(0f, 1f)
        val top = mix(a.second[0], b.second[0], t)
        val bot = mix(a.second[1], b.second[1], t)
        val key = top xor bot
        if (skyKey != key || skyH != vh) {
            skyKey = key; skyH = vh
            skyPaint.shader = LinearGradient(0f, 0f, 0f, vh.toFloat(), top, bot, Shader.TileMode.CLAMP)
        }
        c.drawRect(0f, 0f, vw.toFloat(), vh.toFloat(), skyPaint)

        val night = nightFactor(p)
        // stars
        if (night > 0.03f) {
            for (i in 0 until 44) {
                val x = ((i * 127 + 31) % max(1, vw)).toFloat()
                val y = ((i * 79 + 17) % max(1, (vh * 0.62f).toInt())).toFloat()
                val tw = (0.55f + 0.45f * sin(time * 2.2f + i * 0.9f)).coerceIn(0f, 1f)
                fill.color = (((night * 190f * tw).toInt().coerceIn(0, 255)) shl 24) or 0xFFFFFF
                c.drawCircle(x, y, dp(1.2f), fill)
            }
        }
        // sun by day, moon by night
        val arc = p
        val sx = vw * (0.08f + 0.84f * arc)
        val sy = vh * (0.26f - 0.14f * sin(arc * Math.PI.toFloat()))
        if (night < 0.55f) {
            fill.color = 0x33FFE58A
            c.drawCircle(sx, sy, dp(30f), fill)
            fill.color = 0xFFFFE58A.toInt()
            c.drawCircle(sx, sy, dp(17f), fill)
        } else {
            fill.color = 0x33EDEDF2
            c.drawCircle(sx, sy, dp(24f), fill)
            fill.color = 0xFFEDEDF2.toInt()
            c.drawCircle(sx, sy, dp(14f), fill)
            fill.color = 0xFFD0D4E0.toInt()
            c.drawCircle(sx - dp(4f), sy - dp(3f), dp(4f), fill)
            c.drawCircle(sx + dp(5f), sy + dp(4f), dp(2.5f), fill)
        }
    }

    private fun drawNight(c: Canvas) {
        val n = nightFactor(skyPhase())
        if (n <= 0.02f) return
        fill.color = ((n * 68f).toInt().coerceIn(0, 90) shl 24)
        c.drawRect(0f, 0f, vw.toFloat(), vh.toFloat(), fill)
    }

    private fun weatherNow(): Int {
        if (prof.weatherMode != 0) return prof.weatherMode
        val s = (world?.name?.hashCode() ?: 0) + (time / 90f).toInt()
        return when (((s % 13) + 13) % 13) { 0, 1 -> 2; 2 -> 3; else -> 1 }
    }

    private fun drawWeather(c: Canvas) {
        val wy = weatherNow()
        if (wy == 1 || prof.lowGfx) return
        if (wy == 2) {
            line.color = 0x99A8D0FF.toInt(); line.strokeWidth = dp(1.2f)
            val n = 70
            for (i in 0 until n) {
                val x = ((i * 83 + time * 240f) % (vw + 40)) - 20f
                val y = ((i * 47 + time * 520f) % (vh + 40)) - 20f
                c.drawLine(x, y, x - dp(3f), y + dp(12f), line)
            }
        } else {
            fill.color = 0xCCFFFFFF.toInt()
            for (i in 0 until 55) {
                val sway = sin(time * 1.3f + i) * dp(10f)
                val x = ((i * 71 + time * 32f + sway) % (vw + 40)) - 20f
                val y = ((i * 43 + time * 62f) % (vh + 40)) - 20f
                c.drawCircle(x, y, dp(1.6f), fill)
            }
        }
    }

    private fun drawWater(c: Canvas, sx: Int, sy: Int) {
        fill.color = 0xCC2F7FB8.toInt()
        c.drawRect(sx.toFloat(), sy.toFloat(), (sx + T).toFloat(), (sy + T).toFloat(), fill)
        fill.color = 0x55BFE8FF
        c.drawRect(sx.toFloat(), sy.toFloat(), (sx + T).toFloat(), sy + T * 0.28f, fill)
        line.color = 0x99DFF6FF.toInt(); line.strokeWidth = max(1f, T * 0.05f)
        val wob = sin(time * 2.2f + sx * 0.05f) * T * 0.10f
        c.drawLine(sx.toFloat(), sy + T * 0.55f + wob, (sx + T).toFloat(), sy + T * 0.55f - wob, line)
        c.drawLine(sx.toFloat(), sy + T * 0.82f - wob, (sx + T).toFloat(), sy + T * 0.82f + wob, line)
    }

    private fun drawFishing(c: Canvas, cxi: Int, cyi: Int) {
        if (fishMode == 0 || fishTx < 0) return
        val bx = (fishTx + 0.5f) * T - cxi
        val by = (fishTy + 0.35f) * T - cyi + sin(time * 4f) * T * 0.06f
        line.color = 0x99FFFFFF.toInt(); line.strokeWidth = dp(1f)
        c.drawLine((px + PW / 2) * T - cxi, (py + PH * 0.55f) * T - cyi, bx, by, line)
        fill.color = 0xFFFFFFFF.toInt(); c.drawCircle(bx, by, T * 0.10f, fill)
        fill.color = 0xFFE04040.toInt(); c.drawCircle(bx, by - T * 0.08f, T * 0.06f, fill)
        if (fishMode == 2) {
            textP.textSize = dp(20f); textP.textAlign = Paint.Align.CENTER
            shadowText(c, "!", bx, by - T * 0.45f, C_YELLOW)
            textP.textAlign = Paint.Align.LEFT
        }
    }

    private fun drawPet(c: Canvas, cxi: Int, cyi: Int) {
        val id = prof.pet
        if (id == 0) return
        val sz = T * 0.85f
        val bob = sin(time * 5f) * T * 0.05f
        val x = petX * T - cxi - sz / 2
        val y = petY * T - cyi - sz / 2 + bob
        fill.color = 0x33000000
        c.drawOval(x + sz * 0.15f, y + sz * 0.85f, x + sz * 0.85f, y + sz * 1.05f, fill)
        drawIcon(c, id, x, y, sz)
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
            fill.color = Atlas.avg[tr.seed % Atlas.TILES]
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
        val u = T * SPR_H / 25f
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
        val armPhase = if (punchT > 0f) sin(Math.PI.toFloat() * (1f - punchT / PUNCH_ANIM)) else 0f
        if (punchT > 0f) {
            val len = (3f + 7f * armPhase) * u
            // rotate the arm toward the tile that was punched (the canvas is mirrored by `face`)
            val localDx = punchDx * face
            val ang = Math.toDegrees(atan2(punchDy.toDouble(), localDx.toDouble())).toFloat()
            c.save(); c.rotate(ang, cx + 2f * u, shoulderY + 1.1f * u)
            fill.color = shirt; c.drawRect(cx + 2f * u, shoulderY, cx + 2f * u + len, shoulderY + 2.2f * u, fill)
            if (prof.hasPickaxe) drawToolAt(c, cx + 2f * u + len, shoulderY + 1f * u, u, armPhase)
            else { fill.color = skin; c.drawRect(cx + 2f * u + len, shoulderY - 0.4f * u, cx + 2f * u + len + 2.6f * u, shoulderY + 2.6f * u, fill) }
            c.restore()
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
        val pad = ud(8f) + insL
        val padT = ud(8f) + insT
        // top-left info pill
        textP.textSize = ud(13f); textP.textAlign = Paint.Align.LEFT
        val title = w.name.uppercase()
        val tw = textP.measureText(title)
        val lockW = if (w.ownerId != null) ud(20f) else 0f
        rf.set(pad, padT, pad + tw + ud(20f) + lockW, padT + ud(28f))
        fill.color = 0x99000000.toInt(); c.drawRoundRect(rf, ud(8f), ud(8f), fill)
        shadowText(c, title, pad + ud(10f), padT + ud(19f), C_WHITE)
        if (w.ownerId != null) drawIcon(c, w.get(w.lockX, w.lockY).let { if (ItemDB.isWorldLock(it)) it else Id.WORLD_LOCK }, pad + ud(14f) + tw, padT + ud(6f), ud(16f))

        // gems + level
        if (gemsCache != prof.gems) { gemsCache = prof.gems; gemsStr = prof.gems.toString() }
        val lv = prof.level(); if (lvCache != lv) { lvCache = lv; lvStr = "Lv $lv" }
        val y2 = padT + ud(34f)
        val gw = textP.measureText(gemsStr) + textP.measureText(lvStr) + ud(100f)
        rf.set(pad, y2, pad + gw, y2 + ud(26f)); fill.color = 0x99000000.toInt(); c.drawRoundRect(rf, ud(8f), ud(8f), fill)
        drawIcon(c, Id.T_GEM, pad + ud(6f), y2 + ud(3f), ud(20f))
        shadowText(c, gemsStr, pad + ud(30f), y2 + ud(18f), C_BLUE)
        val lx = pad + ud(34f) + textP.measureText(gemsStr) + ud(10f)
        shadowText(c, lvStr, lx, y2 + ud(18f), C_YELLOW)
        val bx = lx + textP.measureText(lvStr) + ud(6f)
        rf.set(bx, y2 + ud(9f), bx + ud(36f), y2 + ud(17f)); fill.color = 0x66FFFFFF; c.drawRoundRect(rf, ud(4f), ud(4f), fill)
        rf.set(bx, y2 + ud(9f), bx + ud(36f) * prof.levelProgress(), y2 + ud(17f)); fill.color = 0xFFFFD866.toInt(); c.drawRoundRect(rf, ud(4f), ud(4f), fill)

        // console
        var cy = y2 + ud(34f)
        textP.textSize = ud(12f)
        for (m in cons) {
            val a = if (m.t > 7.5f) ((9f - m.t) / 1.5f).coerceIn(0f, 1f) else 1f
            val mw = min(textP.measureText(m.text), vw * 0.55f)
            rf.set(pad, cy, pad + mw + ud(12f), cy + ud(19f))
            fill.color = ((0x88 * a).toInt() shl 24); c.drawRoundRect(rf, ud(6f), ud(6f), fill)
            textP.color = (m.color and 0x00FFFFFF) or ((255 * a).toInt() shl 24)
            c.save(); c.clipRect(rf); c.drawText(m.text, pad + ud(6f), cy + ud(14f), textP); c.restore()
            cy += ud(22f)
        }

        // notification boxes (top-center, Growtopia style)
        if (notifs.isNotEmpty()) {
            val bw = min(vw * 0.55f, ud(340f)).coerceAtLeast(min(vw * 0.9f, ud(220f)))
            val bx0 = (vw - bw) / 2f
            val ny0 = insT + ud(8f)
            var ny = ny0
            for (n in notifs) {
                val iconW = if (n.icon != 0) ud(34f) else 0f
                val textW = bw - ud(24f) - iconW
                if (n.lw != textW) {
                    textP.textSize = ud(12f); textP.textAlign = Paint.Align.LEFT
                    val out = ArrayList<String>(); var cur = ""
                    for (word in n.text.split(' ')) {
                        if (word.isEmpty()) continue
                        val test = if (cur.isEmpty()) word else "$cur $word"
                        if (textP.measureText(test) > textW && cur.isNotEmpty()) { out.add(cur); cur = word } else cur = test
                    }
                    if (cur.isNotEmpty()) out.add(cur)
                    n.lines = out.take(3); n.lw = textW
                }
                val hh = max(ud(44f), ud(32f) + n.lines.size * ud(15f))
                val ease = 1f - (1f - n.slide) * (1f - n.slide)
                val a = ((n.ttl - n.t) / 0.35f).coerceIn(0f, 1f)
                val top = ny - (1f - ease) * (hh + ud(12f))
                n.y0 = top; n.y1 = top + hh
                val ai = (255 * a).toInt()
                rf.set(bx0 - ud(2f), top - ud(2f), bx0 + bw + ud(2f), top + hh + ud(2f))
                fill.color = ((ai * 0xCC / 255) shl 24) or (n.color and 0xFFFFFF); c.drawRoundRect(rf, ud(14f), ud(14f), fill)
                rf.set(bx0, top, bx0 + bw, top + hh)
                fill.color = ((ai * 0xEE / 255) shl 24) or 0x16203A; c.drawRoundRect(rf, ud(12f), ud(12f), fill)
                var tx0 = bx0 + ud(12f)
                if (n.icon != 0) { drawIcon(c, n.icon, tx0, top + (hh - ud(28f)) / 2f, ud(28f)); tx0 += iconW }
                textP.textAlign = Paint.Align.LEFT
                textP.textSize = ud(13f)
                val ty0 = if (n.lines.isNotEmpty()) top + ud(20f) else top + hh / 2f + ud(5f)
                shadowText(c, n.title, tx0, ty0, (ai shl 24) or (n.color and 0xFFFFFF))
                textP.textSize = ud(12f)
                var ly = ty0 + ud(16f)
                for (l in n.lines) { shadowText(c, l, tx0, ly, (ai shl 24) or 0xE6EAF2); ly += ud(15f) }
                ny += hh + ud(6f)
            }
            nbL = bx0; nbR = bx0 + bw; nbT = ny0; nbB = ny - ud(6f)
        }

        drawControls(c)
    }

    private fun drawControls(c: Canvas) {
        for (i in 0..3) {
            val r = when (i) { 0 -> rL; 1 -> rR; 2 -> rJ; else -> rP }
            val on = when (i) { 0 -> holdL; 1 -> holdR; 2 -> jumpHeld; else -> pbDown }
            fill.color = if (on) ((a8(0x88) shl 24) or 0xFFFFFF) else (a8(0x55) shl 24)
            if (i >= 2) c.drawCircle(r.centerX(), r.centerY(), r.width() / 2, fill) else c.drawRoundRect(r, dp(16f), dp(16f), fill)
            if (i == 3) { drawIcon(c, Id.FIST, r.centerX() - r.width() * 0.3f, r.centerY() - r.width() * 0.3f, r.width() * 0.6f); continue }
            fill.color = if (on) 0xFF222222.toInt() else 0xBBFFFFFF.toInt()
            c.drawPath(when (i) { 0 -> arrowL; 1 -> arrowR; else -> arrowJ }, fill)
        }
        val n = if (onGround) maxAir() else airJumps
        for (i in 0 until maxAir()) {
            fill.color = if (i < n) 0xFFFFD866.toInt() else 0x55FFFFFF
            c.drawCircle(rJ.centerX() + (i - (maxAir() - 1) / 2f) * dp(14f), rJ.top - dp(8f), dp(4.5f), fill)
        }
        // zoom buttons
        if (prof.zoomBtns) for (z in 0..1) {
            val r = if (z == 0) rZi else rZo
            fill.color = (a8(0x55) shl 24); c.drawRoundRect(r, dp(10f), dp(10f), fill)
            fill.color = 0xFFFFFFFF.toInt()
            val cx0 = r.centerX(); val cy0 = r.centerY(); val a = dp(9f); val t0 = dp(1.8f)
            c.drawRect(cx0 - a, cy0 - t0, cx0 + a, cy0 + t0, fill)
            if (z == 0) c.drawRect(cx0 - t0, cy0 - a, cx0 + t0, cy0 + a, fill)
        }
        // backpack tab
        fill.color = if (drawerOpen) 0xFF2F9E6B.toInt() else 0xCC3A6EA5.toInt()
        rf.set(tabR); c.drawRoundRect(rf, dp(8f), dp(8f), fill)
        fill.color = 0xFFFFFFFF.toInt()
        for (k in 0..2) c.drawRect(tabR.centerX() - dp(12f), tabR.top + dp(5f) + k * dp(4.5f), tabR.centerX() + dp(12f), tabR.top + dp(7f) + k * dp(4.5f), fill)
        // hotbar
        textP.textSize = dp(11f); textP.textAlign = Paint.Align.RIGHT
        for (i in 0 until 6) {
            val id = if (i == 0) Id.FIST else prof.quick[i - 1]
            val r = hotR[i]
            fill.color = 0xAA1B1F26.toInt(); c.drawRoundRect(r, dp(8f), dp(8f), fill)
            if (id != 0) {
                drawIcon(c, id, r.left + dp(7f), r.top + dp(7f), r.width() - dp(14f))
                if (id != Id.FIST) shadowText(c, prof.count(id).toString(), r.right - dp(4f), r.bottom - dp(4f), C_WHITE)
            }
            if (id == prof.sel && id != 0) { line.color = 0xFFFFD866.toInt(); line.strokeWidth = dp(2.5f); c.drawRoundRect(r, dp(8f), dp(8f), line) }
        }
        // docked backpack drawer
        if (drawerOpen) {
            val cell = drawerGeom(rf)
            fill.color = 0xE61B1F26.toInt(); c.drawRoundRect(rf, dp(12f), dp(12f), fill)
            val pad = dp(8f)
            for (k in 0 until min(dN, 24)) {
                val id = dIds[k]
                val l = rf.left + pad + (k % 8) * cell; val t = rf.top + pad + (k / 8) * cell
                fill.color = 0xFF323A47.toInt(); c.drawRoundRect(l + dp(2f), t + dp(2f), l + cell - dp(2f), t + cell - dp(2f), dp(8f), dp(8f), fill)
                drawIcon(c, id, l + dp(8f), t + dp(8f), cell - dp(16f))
                if (id != Id.FIST && prof.count(id) > 1) shadowText(c, prof.count(id).toString(), l + cell - dp(5f), t + cell - dp(5f), C_WHITE)
                val eq = prof.isEquipped(id)
                if (eq || id == prof.sel) { line.color = if (eq) 0xFFE8C23A.toInt() else 0xFF2F9E6B.toInt(); line.strokeWidth = dp(2f); c.drawRoundRect(l + dp(2f), t + dp(2f), l + cell - dp(2f), t + cell - dp(2f), dp(8f), dp(8f), line) }
            }
        }
        textP.textAlign = Paint.Align.LEFT
    }
}



