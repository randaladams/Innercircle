package com.example.innercircle

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

class GameView(context: Context) : View(context) {

    // ================= TUNING =================
    // All sizes are in dp so the game feels the same on every phone.
    private val innerStartRadiusDp = 22f      // starting size of the filled circle
    private val outerStartFraction = 0.42f    // outer radius as a fraction of the screen's short side
    private val growthPerDistance = 0.04f     // inner radius gained per unit of distance dragged
    private val baseShrinkDpPerSec = 6f       // ring shrink speed while touching, first game
    private val shrinkIncreasePerGame = 0.4f  // extra shrink speed for each game played
    private val maxShrinkDpPerSec = 16f       // shrink speed cap (while touching)
    private val releasedSpeedMultiplier = 3f  // ring shrinks this many times faster while no finger is down
    private val driftStartsAtGame = 5         // ring starts moving around after this many games
    private val maxDriftDp = 40f
    private val wobbleStartsAtGame = 10       // ring starts wobbling after this many games
    private val maxWobbleDp = 10f

    // Scoring: points are earned for every dp the ring closes in while you survive,
    // so a faster ring doesn't lower your ceiling.
    private val pointsPerDp = 10f             // base points per dp the ring travels
    private val enduranceBonusMax = 2f        // the last stretch is worth up to (1 + this)x
    private val driftBonus = 0.25f            // +25% score once the ring drifts
    private val wobbleBonus = 0.25f           // another +25% once it also wobbles
    private val penaltyPerDpMoved = 2f        // points lost per dp the inner circle travels
    private val handsOffPointsMultiplier = 2f // points are worth this much more while no finger is down

    // Sound
    private val humLowHz = 70f                // hum pitch when the ring is full size
    private val humHighHz = 150f              // hum pitch when the ring has fully closed
    private val humHandsOffBoost = 1.2f       // hum rises this much while the ring is speeding
    private val humVolume = 0.35f
    // ==========================================

    // ================= COLORS =================
    private val bgCenter = Color.rgb(28, 36, 72)
    private val bgEdge = Color.rgb(8, 10, 22)
    private val safeColor = Color.rgb(80, 220, 255)     // ring when you have room
    private val warnColor = Color.rgb(255, 200, 60)     // ring when it's getting close
    private val dangerColor = Color.rgb(255, 70, 100)   // ring when you're about to touch
    private val innerSmall = Color.rgb(110, 255, 170)   // inner circle at starting size
    private val innerBig = Color.rgb(200, 110, 255)     // inner circle once it's grown a lot
    private val accent = Color.rgb(80, 220, 255)
    private val gold = Color.rgb(255, 210, 80)
    private val muted = Color.rgb(160, 172, 200)
    // ==========================================

    private enum class State { READY, PLAYING, OVER }

    private val prefs = context.getSharedPreferences("inner_circle", Context.MODE_PRIVATE)
    private val sound = SoundEngine()
    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private var state = State.READY
    private var gamesPlayed = prefs.getInt("games", 0)
    private var best = prefs.getInt("best", 0)
    private var lastScore = 0
    private var newBest = false

    // Outer circle
    private var baseCx = 0f
    private var baseCy = 0f
    private var outerCx = 0f
    private var outerCy = 0f
    private var outerR = 0f
    private var startOuterR = 0f
    private var shrinkSpeed = 0f
    private var driftAmp = 0f
    private var wobbleAmp = 0f
    private var difficultyMult = 1f

    // Inner circle
    private var ix = 0f
    private var iy = 0f
    private var ir = 0f

    // Touch / scoring
    // Control is relative: touch anywhere and the circle moves by however far your
    // finger moves, so your finger never has to cover the circle.
    private var fingerDown = false
    private var activePointerId = -1
    private var lastX = 0f
    private var lastY = 0f
    private var movedDp = 0f
    private var elapsed = 0f
    private var points = 0f
    private var lastFrameNs = 0L
    private var overAtMs = 0L

    // ---- Paints ----
    private val bgPaint = Paint()
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val ringGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val innerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val innerGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val fingerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val overlayPaint = Paint().apply { color = Color.argb(185, 6, 8, 18) }
    private val bigText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val midText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val smallText = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val outerPath = Path()

    // DEBUG: reset button — remove before release
    private val resetRect = RectF()
    private val resetFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(220, 150, 30, 50) }
    private val resetText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    init {
        ringPaint.strokeWidth = dp(3f)
        ringGlow.strokeWidth = dp(14f)
        fingerPaint.strokeWidth = dp(2f)
        bigText.textSize = dp(40f)
        midText.textSize = dp(24f)
        smallText.textSize = dp(16f)
        resetText.textSize = dp(13f)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        sound.start()
    }

    override fun onDetachedFromWindow() {
        sound.stop()
        super.onDetachedFromWindow()
    }

    fun resumeAudio() = sound.start()
    fun pauseAudio() = sound.stop()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        bgPaint.shader = RadialGradient(
            w / 2f, h / 2f, max(w, h) * 0.75f,
            bgCenter, bgEdge, Shader.TileMode.CLAMP
        )
        resetRect.set(w - dp(96f), dp(14f), w - dp(12f), dp(50f)) // DEBUG
        newRound()
    }

    private fun newRound() {
        baseCx = width / 2f
        baseCy = height / 2f
        outerCx = baseCx
        outerCy = baseCy
        outerR = min(width, height) * outerStartFraction
        startOuterR = outerR
        ir = dp(innerStartRadiusDp)

        // Difficulty grows with the number of games played.
        val g = gamesPlayed
        shrinkSpeed = dp(min(maxShrinkDpPerSec, baseShrinkDpPerSec + shrinkIncreasePerGame * g))
        driftAmp = if (g >= driftStartsAtGame) dp(min(maxDriftDp, (g - driftStartsAtGame + 1) * 4f)) else 0f
        wobbleAmp = if (g >= wobbleStartsAtGame) dp(min(maxWobbleDp, (g - wobbleStartsAtGame + 1) * 1f)) else 0f
        difficultyMult = 1f +
            (if (driftAmp > 0f) driftBonus else 0f) +
            (if (wobbleAmp > 0f) wobbleBonus else 0f)

        // Random spawn, never right in the middle.
        val maxDist = outerR - ir - dp(12f)
        val angle = Random.nextFloat() * 2f * PI.toFloat()
        val dist = maxDist * (0.35f + 0.65f * sqrt(Random.nextFloat()))
        ix = baseCx + cos(angle) * dist
        iy = baseCy + sin(angle) * dist

        movedDp = 0f
        elapsed = 0f
        points = 0f
        newBest = false
        fingerDown = false
        state = State.READY
        sound.humVolume = 0f
        sound.humFreq = humLowHz
        sound.danger = 0f
        sound.wobble = 0f
        invalidate()
    }

    private fun startGame() {
        state = State.PLAYING
        lastFrameNs = 0L
        sound.click()
        sound.humVolume = humVolume
        invalidate()
    }

    private fun currentScore(): Int =
        max(0, (points * difficultyMult - movedDp * penaltyPerDpMoved).toInt())

    private fun gameOver() {
        state = State.OVER
        fingerDown = false
        sound.humVolume = 0f
        sound.crash()
        lastScore = currentScore()
        gamesPlayed++
        if (lastScore > best) {
            best = lastScore
            newBest = true
        }
        prefs.edit().putInt("games", gamesPlayed).putInt("best", best).apply()
        overAtMs = System.currentTimeMillis()
    }

    // DEBUG: wipes saved games and best score — remove before release
    private fun resetAll() {
        prefs.edit().clear().apply()
        gamesPlayed = 0
        best = 0
        lastScore = 0
        newRound()
    }

    private fun update(dt: Float) {
        elapsed += dt
        // Ring closes slowly while you hold the screen, fast while you don't.
        val speed = if (fingerDown) shrinkSpeed else shrinkSpeed * releasedSpeedMultiplier
        val shrink = speed * dt
        // Endurance bonus: the further the ring has closed, the more each dp is worth.
        val progress = ((startOuterR - outerR) / startOuterR).coerceIn(0f, 1f)
        // Hands off is riskier, so it pays more: every dp is worth double while the ring rushes in.
        val handsOff = if (fingerDown) 1f else handsOffPointsMultiplier
        points += (shrink / density) * pointsPerDp * (1f + enduranceBonusMax * progress) * handsOff
        outerR -= shrink

        if (driftAmp > 0f) {
            outerCx = baseCx + driftAmp * sin(elapsed * 0.6f)
            outerCy = baseCy + driftAmp * sin(elapsed * 0.43f)
        }

        // Hum follows the ring: higher as it shrinks, wavering with its wobble,
        // pulsing faster as the gap closes, and rising while the ring is speeding.
        sound.humFreq = (humLowHz + (humHighHz - humLowHz) * progress) *
            (if (fingerDown) 1f else humHandsOffBoost)
        sound.wobble = if (wobbleAmp > 0f) (wobbleAmp / dp(maxWobbleDp)).coerceIn(0f, 1f) else 0f
        sound.danger = 1f - gapFraction()

        if (collides()) gameOver()
    }

    /** 1 when there's plenty of room between the circles, 0 when they're touching. */
    private fun gapFraction(): Float {
        val gap = outerR - wobbleAmp - (hypot(ix - outerCx, iy - outerCy) + ir)
        return (gap / dp(70f)).coerceIn(0f, 1f)
    }

    private fun outerRadiusAt(theta: Float): Float =
        outerR + wobbleAmp * sin(3f * theta + elapsed * 2.2f)

    /** Checks points around the inner circle's edge against the (possibly wobbly) outer ring. */
    private fun collides(): Boolean {
        if (outerR <= ir) return true
        val samples = 24
        for (k in 0 until samples) {
            val a = (2.0 * PI * k / samples).toFloat()
            val px = ix + ir * cos(a)
            val py = iy + ir * sin(a)
            val dx = px - outerCx
            val dy = py - outerCy
            if (hypot(dx, dy) >= outerRadiusAt(atan2(dy, dx))) return true
        }
        return false
    }

    private fun lerpColor(a: Int, b: Int, t: Float): Int {
        val u = t.coerceIn(0f, 1f)
        return Color.rgb(
            (Color.red(a) + (Color.red(b) - Color.red(a)) * u).toInt(),
            (Color.green(a) + (Color.green(b) - Color.green(a)) * u).toInt(),
            (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * u).toInt()
        )
    }

    private fun withAlpha(c: Int, a: Int) = Color.argb(a, Color.red(c), Color.green(c), Color.blue(c))

    /** Ring color slides from cyan to yellow to red as the gap closes. */
    private fun ringColor(): Int {
        if (state == State.OVER) return dangerColor
        val t = gapFraction()
        return if (t > 0.5f) lerpColor(warnColor, safeColor, (t - 0.5f) * 2f)
        else lerpColor(dangerColor, warnColor, t * 2f)
    }

    private fun drawRing(canvas: Canvas, paint: Paint) {
        if (wobbleAmp == 0f) {
            canvas.drawCircle(outerCx, outerCy, max(0f, outerR), paint)
        } else {
            canvas.drawPath(outerPath, paint)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (state == State.PLAYING) {
            val now = System.nanoTime()
            val dt = if (lastFrameNs == 0L) 0f else min(0.05f, (now - lastFrameNs) / 1e9f)
            lastFrameNs = now
            update(dt)
        }

        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        // Outer ring with a soft glow
        if (wobbleAmp > 0f) {
            outerPath.reset()
            val steps = 120
            for (i in 0..steps) {
                val th = (2.0 * PI * i / steps).toFloat()
                val r = max(0f, outerRadiusAt(th))
                val x = outerCx + r * cos(th)
                val y = outerCy + r * sin(th)
                if (i == 0) outerPath.moveTo(x, y) else outerPath.lineTo(x, y)
            }
            outerPath.close()
        }
        val rc = ringColor()
        ringGlow.color = withAlpha(rc, 55)
        ringPaint.color = rc
        drawRing(canvas, ringGlow)
        drawRing(canvas, ringPaint)

        // Inner circle: color shifts from mint to purple as it grows
        val growth = (ir / dp(innerStartRadiusDp) - 1f) / 2f   // 0 at start, 1 at 3x size
        val ic = lerpColor(innerSmall, innerBig, growth)
        innerGlow.color = withAlpha(ic, 60)
        canvas.drawCircle(ix, iy, ir + dp(8f), innerGlow)
        innerPaint.shader = RadialGradient(
            ix - ir * 0.3f, iy - ir * 0.3f, ir * 1.3f,
            lerpColor(ic, Color.WHITE, 0.55f), ic, Shader.TileMode.CLAMP
        )
        canvas.drawCircle(ix, iy, ir, innerPaint)

        // Small marker where the finger is, so you can see you're in control
        if (state == State.PLAYING && fingerDown) {
            fingerPaint.color = withAlpha(ic, 110)
            canvas.drawCircle(lastX, lastY, dp(20f), fingerPaint)
        }

        // HUD
        val level = if (state == State.OVER) gamesPlayed else gamesPlayed + 1
        val cx = width / 2f
        val top = dp(80f)
        bigText.color = Color.WHITE
        canvas.drawText(String.format("%.2f", elapsed), cx, top, bigText)
        midText.color = if (state == State.PLAYING && !fingerDown) gold else accent
        canvas.drawText("${currentScore()}", cx, top + dp(34f), midText)
        smallText.color = muted
        val mult = if (difficultyMult > 1f) "   ·   ×" + String.format("%.2f", difficultyMult) else ""
        canvas.drawText("Level $level   ·   Moved ${movedDp.toInt()}$mult", cx, top + dp(60f), smallText)

        when (state) {
            State.READY -> drawReady(canvas)
            State.OVER -> drawGameOver(canvas)
            State.PLAYING -> {
                if (!fingerDown) {
                    smallText.color = gold
                    val pm = String.format("%.0f", handsOffPointsMultiplier)
                    canvas.drawText("HANDS OFF: points ×$pm   ·   touch to slow", cx, height - dp(60f), smallText)
                }
                postInvalidateOnAnimation()
            }
        }

        if (state != State.PLAYING) drawResetButton(canvas) // DEBUG
    }

    private fun drawReady(canvas: Canvas) {
        val cx = width / 2f
        val y = height - dp(60f)
        smallText.color = muted
        canvas.drawText("Touch anywhere and drag to move the circle", cx, y, smallText)
        val note = when (gamesPlayed) {
            driftStartsAtGame -> "New: the ring drifts!  Score ×${String.format("%.2f", difficultyMult)}"
            wobbleStartsAtGame -> "New: the ring wobbles!  Score ×${String.format("%.2f", difficultyMult)}"
            else -> null
        }
        if (note != null) {
            smallText.color = warnColor
            canvas.drawText(note, cx, y - dp(28f), smallText)
        }
    }

    private fun drawGameOver(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), overlayPaint)
        val cx = width / 2f
        var y = height / 2f - dp(90f)
        bigText.color = dangerColor
        canvas.drawText("Game over", cx, y, bigText)
        y += dp(46f)
        smallText.color = muted
        canvas.drawText(String.format("Time %.2f s   ·   Moved %d", elapsed, movedDp.toInt()), cx, y, smallText)
        y += dp(56f)
        bigText.color = if (newBest) gold else accent
        canvas.drawText("$lastScore", cx, y, bigText)
        y += dp(30f)
        if (newBest) {
            midText.color = gold
            canvas.drawText("NEW BEST!", cx, y, midText)
        } else {
            smallText.color = muted
            canvas.drawText("Best $best", cx, y, smallText)
        }
        y += dp(70f)
        smallText.color = Color.WHITE
        canvas.drawText("Tap to play again", cx, y, smallText)
    }

    // DEBUG: remove before release
    private fun drawResetButton(canvas: Canvas) {
        canvas.drawRoundRect(resetRect, dp(8f), dp(8f), resetFill)
        val ty = resetRect.centerY() - (resetText.descent() + resetText.ascent()) / 2f
        canvas.drawText("RESET", resetRect.centerX(), ty, resetText)
    }

    /** Start following the given pointer from where it is now. */
    private fun track(e: MotionEvent, index: Int) {
        activePointerId = e.getPointerId(index)
        lastX = e.getX(index)
        lastY = e.getY(index)
        fingerDown = true
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // DEBUG: reset button, only when not mid-game
                if (state != State.PLAYING && resetRect.contains(e.x, e.y)) {
                    resetAll()
                    return true
                }
                when (state) {
                    State.READY -> {
                        startGame()
                        track(e, 0)
                    }
                    State.PLAYING -> track(e, 0)
                    State.OVER -> if (System.currentTimeMillis() - overAtMs > 700) newRound()
                }
            }
            MotionEvent.ACTION_MOVE -> if (fingerDown && state == State.PLAYING) {
                val idx = e.findPointerIndex(activePointerId)
                if (idx >= 0) {
                    val x = e.getX(idx)
                    val y = e.getY(idx)
                    val dx = x - lastX
                    val dy = y - lastY
                    lastX = x
                    lastY = y
                    val d = hypot(dx, dy)
                    ix += dx
                    iy += dy
                    ir += d * growthPerDistance
                    movedDp += d / density
                }
            }
            // If the finger we're following lifts while another is still down, switch to that one.
            MotionEvent.ACTION_POINTER_UP -> {
                val upIdx = e.actionIndex
                if (e.getPointerId(upIdx) == activePointerId) {
                    track(e, if (upIdx == 0) 1 else 0)
                }
            }
            // Lifting your last finger lets the ring speed up.
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> fingerDown = false
        }
        return true
    }
}
