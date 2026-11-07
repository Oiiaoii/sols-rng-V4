package com.solmacro

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

private typealias WL = WindowManager.LayoutParams

private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT

class MacroService : AccessibilityService() {

    companion object { var inst: MacroService? = null }

    private class Step(val x: Int, val y: Int, val hold: Long, val wait: Long)

    private val h = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager
    private var panel: LinearLayout? = null
    private var startBtn: TextView? = null
    private var running = false
    private var catches = 0
    private var tl: List<Step> = emptyList()
    private var idx = 0
    private var lastPkg = ""

    // detection state
    private var detectMode = false
    private var phase = 0
    private var miss = 0
    private var sellIdx = 0
    private var t0 = 0L
    private var lastShot = 0L
    private var lastMark = -1.0
    private var lastHeld = false
    private var votes = 0
    private val bg = java.util.concurrent.Executors.newSingleThreadExecutor()

    override fun onServiceConnected() {
        inst = this
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        showPanel()
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        if (e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val p = e.packageName?.toString() ?: return
            if (p != packageName && p != "com.android.systemui") lastPkg = p
        }
    }

    override fun onInterrupt() { stop() }

    override fun onUnbind(intent: Intent?): Boolean {
        stop()
        panel?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        panel = null
        inst = null
        return super.onUnbind(intent)
    }

    private fun foreground(): String =
        if (lastPkg.isNotEmpty()) lastPkg else (rootInActiveWindow?.packageName?.toString() ?: "")

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()

    // ---------- timed macro loop ----------
    private fun build(): List<Step> {
        val c = applicationContext
        val l = ArrayList<Step>()
        l.add(Step(Cfg.get(c, "cast_x"), Cfg.get(c, "cast_y"), Cfg.get(c, "cast").toLong(), Cfg.get(c, "wait").toLong()))
        repeat(Cfg.get(c, "taps")) {
            l.add(Step(Cfg.get(c, "reel_x"), Cfg.get(c, "reel_y"), 40L, Cfg.get(c, "gap").toLong()))
        }
        val every = Cfg.get(c, "every")
        if (Cfg.get(c, "sell") == 1 && every > 0 && (catches + 1) % every == 0) {
            for (k in listOf("inv", "sellall", "confirm")) {
                l.add(Step(Cfg.get(c, k + "_x"), Cfg.get(c, k + "_y"), 50L, Cfg.get(c, "mdelay").toLong()))
            }
        }
        return l
    }

    fun toggle() { if (running) stop() else start() }

    private fun start() {
        if (foreground() != Cfg.ROBLOX) { toast("Open Roblox first"); return }
        detectMode = Cfg.get(applicationContext, "detect") == 1
        if (detectMode && Build.VERSION.SDK_INT < 30) { toast("Detection needs Android 11 or newer"); return }
        running = true
        idx = 0
        phase = 0
        miss = 0
        label()
        if (detectMode) {
            dnext()
        } else {
            tl = build()
            next()
        }
    }

    fun stop() {
        running = false
        h.removeCallbacksAndMessages(null)
        label()
    }

    private fun next() {
        if (!running) return
        if (foreground() != Cfg.ROBLOX) { stop(); toast("Roblox closed, macro stopped"); return }
        if (idx >= tl.size) { catches++; label(); if (doneCheck()) return; tl = build(); idx = 0 }
        val s = tl[idx++]
        tap(s.x, s.y, s.hold)
        h.postDelayed({ next() }, s.hold + s.wait)
    }

    private fun tap(x: Int, y: Int, hold: Long) {
        val p = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0L, maxOf(hold, 1L)))
            .build()
        dispatchGesture(g, null, null)
    }

    private fun label() {
        startBtn?.text = if (running) "STOP ($catches)" else "START"
    }

    // ---------- screen detection loop ----------
    private fun dnext() {
        if (!running) return
        if (foreground() != Cfg.ROBLOX) { stop(); toast("Roblox closed, macro stopped"); return }
        val c = applicationContext
        when (phase) {
            0 -> {
                tap(Cfg.get(c, "cast_x"), Cfg.get(c, "cast_y"), Cfg.get(c, "cast").toLong())
                phase = 1
                t0 = SystemClock.uptimeMillis()
                h.postDelayed({ dnext() }, Cfg.get(c, "cast") + 600L)
            }
            1 -> detect { n ->
                if (!running) return@detect
                if (n >= Cfg.get(c, "minpx")) {
                    phase = 2
                    miss = 0
                    lastMark = -1.0
                    lastHeld = false
                    votes = 0
                    t0 = SystemClock.uptimeMillis()
                } else if (SystemClock.uptimeMillis() - t0 > Cfg.get(c, "maxwait") * 1000L) {
                    phase = 0
                }
                h.postDelayed({ dnext() }, 50L)
            }
            2 -> track { r ->
                if (!running) return@track
                var held = false
                if (r != null && r[0].toInt() >= Cfg.get(c, "minpx")) {
                    miss = 0
                    if (r[3].toInt() >= 3) {
                        val zc = (r[1] + r[2]) / 2.0
                        val mc = r[4] / r[3]
                        var dir = Cfg.get(c, "holddir")
                        if (lastHeld && lastMark >= 0) {
                            val d = mc - lastMark
                            if (Math.abs(d) > 6) {
                                if ((d > 0) == (dir == 1)) votes++ else votes--
                                if (votes <= -3) {
                                    dir = 1 - dir
                                    Cfg.put(c, "holddir", dir)
                                    votes = 0
                                    toast("Hold direction flipped")
                                }
                            }
                        }
                        val db = Cfg.get(c, "deadband")
                        held = if (dir == 0) mc > zc + db else mc < zc - db
                        if (held) tap(Cfg.get(c, "reel_x"), Cfg.get(c, "reel_y"), Cfg.get(c, "holdms").toLong())
                        lastMark = mc
                    }
                } else {
                    miss++
                }
                lastHeld = held
                if (miss >= 3 || SystemClock.uptimeMillis() - t0 > 60000L) {
                    catches++
                    label()
                    if (doneCheck()) return@track
                    val every = Cfg.get(c, "every")
                    phase = if (Cfg.get(c, "sell") == 1 && every > 0 && catches % every == 0) 3 else 0
                    sellIdx = 0
                    h.postDelayed({ dnext() }, 800L)
                } else {
                    h.postDelayed({ dnext() }, 20L)
                }
            }
            else -> {
                val k = listOf("inv", "sellall", "confirm")[sellIdx]
                sellIdx++
                tap(Cfg.get(c, k + "_x"), Cfg.get(c, k + "_y"), 50L)
                if (sellIdx >= 3) phase = 0
                h.postDelayed({ dnext() }, Cfg.get(c, "mdelay").toLong())
            }
        }
    }

    private fun shot(cb: (Bitmap?) -> Unit) {
        if (Build.VERSION.SDK_INT < 30) { cb(null); return }
        val wait = maxOf(0L, 380L - (SystemClock.uptimeMillis() - lastShot))
        h.postDelayed({
            lastShot = SystemClock.uptimeMillis()
            takeScreenshot(Display.DEFAULT_DISPLAY, bg, object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(r: AccessibilityService.ScreenshotResult) {
                    val hb = r.hardwareBuffer
                    val hw = Bitmap.wrapHardwareBuffer(hb, r.colorSpace)
                    val bmp = hw?.copy(Bitmap.Config.ARGB_8888, false)
                    hw?.recycle()
                    hb.close()
                    cb(bmp)
                }
                override fun onFailure(errorCode: Int) { cb(null) }
            })
        }, wait)
    }

    private fun track(cb: (DoubleArray?) -> Unit) {
        shot { b ->
            val r = if (b == null) null else scan(b).also { b.recycle() }
            h.post { cb(r) }
        }
    }

    private fun near(p: Int, t: Int, tol: Int): Boolean =
        Math.abs(((p shr 16) and 255) - ((t shr 16) and 255)) <= tol &&
        Math.abs(((p shr 8) and 255) - ((t shr 8) and 255)) <= tol &&
        Math.abs((p and 255) - (t and 255)) <= tol

    // returns [zoneCount, zoneMin, zoneMax, markerCount, markerSum] along the chosen axis
    private fun scan(b: Bitmap): DoubleArray {
        val c = applicationContext
        val out = doubleArrayOf(0.0, 1e9, -1.0, 0.0, 0.0)
        val x1 = minOf(Cfg.get(c, "r1_x"), Cfg.get(c, "r2_x")).coerceIn(0, b.width - 1)
        val x2 = maxOf(Cfg.get(c, "r1_x"), Cfg.get(c, "r2_x")).coerceIn(0, b.width)
        val y1 = minOf(Cfg.get(c, "r1_y"), Cfg.get(c, "r2_y")).coerceIn(0, b.height - 1)
        val y2 = maxOf(Cfg.get(c, "r1_y"), Cfg.get(c, "r2_y")).coerceIn(0, b.height)
        val w = x2 - x1
        val hh = y2 - y1
        if (w <= 0 || hh <= 0) return out
        val px = IntArray(w * hh)
        b.getPixels(px, 0, w, x1, y1, w, hh)
        val zc = Cfg.get(c, "color")
        val mk = Cfg.get(c, "color2")
        val tol = Cfg.get(c, "tol")
        val vert = Cfg.get(c, "axis") == 1
        var i = 0
        while (i < px.size) {
            val p = px[i]
            val pos = (if (vert) y1 + i / w else x1 + i % w).toDouble()
            if (near(p, zc, tol)) {
                out[0] += 1.0
                if (pos < out[1]) out[1] = pos
                if (pos > out[2]) out[2] = pos
            } else if (near(p, mk, tol)) {
                out[3] += 1.0
                out[4] += pos
            }
            i += 2
        }
        return out
    }

    private fun shotMain(cb: (Bitmap?) -> Unit) = shot { b -> h.post { cb(b) } }

    private fun detect(cb: (Int) -> Unit) {
        shot { b ->
            val n = if (b == null) 0 else count(b).also { b.recycle() }
            h.post { cb(n) }
        }
    }

    private fun doneCheck(): Boolean {
        val m = Cfg.get(applicationContext, "maxcatch")
        if (m > 0 && catches >= m) { stop(); toast("Reached $m catches, stopped"); return true }
        return false
    }

    private fun count(b: Bitmap): Int {
        val c = applicationContext
        val x1 = minOf(Cfg.get(c, "r1_x"), Cfg.get(c, "r2_x")).coerceIn(0, b.width - 1)
        val x2 = maxOf(Cfg.get(c, "r1_x"), Cfg.get(c, "r2_x")).coerceIn(0, b.width)
        val y1 = minOf(Cfg.get(c, "r1_y"), Cfg.get(c, "r2_y")).coerceIn(0, b.height - 1)
        val y2 = maxOf(Cfg.get(c, "r1_y"), Cfg.get(c, "r2_y")).coerceIn(0, b.height)
        val w = x2 - x1
        val hh = y2 - y1
        if (w <= 0 || hh <= 0) return 0
        val px = IntArray(w * hh)
        b.getPixels(px, 0, w, x1, y1, w, hh)
        val tc = Cfg.get(c, "color")
        val tr = (tc shr 16) and 255
        val tg = (tc shr 8) and 255
        val tb = tc and 255
        val tol = Cfg.get(c, "tol")
        var n = 0
        var i = 0
        while (i < px.size) {
            val p = px[i]
            if (Math.abs(((p shr 16) and 255) - tr) <= tol &&
                Math.abs(((p shr 8) and 255) - tg) <= tol &&
                Math.abs((p and 255) - tb) <= tol) n++
            i += 3
        }
        return n
    }

    private fun test() {
        if (Build.VERSION.SDK_INT < 30) { toast("Detection needs Android 11 or newer"); return }
        track { r -> toast(if (r == null) "Could not capture the screen" else "Zone px: ${r[0].toInt()}, marker px: ${r[3].toInt()} (zone needs ${Cfg.get(applicationContext, "minpx")})") }
    }

    // ---------- calibration on a frozen screenshot ----------
    private fun calibrate() {
        if (Build.VERSION.SDK_INT < 30) { toast("Detection needs Android 11 or newer"); return }
        toast("Start fishing now. The screen is captured in 8 seconds, so have the reeling bar showing.")
        h.postDelayed({ calibrateNow() }, 8000L)
    }

    private fun calibrateNow() {
        if (Build.VERSION.SDK_INT < 30) { toast("Detection needs Android 11 or newer"); return }
        shotMain { bmp ->
            if (bmp == null) { toast("Could not capture the screen"); return@shotMain }
            val c = applicationContext
            val box = FrameLayout(this)
            val img = ImageView(this).apply {
                setImageBitmap(bmp)
                scaleType = ImageView.ScaleType.FIT_XY
            }
            box.addView(img, FrameLayout.LayoutParams(MATCH, MATCH))
            val prompts = listOf(
                "1/4: tap the TOP-LEFT corner of the reeling bar area",
                "2/4: tap the BOTTOM-RIGHT corner of the bar area",
                "3/4: tap the GREEN zone (its color is saved)",
                "4/4: tap the white MARKER (its color is saved)"
            )
            val t = pill(prompts[0], 0xDD000000.toInt())
            box.addView(t, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = 60 })
            var step = 0
            box.setOnTouchListener { v, e ->
                if (e.action == MotionEvent.ACTION_UP) {
                    val x = (e.x / v.width * bmp.width).toInt().coerceIn(0, bmp.width - 1)
                    val y = (e.y / v.height * bmp.height).toInt().coerceIn(0, bmp.height - 1)
                    when (step) {
                        0 -> { Cfg.put(c, "r1_x", x); Cfg.put(c, "r1_y", y) }
                        1 -> { Cfg.put(c, "r2_x", x); Cfg.put(c, "r2_y", y) }
                        2 -> Cfg.put(c, "color", bmp.getPixel(x, y))
                        else -> Cfg.put(c, "color2", bmp.getPixel(x, y))
                    }
                    step++
                    if (step >= 4) {
                        wm.removeView(box)
                        toast("Saved. Tap TEST while the bar is showing.")
                    } else {
                        t.text = prompts[step]
                    }
                }
                true
            }
            wm.addView(box, WL(MATCH, MATCH, WL.TYPE_ACCESSIBILITY_OVERLAY,
                WL.FLAG_LAYOUT_IN_SCREEN or WL.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT))
        }
    }

    // ---------- floating panel ----------
    private fun pill(t: String, color: Int) = TextView(this).apply {
        text = t
        setTextColor(Color.WHITE)
        textSize = 13f
        setPadding(34, 22, 34, 22)
        background = GradientDrawable().apply { setColor(color); cornerRadius = 60f }
    }

    private fun showPanel() {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val s = pill("START", 0xDD2E8BFF.toInt())
        val p = pill("SET", 0xDD6B4DFF.toInt())
        val cl = pill("COLOR", 0xDD1E9E75.toInt())
        val ts = pill("TEST", 0xDD8A6D00.toInt())
        row.addView(s)
        for (v in listOf(p, cl, ts)) {
            row.addView(v, LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = 12 })
        }
        val lp = WL(WRAP, WRAP, WL.TYPE_ACCESSIBILITY_OVERLAY,
            WL.FLAG_NOT_FOCUSABLE or WL.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 24
            y = 120
        }
        drag(s, row, lp) { toggle() }
        drag(p, row, lp) { if (!running) setPoints() else toast("Stop the macro first") }
        drag(cl, row, lp) { if (!running) calibrate() else toast("Stop the macro first") }
        drag(ts, row, lp) { test() }
        wm.addView(row, lp)
        panel = row
        startBtn = s
    }

    private fun drag(v: View, row: View, lp: WL, click: () -> Unit) {
        var dx = 0f
        var dy = 0f
        var sx = 0
        var sy = 0
        var moved = false
        v.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { dx = e.rawX; dy = e.rawY; sx = lp.x; sy = lp.y; moved = false }
                MotionEvent.ACTION_MOVE -> {
                    val mx = (e.rawX - dx).toInt()
                    val my = (e.rawY - dy).toInt()
                    if (Math.abs(mx) > 12 || Math.abs(my) > 12) moved = true
                    if (moved) { lp.x = sx + mx; lp.y = sy + my; wm.updateViewLayout(row, lp) }
                }
                MotionEvent.ACTION_UP -> if (!moved) click()
            }
            true
        }
    }

    // ---------- tap position picker ----------
    private fun setPoints() = pickAt(0)

    private fun pickAt(i: Int) {
        if (i >= Cfg.keys.size) { toast("Positions saved"); return }
        val k = Cfg.keys[i]
        val box = FrameLayout(this).apply { setBackgroundColor(0x99000000.toInt()) }
        val t = TextView(this).apply {
            text = "${i + 1}/${Cfg.keys.size}: tap the ${Cfg.label[k]}"
            setTextColor(Color.WHITE)
            textSize = 20f
            gravity = Gravity.CENTER
        }
        box.addView(t, FrameLayout.LayoutParams(MATCH, MATCH))
        box.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_UP) {
                Cfg.put(applicationContext, k + "_x", e.rawX.toInt())
                Cfg.put(applicationContext, k + "_y", e.rawY.toInt())
                wm.removeView(box)
                pickAt(i + 1)
            }
            true
        }
        wm.addView(box, WL(MATCH, MATCH, WL.TYPE_ACCESSIBILITY_OVERLAY,
            WL.FLAG_LAYOUT_IN_SCREEN or WL.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT))
    }
}
