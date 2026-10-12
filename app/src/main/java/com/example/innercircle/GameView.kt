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
import kotlin.math.floor
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
    private val outerStartFraction = 0.42f    // ring size as a fraction of the screen's short side
    private val growthPerDistance = 0.04f     // inner radius gained per unit of distance dragged
    private val baseShrinkDpPerSec = 6f       // ring shrink speed while touching, level 1
    private val shrinkIncreasePerLevel = 0.4f // extra shrink speed for each level
    private val maxShrinkDpPerSec = 16f       // shrink speed cap (while touching)
    private val releasedSpeedMultiplier = 3f  // ring shrinks this many times faster while no finger is down

    // Wandering: the ring's center drifts toward random points, so it never closes in the same place.
    private val wanderStartDp = 12f           // how far from screen center it can roam at level 1
    private val wanderPerLevelDp = 5f         // extra roaming room per level
    private val maxWanderDp = 70f
    private val wanderSpeedStartDp = 8f       // roaming speed at level 1 (dp per second)
    private val wanderSpeedPerLevelDp = 2f
    private val maxWanderSpeedDp = 30f

    // Shapes unlock by level; each round picks one at random from those unlocked.
    private val ovalAtLevel = 3
    private val spinAtLevel = 4               // shapes start rotating
    private val squareAtLevel = 5
    private val triangleAtLevel = 7
    private val starAtLevel = 9
    private val wobbleStartsAtLevel = 11      // ring edge starts wobbling
    private val maxWobbleDp = 10f
    private val spinStart = 0.15f             // rotation speed in radians per second when spinning starts
    private val spinPerLevel = 0.03f
    private val maxSpin = 0.6f

    // Scoring: points are earned for every dp the ring closes in while you survive.
    private val pointsPerDp = 10f             // base points per dp the ring travels
    private val enduranceBonusMax = 2f        // the last stretch is worth up to (1 + this)x
    private val penaltyPerDpMoved = 2f        // points lost per dp the inner circle travels
    private val handsOffPointsMultiplier = 2f // points are worth this much more while no finger is down
    private val levelBonusPerLevel = 0.08f    // each level is worth 8% more than the one before
    private val parBase = 1500f               // score needed to clear level 1; scales with the level bonus

    // Sound
    private val humLowHz = 70f                // hum pitch when the ring is full size
    private val humHighHz = 150f              // hum pitch when the ring has fully closed
    private val humHandsOffBoost = 1.2f       // hum rises this much while the ring is speeding
    private val humVolume = 0.35f
    // ==========================================

    // Each shape's score multiplier makes up for how much less room it leaves you.
    // "hum" sets how buzzy the hum sounds: smooth for a circle, harsh for a star.
    private enum class Shape(val label: String, val scoreMult: Float, val hum: Float) {
        CIRCLE("Circle", 1.00f, 0.0f),
        OVAL("Oval", 1.15f, 0.2f),
        SQUARE("Square", 1.10f, 0.5f),
        TRIANGLE("Triangle", 1.35f, 0.8f),
        STAR("Star", 1.40f, 1.0f)
    }
    private val ovalA = 1.15f     // oval half-width (1.0 = circle size)
    private val ovalB = 0.72f     // oval half-height

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
    private val twoPi = (2.0 * PI).toFloat()

    private var state = State.READY

    // Progress. You stay on a level until you reach its par score, then move up.
    private var level = prefs.getInt("level", 1)
    private var playedLevel = level           // the level of the round on screen
    private var best = prefs.getInt("best", 0)            // best single run, any level
    private var bestLevel = prefs.getInt("bestLevel", 0)  // ...and the level it was set on
    private var total = prefs.getInt("total", 0)          // sum of your best score on every level
    private var levelBest = 0                 // your best on the level being played
    private var par = 0
    private var lastScore = 0
    private var newBest = false
    private var newLevelBest = false
    private var cleared = false

    // Outer shape
    private var shape = Shape.CIRCLE
    private var lastShape: Shape? = null
    private var shapeAngle = 0f
    private var shapeSpin = 0f
    private var vx = FloatArray(0)            // polygon corner points for a unit-size shape
    private var vy = FloatArray(0)
    private var baseCx = 0f
    private var baseCy = 0f
    private var outerCx = 0f
    private var outerCy = 0f
    private var outerR = 0f
    private var startOuterR = 0f
    private var shrinkSpeed = 0f
    private var wobbleAmp = 0f
    private var levelMult = 1f
    private var scoreMult = 1f

    // Wandering
    private var wanderAmp = 0f
    private var wanderSpeed = 0f
    private var targetX = 0f
    private var targetY = 0f
    private var velX = 0f
    private var velY = 0f
    private var retargetIn = 0f

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
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
    }
    private val ringGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
    }
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
    private val cornerText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
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
        cornerText.textSize = dp(14f)
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

    // ---------------- Shapes ----------------

    private fun pickShape(): Shape {
        val pool = mutableListOf(Shape.CIRCLE)
        if (level >= ovalAtLevel) pool.add(Shape.OVAL)
        if (level >= squareAtLevel) pool.add(Shape.SQUARE)
        if (level >= triangleAtLevel) pool.add(Shape.TRIANGLE)
        if (level >= starAtLevel) pool.add(Shape.STAR)
        val prev = lastShape
        if (pool.size > 1 && prev != null) pool.remove(prev)   // no same shape twice in a row
        return pool[Random.nextInt(pool.size)]
    }

    /** Builds the corner points for polygon shapes, at unit size. */
    private fun buildVertices() {
        val radii: FloatArray = when (shape) {
            Shape.SQUARE -> FloatArray(4) { 1.15f }
            Shape.TRIANGLE -> FloatArray(3) { 1.15f }
            Shape.STAR -> FloatArray(10) { if (it % 2 == 0) 1.15f else 0.62f }
            else -> FloatArray(0)
        }
        val n = radii.size
        vx = FloatArray(n) { radii[it] * cos(twoPi * it / n) }
        vy = FloatArray(n) { radii[it] * sin(twoPi * it / n) }
    }

    /** Distance from the shape's center to its edge in direction phi, at unit size. */
    private fun unitRadius(phi: Float): Float = when (shape) {
        Shape.CIRCLE -> 1f
        Shape.OVAL -> {
            val c = ovalB * cos(phi)
            val s = ovalA * sin(phi)
            ovalA * ovalB / sqrt(c * c + s * s)
        }
        else -> {
            // Find which edge the ray hits, then intersect the ray with that edge.
            val n = vx.size
            val step = twoPi / n
            var u = phi % twoPi
            if (u < 0f) u += twoPi
            val k = min(n - 1, floor(u / step).toInt())
            val k2 = (k + 1) % n
            val ex = vx[k2] - vx[k]
            val ey = vy[k2] - vy[k]
            val dx = cos(phi)
            val dy = sin(phi)
            (vx[k] * ey - vy[k] * ex) / (dx * ey - dy * ex)
        }
    }

    /** Distance from the ring's center to its edge at screen angle theta, including spin and wobble. */
    private fun boundaryAt(theta: Float): Float =
        outerR * unitRadius(theta - shapeAngle) + wobbleAmp * sin(3f * theta + elapsed * 2.2f)

    /** Smallest gap between the inner circle's edge and the ring. Below zero means they touch. */
    private fun minClearance(): Float {
        var m = Float.MAX_VALUE
        val samples = 48
        for (k in 0 until samples) {
            val a = twoPi * k / samples
            val dx = ix + ir * cos(a) - outerCx
            val dy = iy + ir * sin(a) - outerCy
            val c = boundaryAt(atan2(dy, dx)) - hypot(dx, dy)
            if (c < m) m = c
        }
        return m
    }

    private fun collides() = outerR <= 0f || minClearance() <= 0f

    /** 1 when there's plenty of room between the circles, 0 when they're touching. */
    private fun gapFraction(): Float = (minClearance() / dp(70f)).coerceIn(0f, 1f)

    // ---------------- Rounds ----------------

    private fun newRound() {
        baseCx = width / 2f
        baseCy = height / 2f
        outerCx = baseCx
        outerCy = baseCy
        outerR = min(width, height) * outerStartFraction
        startOuterR = outerR
        ir = dp(innerStartRadiusDp)
        elapsed = 0f

        // Difficulty and score multiplier both grow with the level.
        playedLevel = level
        val g = level - 1
        shrinkSpeed = dp(min(maxShrinkDpPerSec, baseShrinkDpPerSec + shrinkIncreasePerLevel * g))
        wobbleAmp = if (level >= wobbleStartsAtLevel) dp(min(maxWobbleDp, (level - wobbleStartsAtLevel + 1) * 1f)) else 0f
        levelMult = 1f + levelBonusPerLevel * g
        par = (parBase * levelMult).toInt()
        levelBest = prefs.getInt("lvl_$level", 0)

        // Random shape, random starting angle, random spin direction.
        shape = pickShape()
        lastShape = shape
        buildVertices()
        shapeAngle = Random.nextFloat() * twoPi
        shapeSpin = if (level >= spinAtLevel && shape != Shape.CIRCLE) {
            val s = min(maxSpin, spinStart + spinPerLevel * (level - spinAtLevel))
            if (Random.nextBoolean()) s else -s
        } else 0f
        scoreMult = levelMult * shape.scoreMult

        // Random wandering for the ring's center.
        wanderAmp = dp(min(maxWanderDp, wanderStartDp + wanderPerLevelDp * g))
        wanderSpeed = dp(min(maxWanderSpeedDp, wanderSpeedStartDp + wanderSpeedPerLevelDp * g))
        velX = 0f
        velY = 0f
        pickTarget()

        // Random spawn inside the shape, never right in the middle.
        var placed = false
        for (attempt in 0 until 30) {
            val angle = Random.nextFloat() * twoPi
            val edge = outerR * unitRadius(angle - shapeAngle) - ir - dp(12f)
            val dist = edge * (0.35f + 0.65f * sqrt(Random.nextFloat()))
            ix = baseCx + cos(angle) * dist
            iy = baseCy + sin(angle) * dist
            if (minClearance() > dp(6f)) {
                placed = true
                break
            }
        }
        if (!placed) {
            ix = baseCx
            iy = baseCy
        }

        movedDp = 0f
        points = 0f
        newBest = false
        newLevelBest = false
        cleared = false
        fingerDown = false
        state = State.READY
        sound.humVolume = 0f
        sound.humFreq = humLowHz
        sound.danger = 0f
        sound.wobble = 0f
        sound.brightness = shape.hum
        invalidate()
    }

    private fun pickTarget() {
        val a = Random.nextFloat() * twoPi
        val r = wanderAmp * sqrt(Random.nextFloat())
        targetX = baseCx + cos(a) * r
        targetY = baseCy + sin(a) * r
        retargetIn = 1.5f + Random.nextFloat() * 2.5f   // change course every 1.5–4 seconds
    }

    private fun startGame() {
        state = State.PLAYING
        lastFrameNs = 0L
        sound.click()
        sound.humVolume = humVolume
        invalidate()
    }

    private fun currentScore(): Int =
        max(0, (points * scoreMult - movedDp * penaltyPerDpMoved).toInt())

    private fun gameOver() {
        state = State.OVER
        fingerDown = false
        sound.humVolume = 0f
        sound.crash()
        lastScore = currentScore()

        // Per-level best; the total is the sum of all of them.
        if (lastScore > levelBest) {
            total += lastScore - levelBest
            levelBest = lastScore
            newLevelBest = true
        }
        // Best single run anywhere, with the level it happened on.
        if (lastScore > best) {
            best = lastScore
            bestLevel = playedLevel
            newBest = true
        }
        // Reach par to move up a level; otherwise you replay this one.
        cleared = lastScore >= par
        if (cleared) level = playedLevel + 1

        prefs.edit()
            .putInt("lvl_$playedLevel", levelBest)
            .putInt("total", total)
            .putInt("best", best)
            .putInt("bestLevel", bestLevel)
            .putInt("level", level)
            .apply()
        overAtMs = System.currentTimeMillis()
    }

    // DEBUG: wipes saved games and best score — remove before release
    private fun resetAll() {
        prefs.edit().clear().apply()
        level = 1
        best = 0
        bestLevel = 0
        total = 0
        lastScore = 0
        lastShape = null
        newRound()
    }

    private fun update(dt: Float) {
        elapsed += dt
        // Ring closes slowly while you hold the screen, fast while you don't.
        val speed = if (fingerDown) shrinkSpeed else shrinkSpeed * releasedSpeedMultiplier
        val shrink = speed * dt
        // Endurance bonus: the further the ring has closed, the more each dp is worth.
        val progress = ((startOuterR - outerR) / startOuterR).coerceIn(0f, 1f)
        // Hands off is riskier, so it pays more.
        val handsOff = if (fingerDown) 1f else handsOffPointsMultiplier
        points += (shrink / density) * pointsPerDp * (1f + enduranceBonusMax * progress) * handsOff
        outerR -= shrink

        // Spin
        shapeAngle += shapeSpin * dt

        // Wander: steer smoothly toward a random target, picking a new one now and then.
        retargetIn -= dt
        val tx = targetX - outerCx
        val ty = targetY - outerCy
        val td = hypot(tx, ty)
        if (td < dp(4f) || retargetIn <= 0f) pickTarget()
        val desX = if (td > 0f) tx / td * wanderSpeed else 0f
        val desY = if (td > 0f) ty / td * wanderSpeed else 0f
        val k = min(1f, dt * 1.5f)
        velX += (desX - velX) * k
        velY += (desY - velY) * k
        outerCx += velX * dt
        outerCy += velY * dt

        // Hum follows the ring: higher as it shrinks, wavering with its wobble,
        // pulsing faster as the gap closes, and rising while the ring is speeding.
        sound.humFreq = (humLowHz + (humHighHz - humLowHz) * progress) *
            (if (fingerDown) 1f else humHandsOffBoost)
        sound.wobble = if (wobbleAmp > 0f) (wobbleAmp / dp(maxWobbleDp)).coerceIn(0f, 1f) else 0f
        sound.danger = 1f - gapFraction()

        if (collides()) gameOver()
    }

    // ---------------- Drawing ----------------

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

    private fun buildOuterPath() {
        outerPath.reset()
        val steps = 240
        for (i in 0..steps) {
            val th = twoPi * i / steps
            val r = max(0f, boundaryAt(th))
            val x = outerCx + r * cos(th)
            val y = outerCy + r * sin(th)
            if (i == 0) outerPath.moveTo(x, y) else outerPath.lineTo(x, y)
        }
        outerPath.close()
    }

    private fun drawRing(canvas: Canvas, paint: Paint) {
        if (shape == Shape.CIRCLE && wobbleAmp == 0f) {
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

        // Outer shape with a soft glow
        if (shape != Shape.CIRCLE || wobbleAmp > 0f) buildOuterPath()
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
        val cx = width / 2f
        val top = dp(80f)
        bigText.color = Color.WHITE
        canvas.drawText(String.format("%.2f", elapsed), cx, top, bigText)
        val score = currentScore()
        midText.color = when {
            state == State.PLAYING && !fingerDown -> gold
            score >= par -> innerSmall        // turns mint once you've reached par
            else -> accent
        }
        canvas.drawText("$score", cx, top + dp(34f), midText)
        smallText.color = muted
        canvas.drawText("Level $playedLevel  ·  Par $par  ·  Moved ${movedDp.toInt()}", cx, top + dp(60f), smallText)

        // High score with its level, and the running total, in the top-left corner
        cornerText.color = gold
        val bestLine = if (best > 0) "High score $best  ·  Level $bestLevel" else "High score —"
        canvas.drawText(bestLine, dp(16f), dp(30f), cornerText)
        cornerText.color = muted
        canvas.drawText("Total $total", dp(16f), dp(50f), cornerText)

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

        val mult = String.format("%.2f", scoreMult)
        smallText.color = Color.WHITE
        canvas.drawText("Level $playedLevel  ·  ${shape.label}  ·  Score ×$mult", cx, y - dp(28f), smallText)
        smallText.color = muted
        val bestText = if (levelBest > 0) "Your best on this level: $levelBest" else "Level not played yet"
        canvas.drawText(bestText, cx, y - dp(52f), smallText)

        val note = when (playedLevel) {
            ovalAtLevel -> "New shape: ovals!"
            spinAtLevel -> "New: shapes now spin!"
            squareAtLevel -> "New shape: squares!"
            triangleAtLevel -> "New shape: triangles!"
            starAtLevel -> "New shape: stars!"
            wobbleStartsAtLevel -> "New: the ring wobbles!"
            else -> null
        }
        if (note != null) {
            smallText.color = warnColor
            canvas.drawText(note, cx, y - dp(80f), smallText)
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
        when {
            newBest -> {
                midText.color = gold
                canvas.drawText("NEW HIGH SCORE!", cx, y, midText)
            }
            newLevelBest -> {
                midText.color = gold
                canvas.drawText("Level $playedLevel best!", cx, y, midText)
            }
            else -> {
                smallText.color = muted
                canvas.drawText("Level $playedLevel best $levelBest", cx, y, smallText)
            }
        }
        y += dp(34f)
        if (cleared) {
            smallText.color = innerSmall
            canvas.drawText("Level cleared! On to Level $level", cx, y, smallText)
        } else {
            smallText.color = warnColor
            canvas.drawText("Score $par to clear Level $playedLevel", cx, y, smallText)
        }
        y += dp(56f)
        smallText.color = Color.WHITE
        canvas.drawText("Tap to play again", cx, y, smallText)
    }

    // DEBUG: remove before release
    private fun drawResetButton(canvas: Canvas) {
        canvas.drawRoundRect(resetRect, dp(8f), dp(8f), resetFill)
        val ty = resetRect.centerY() - (resetText.descent() + resetText.ascent()) / 2f
        canvas.drawText("RESET", resetRect.centerX(), ty, resetText)
    }

    // ---------------- Touch ----------------

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
