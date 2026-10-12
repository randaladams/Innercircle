package com.example.innercircle

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
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
    private val baseShrinkDpPerSec = 6f       // outer circle shrink speed in the first game
    private val shrinkIncreasePerGame = 0.4f  // extra shrink speed for each game played
    private val maxShrinkDpPerSec = 16f       // shrink speed cap
    private val driftStartsAtGame = 5         // outer circle starts moving around after this many games
    private val maxDriftDp = 40f
    private val wobbleStartsAtGame = 10       // outer circle starts wobbling after this many games
    private val maxWobbleDp = 10f
    private val pointsPerSecond = 100         // score: 100 points per second survived...
    private val penaltyPerMove = 25           // ...minus 25 for every move after the first
    // ==========================================

    private enum class State { READY, PLAYING, OVER }

    private val prefs = context.getSharedPreferences("inner_circle", Context.MODE_PRIVATE)
    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private var state = State.READY
    private var gamesPlayed = prefs.getInt("games", 0)
    private var best = prefs.getInt("best", 0)
    private var lastScore = 0

    // Outer circle
    private var baseCx = 0f
    private var baseCy = 0f
    private var outerCx = 0f
    private var outerCy = 0f
    private var outerR = 0f
    private var shrinkSpeed = 0f
    private var driftAmp = 0f
    private var wobbleAmp = 0f

    // Inner circle
    private var ix = 0f
    private var iy = 0f
    private var ir = 0f

    // Touch / scoring
    private var dragging = false
    private var grabDx = 0f
    private var grabDy = 0f
    private var grabDistance = 0f
    private var grabCounted = false
    private var moves = 0
    private var elapsed = 0f
    private var lastFrameNs = 0L
    private var overAtMs = 0L

    private val outerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
    }
    private val innerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.rgb(61, 220, 151)
    }
    private val bigText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val smallText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(170, 180, 190)
        textAlign = Paint.Align.CENTER
    }
    private val outerPath = Path()

    init {
        outerPaint.strokeWidth = dp(3f)
        bigText.textSize = dp(40f)
        smallText.textSize = dp(18f)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        newRound()
    }

    private fun newRound() {
        baseCx = width / 2f
        baseCy = height / 2f
        outerCx = baseCx
        outerCy = baseCy
        outerR = min(width, height) * outerStartFraction
        ir = dp(innerStartRadiusDp)

        // Difficulty grows with the number of games played.
        val g = gamesPlayed
        shrinkSpeed = dp(min(maxShrinkDpPerSec, baseShrinkDpPerSec + shrinkIncreasePerGame * g))
        driftAmp = if (g >= driftStartsAtGame) dp(min(maxDriftDp, (g - driftStartsAtGame + 1) * 4f)) else 0f
        wobbleAmp = if (g >= wobbleStartsAtGame) dp(min(maxWobbleDp, (g - wobbleStartsAtGame + 1) * 1f)) else 0f

        // Random spawn, never right in the middle.
        val maxDist = outerR - ir - dp(12f)
        val angle = Random.nextFloat() * 2f * PI.toFloat()
        val dist = maxDist * (0.35f + 0.65f * sqrt(Random.nextFloat()))
        ix = baseCx + cos(angle) * dist
        iy = baseCy + sin(angle) * dist

        moves = 0
        elapsed = 0f
        dragging = false
        state = State.READY
        invalidate()
    }

    private fun startGame() {
        state = State.PLAYING
        lastFrameNs = 0L
        invalidate()
    }

    private fun gameOver() {
        state = State.OVER
        dragging = false
        lastScore = max(0, (elapsed * pointsPerSecond).toInt() - penaltyPerMove * max(0, moves - 1))
        gamesPlayed++
        if (lastScore > best) best = lastScore
        prefs.edit().putInt("games", gamesPlayed).putInt("best", best).apply()
        overAtMs = System.currentTimeMillis()
    }

    private fun update(dt: Float) {
        elapsed += dt
        outerR -= shrinkSpeed * dt
        if (driftAmp > 0f) {
            outerCx = baseCx + driftAmp * sin(elapsed * 0.6f)
            outerCy = baseCy + driftAmp * sin(elapsed * 0.43f)
        }
        if (collides()) gameOver()
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

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (state == State.PLAYING) {
            val now = System.nanoTime()
            val dt = if (lastFrameNs == 0L) 0f else min(0.05f, (now - lastFrameNs) / 1e9f)
            lastFrameNs = now
            update(dt)
        }

        canvas.drawColor(Color.rgb(16, 20, 24))

        // Outer ring
        outerPaint.color = if (state == State.OVER) Color.rgb(255, 90, 90) else Color.WHITE
        if (wobbleAmp == 0f) {
            canvas.drawCircle(outerCx, outerCy, max(0f, outerR), outerPaint)
        } else {
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
            canvas.drawPath(outerPath, outerPaint)
        }

        // Inner circle
        canvas.drawCircle(ix, iy, ir, innerPaint)

        // HUD
        val top = dp(70f)
        canvas.drawText(String.format("%.2f", elapsed), width / 2f, top, bigText)
        canvas.drawText("Moves: $moves   ·   Game ${gamesPlayed + if (state == State.OVER) 0 else 1}",
            width / 2f, top + dp(30f), smallText)

        when (state) {
            State.READY -> canvas.drawText("Touch the circle to start",
                width / 2f, height - dp(60f), smallText)
            State.OVER -> drawGameOver(canvas)
            State.PLAYING -> postInvalidateOnAnimation()
        }
    }

    private fun drawGameOver(canvas: Canvas) {
        canvas.drawColor(Color.argb(170, 0, 0, 0))
        val cx = width / 2f
        var y = height / 2f - dp(90f)
        canvas.drawText("Game over", cx, y, bigText)
        y += dp(50f)
        canvas.drawText(String.format("Time %.2f s   ·   Moves %d", elapsed, moves), cx, y, smallText)
        y += dp(50f)
        canvas.drawText("Score $lastScore", cx, y, bigText)
        y += dp(34f)
        canvas.drawText("Best $best", cx, y, smallText)
        y += dp(70f)
        canvas.drawText("Tap to play again", cx, y, smallText)
    }

    private fun touchesInner(x: Float, y: Float) = hypot(x - ix, y - iy) <= ir + dp(12f)

    private fun grab(x: Float, y: Float) {
        dragging = true
        grabDx = ix - x
        grabDy = iy - y
        grabDistance = 0f
        grabCounted = false
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> when (state) {
                State.READY -> if (touchesInner(e.x, e.y)) {
                    startGame()
                    grab(e.x, e.y)
                }
                State.PLAYING -> if (touchesInner(e.x, e.y)) grab(e.x, e.y)
                State.OVER -> if (System.currentTimeMillis() - overAtMs > 700) newRound()
            }
            MotionEvent.ACTION_MOVE -> if (dragging && state == State.PLAYING) {
                val nx = e.x + grabDx
                val ny = e.y + grabDy
                val d = hypot(nx - ix, ny - iy)
                ix = nx
                iy = ny
                ir += d * growthPerDistance
                grabDistance += d
                // A "move" is one touch-and-drag. Tiny finger jitter doesn't count.
                if (!grabCounted && grabDistance > dp(3f)) {
                    moves++
                    grabCounted = true
                }
            }
            // Lifting the finger does nothing except let go of the circle.
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragging = false
        }
        return true
    }
}
