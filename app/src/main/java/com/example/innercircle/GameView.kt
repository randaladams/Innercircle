package com.example.innercircle

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import kotlin.math.PI
import kotlin.math.abs
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
    private val outerStartFraction = 0.40f    // ring size as a fraction of the screen's short side
    private val growthPerDistance = 0.04f     // inner radius gained per unit of distance dragged
    private val baseShrinkDpPerSec = 8f       // ring shrink speed while touching, level 1
    private val shrinkIncreasePerLevel = 0.6f // extra shrink speed for each level
    private val maxShrinkDpPerSec = 20f       // shrink speed cap (while touching)
    private val releasedSpeedMultiplier = 3f  // ring shrinks this many times faster while no finger is down

    // Wandering: the ring's center drifts toward random points, so it never closes in the same place.
    private val wanderStartDp = 20f           // how far from screen center it can roam at level 1
    private val wanderPerLevelDp = 6f         // extra roaming room per level
    private val maxWanderDp = 80f
    private val wanderSpeedStartDp = 14f      // roaming speed at level 1 (dp per second)
    private val wanderSpeedPerLevelDp = 3f
    private val maxWanderSpeedDp = 45f

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

    // Par: the score needed to clear a level, as a share of a PERFECT run on that exact round.
    // A perfect run = move straight to the safe spot, then hands off until the ring closes.
    // It already accounts for the shape, the spawn distance and the ring speed.
    private val parPercentStart = 0.70f       // share of perfect needed when the ring barely moves
    private val parPercentMin = 0.55f         // share needed when the ring roams and spins the most
    // Endless ramp: everything else maxes out around level 21, so from there par keeps creeping up.
    private val parCreepStartsAtLevel = 21    // par starts creeping up at this level...
    private val parCreepPerLevel = 0.005f     // ...by 0.5% of perfect every level...
    private val parPercentCap = 0.78f         // ...until it needs 78% of a perfect run (around level 66)
    private val mercyPerFail = 0.05f          // par drops 5% for each miss on the current level...
    private val mercyFloor = 0.70f            // ...but never below 70% of its normal value

    // Tries and revives
    private val strikesPerGame = 3            // miss par this many times in one game and it's over
    private val revivesPerRun = 1             // revives (after watching an ad) allowed per game
    private val roundsToEarnStrike = 5        // clear this many levels in a row without a strike to get one back
    private val fakeAdSeconds = 5             // length of the placeholder ad
    private val proWaitSeconds = 3            // Pro screen on exit: seconds before Exit / Not now appear
    private val checkpointEvery = 10          // Pro: new games can start at level 11, 21, 31... once reached
    private val longPressMs = 800L            // hold RESET this long to toggle Pro test mode

    // Space kept free at the bottom for a Google AdMob banner (a standard banner is 50dp tall).
    private val adBannerHeightDp = 60f
    private val hudHeightDp = 186f            // top area used by the timer, score and progress bar
    private val bottomReserveDp = 100f        // bottom room for the ready-screen text and menu buttons
    private val showAdPlaceholder = true      // DEBUG: outlines the banner space; set false to hide

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
    private var bgCenter = Color.rgb(28, 36, 72)
    private var bgEdge = Color.rgb(8, 10, 22)
    private var safeColor = Color.rgb(80, 220, 255)     // ring when you have room
    private var warnColor = Color.rgb(255, 200, 60)     // ring when it's getting close
    private var dangerColor = Color.rgb(255, 70, 100)   // ring when you're about to touch
    private var innerSmall = Color.rgb(110, 255, 170)   // inner circle at starting size
    private var innerBig = Color.rgb(200, 110, 255)     // inner circle once it's grown a lot
    private var accent = Color.rgb(80, 220, 255)
    private val gold = Color.rgb(255, 210, 80)
    private val muted = Color.rgb(160, 172, 200)
    // ==========================================

    private enum class State { READY, PLAYING, OVER, AD, RUN_OVER, PRO, PRACTICE_PICK, THEMES, STATS }

    private val prefs = context.getSharedPreferences("inner_circle", Context.MODE_PRIVATE)
    private val sound = SoundEngine()
    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density
    /** Bottom of the play area; below it is kept free for the ad banner. */
    private val playBottom: Float get() = height - (if (isPro) 0f else dp(adBannerHeightDp))
    private val twoPi = (2.0 * PI).toFloat()

    private var state = State.READY

    // A game starts at level 1. Reach par to move up. Miss par and it's a strike; 3 strikes and you're out.
    private var level = prefs.getInt("level", 1)
    private var playedLevel = level           // the level of the round on screen
    private var triesLeft = prefs.getInt("triesLeft", strikesPerGame)   // strikes remaining this game
    private var runScore = prefs.getInt("runScore", 0)          // every round's score added up this run
    private var revivesLeft = prefs.getInt("revivesLeft", revivesPerRun)
    private var levelFails = prefs.getInt("levelFails", 0)      // misses on the current level (for mercy)
    private var cleanStreak = prefs.getInt("cleanStreak", 0)    // levels cleared in a row without a strike
    private var earnsStrike = false           // this round's clear earns a strike back
    private var best = prefs.getInt("best", 0)                  // best run score ever
    private var bestLevel = prefs.getInt("bestLevel", 0)        // ...and the level that run reached
    private var levelBest = 0                 // your best single round on this level
    private var par = 0
    private var perfect = 0                   // best possible score for this round
    private var parPercent = 0f               // share of perfect needed this round
    private var mercy = 1f                    // par reduction from tries already used
    private var unitInr = 1f                  // room at the shape's tightest point (1.0 = circle)
    private var lastScore = 0
    private var newBest = false
    private var newLevelBest = false
    private var newHighScore = false          // this game's total just passed the all-time high score
    private var cleared = false
    private var outOfTries = false
    private var paused = false                // after a revive: waits for a touch before resuming
    private var adEndsAt = 0L
    private var pausedMsg = "Touch to continue"
    // Pro. For now it's switched on by holding RESET (test mode); later Google Play Billing sets it.
    private var isPro = prefs.getBoolean("pro", false)
    private var maxLevel = prefs.getInt("maxLevel", prefs.getInt("level", 1))  // highest level ever reached
    private var resetHeld = false
    private val checkpointRects = Array(4) { RectF() }
    private var checkpointLevels = emptyList<Int>()
    private val toggleProRunnable = Runnable {
        resetHeld = false
        isPro = !isPro
        prefs.edit().putBoolean("pro", isPro).apply()
        applyTheme()
        android.widget.Toast.makeText(context, if (isPro) "Pro test mode ON" else "Pro test mode OFF",
            android.widget.Toast.LENGTH_SHORT).show()
        invalidate()
    }
    private var stateBeforePro = State.READY
    private var proShownAt = 0L
    private val proUpgradeRect = RectF()
    private val proStayRect = RectF()
    private val proExitRect = RectF()

    // ---- Pro: practice, themes, stats ----
    private var practice = false              // practice round: no strikes, nothing saved
    private var practiceLevel = 1
    private var pickLevel = 1
    private var proFromMenu = false           // Pro screen opened from a menu button (not from Back)
    private val menuRects = Array(3) { RectF() }
    private val menuLabels = arrayOf("PRACTICE", "THEMES", "STATS")
    private val endPracticeRect = RectF()
    private val pickRects = Array(4) { RectF() }
    private val pickDeltas = intArrayOf(-10, -1, 1, 10)
    private val pickPlayRect = RectF()
    private val backRect = RectF()
    private val themeRects = Array(5) { RectF() }

    private class Theme(
        val name: String, val bgCenter: Int, val bgEdge: Int, val safe: Int, val warn: Int,
        val danger: Int, val innerSmall: Int, val innerBig: Int, val accent: Int
    )
    private val themes = listOf(
        Theme("Classic", Color.rgb(28, 36, 72), Color.rgb(8, 10, 22), Color.rgb(80, 220, 255), Color.rgb(255, 200, 60),
            Color.rgb(255, 70, 100), Color.rgb(110, 255, 170), Color.rgb(200, 110, 255), Color.rgb(80, 220, 255)),
        Theme("Neon", Color.rgb(40, 0, 60), Color.rgb(6, 0, 14), Color.rgb(0, 255, 200), Color.rgb(255, 235, 0),
            Color.rgb(255, 0, 130), Color.rgb(255, 90, 230), Color.rgb(120, 90, 255), Color.rgb(0, 255, 200)),
        Theme("Ocean", Color.rgb(10, 60, 95), Color.rgb(2, 14, 28), Color.rgb(130, 235, 255), Color.rgb(255, 215, 130),
            Color.rgb(255, 105, 95), Color.rgb(255, 190, 140), Color.rgb(255, 95, 130), Color.rgb(130, 235, 255)),
        Theme("Ember", Color.rgb(70, 22, 10), Color.rgb(16, 4, 2), Color.rgb(255, 185, 90), Color.rgb(255, 130, 40),
            Color.rgb(255, 45, 45), Color.rgb(255, 240, 200), Color.rgb(255, 140, 40), Color.rgb(255, 195, 100)),
        Theme("Mono", Color.rgb(44, 44, 50), Color.rgb(8, 8, 10), Color.rgb(235, 235, 235), Color.rgb(170, 170, 175),
            Color.rgb(255, 85, 85), Color.rgb(255, 255, 255), Color.rgb(140, 140, 150), Color.rgb(235, 235, 235))
    )
    private var themeIndex = prefs.getInt("theme", 0)

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
    private val rowText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val rightText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.RIGHT
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val outerPath = Path()

    // Progress bar, revive button and fake ad
    private val barBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(60, 255, 255, 255) }
    private val barFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tinyText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val barRect = RectF()
    private val reviveRect = RectF()
    private val reviveFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 180, 40) }
    private val reviveText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(40, 25, 0)
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val adBg = Paint().apply { color = Color.rgb(240, 240, 246) }

    // DEBUG: safe spot finder — remove before release
    private val safeButtonEnabled = false   // set to true to bring the SAFE button back
    private var showSafe = false
    private var safeX = 0f
    private var safeY = 0f
    private var safeR = 0f
    private val safeRect = RectF()
    private val safeFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(220, 30, 90, 160) }
    private val safeFillOn = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(240, 40, 160, 90) }
    private val safePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
    }
    private val safeText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = Color.WHITE
    }

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
        tickPaint.strokeWidth = dp(2f)
        tinyText.textSize = dp(10f)
        reviveText.textSize = dp(18f)
        safePaint.strokeWidth = dp(2f)
        safePaint.pathEffect = DashPathEffect(floatArrayOf(dp(6f), dp(5f)), 0f)
        safeText.textSize = dp(13f)
        bigText.textSize = dp(40f)
        midText.textSize = dp(24f)
        smallText.textSize = dp(16f)
        cornerText.textSize = dp(14f)
        rightText.textSize = dp(14f)
        rowText.textSize = dp(34f)
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
    fun pauseAudio() {
        pauseRound("Paused: touch to continue")
        sound.stop()
    }

    /** Freeze a round in progress until the player touches the screen again. */
    private fun pauseRound(msg: String) {
        if (state == State.PLAYING && !paused) {
            paused = true
            pausedMsg = msg
            fingerDown = false
            sound.humVolume = 0f
            invalidate()
        }
    }

    /**
     * Called by the activity when Back is pressed. Shows the Pro screen first.
     * Returns false only when the app should actually close.
     */
    fun onBackRequested(): Boolean {
        if (state == State.PRACTICE_PICK || state == State.THEMES || state == State.STATS) {
            state = State.READY
            invalidate()
            return true
        }
        if (practice && (state == State.READY || state == State.OVER)) {
            practice = false                    // Back ends practice and returns to your game
            lastShape = null
            newRound()
            return true
        }
        if (state == State.PRO && proFromMenu) {
            proFromMenu = false
            state = stateBeforePro
            invalidate()
            return true
        }
        if (isPro && state != State.AD && state != State.PRO) return false   // Pro: no upsell, just exit
        if (state == State.AD) return true                      // can't skip the ad with Back
        if (state == State.PRO) {
            // Back is ignored during the short wait, then it exits like the Exit button.
            return System.currentTimeMillis() - proShownAt < proWaitSeconds * 1000L
        }
        pauseRound("Paused: touch to continue")
        stateBeforePro = state
        proFromMenu = false
        state = State.PRO
        proShownAt = System.currentTimeMillis()
        invalidate()
        return true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        applyTheme()
        resetRect.set(w - dp(96f), dp(14f), w - dp(12f), dp(50f)) // DEBUG
        safeRect.set(w - dp(96f), dp(58f), w - dp(12f), dp(94f))   // DEBUG
        newRound()
    }

    /** Pro themes recolor the whole game; without Pro it's always Classic. */
    private fun applyTheme() {
        val t = themes[if (isPro) themeIndex.coerceIn(0, themes.size - 1) else 0]
        bgCenter = t.bgCenter
        bgEdge = t.bgEdge
        safeColor = t.safe
        warnColor = t.warn
        dangerColor = t.danger
        innerSmall = t.innerSmall
        innerBig = t.innerBig
        accent = t.accent
        if (width > 0) {
            bgPaint.shader = RadialGradient(
                width / 2f, height / 2f, max(width, height) * 0.75f,
                bgCenter, bgEdge, Shader.TileMode.CLAMP
            )
        }
        invalidate()
    }

    // ---------------- Shapes ----------------

    private fun pickShape(): Shape {
        val pool = mutableListOf(Shape.CIRCLE)
        if (playedLevel >= ovalAtLevel) pool.add(Shape.OVAL)
        if (playedLevel >= squareAtLevel) pool.add(Shape.SQUARE)
        if (playedLevel >= triangleAtLevel) pool.add(Shape.TRIANGLE)
        if (playedLevel >= starAtLevel) pool.add(Shape.STAR)
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
        // Centered in the free space between the top display and the bottom text/buttons
        baseCy = (dp(hudHeightDp) + playBottom - dp(bottomReserveDp)) / 2f
        outerCx = baseCx
        outerCy = baseCy
        outerR = min(width, height) * outerStartFraction
        startOuterR = outerR
        ir = dp(innerStartRadiusDp)
        elapsed = 0f

        // Difficulty and score multiplier both grow with the level.
        playedLevel = if (practice) practiceLevel else level
        val g = playedLevel - 1
        shrinkSpeed = dp(min(maxShrinkDpPerSec, baseShrinkDpPerSec + shrinkIncreasePerLevel * g))
        wobbleAmp = if (playedLevel >= wobbleStartsAtLevel) dp(min(maxWobbleDp, (playedLevel - wobbleStartsAtLevel + 1) * 1f)) else 0f
        levelMult = 1f + levelBonusPerLevel * g
        levelBest = prefs.getInt("lvl_$playedLevel", 0)

        // Random shape, random starting angle, random spin direction.
        shape = pickShape()
        lastShape = shape
        buildVertices()
        shapeAngle = Random.nextFloat() * twoPi
        shapeSpin = if (playedLevel >= spinAtLevel && shape != Shape.CIRCLE) {
            val s = min(maxSpin, spinStart + spinPerLevel * (playedLevel - spinAtLevel))
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

        computePar()
        if (showSafe) findSafeSpot() // DEBUG

        movedDp = 0f
        points = 0f
        newBest = false
        newLevelBest = false
        cleared = false
        outOfTries = false
        paused = false
        fingerDown = false
        state = State.READY
        sound.humVolume = 0f
        sound.humFreq = humLowHz
        sound.danger = 0f
        sound.wobble = 0f
        sound.brightness = shape.hum
        invalidate()
    }

    /** Works out the best possible score for this round, then sets par as a share of it. */
    private fun computePar() {
        // How much room the shape leaves at its tightest point (1.0 = circle).
        var inr = Float.MAX_VALUE
        for (i in 0 until 720) {
            val r = unitRadius(twoPi * i / 720)
            if (r < inr) inr = r
        }
        unitInr = inr
        // Perfect run: drag straight to the center, growing on the way...
        val d = hypot(ix - baseCx, iy - baseCy)
        val irFinal = ir + d * growthPerDistance
        // ...then hands off until the shape is too small to hold the circle.
        val rEnd = min(startOuterR, (irFinal + wobbleAmp) / inr)
        val travel = startOuterR - rEnd
        val pts = (pointsPerDp * handsOffPointsMultiplier / density) *
            (travel + enduranceBonusMax * travel * travel / (2f * startOuterR))
        perfect = max(0, (pts * scoreMult - d / density * penaltyPerDpMoved).toInt())

        // A ring that roams and spins can't be played perfectly, so it asks for a smaller share.
        val movement = (0.5f * wanderAmp / dp(maxWanderDp) + 0.5f * abs(shapeSpin) / maxSpin).coerceIn(0f, 1f)
        parPercent = parPercentStart - (parPercentStart - parPercentMin) * movement
        if (playedLevel >= parCreepStartsAtLevel) {
            val creep = parCreepPerLevel * (playedLevel - parCreepStartsAtLevel + 1)
            parPercent = min(parPercentCap, parPercent + creep)
        }
        mercy = if (practice) 1f else max(mercyFloor, 1f - mercyPerFail * levelFails)
        par = max(1, (perfect * parPercent * mercy).toInt())
    }

    // DEBUG: brute-force search for the spot where the biggest circle fits inside the shape.
    // It doesn't assume the answer; it tests points across the whole shape and zooms in on the best.
    private fun findSafeSpot() {
        val n = 720
        val bx = FloatArray(n)
        val by = FloatArray(n)
        for (i in 0 until n) {
            val th = twoPi * i / n
            val r = boundaryAt(th)
            bx[i] = outerCx + r * cos(th)
            by[i] = outerCy + r * sin(th)
        }
        fun clearance(x: Float, y: Float): Float {
            val dx = x - outerCx
            val dy = y - outerCy
            if (hypot(dx, dy) >= boundaryAt(atan2(dy, dx))) return -1f   // outside the shape
            var m = Float.MAX_VALUE
            for (i in 0 until n) {
                val c = hypot(x - bx[i], y - by[i])
                if (c < m) m = c
            }
            return m
        }
        var bx0 = outerCx
        var by0 = outerCy
        var bestC = -1f
        var span = outerR * 1.2f
        for (pass in 0 until 4) {
            val cx0 = bx0
            val cy0 = by0
            for (gx in -10..10) for (gy in -10..10) {
                val x = cx0 + span * gx / 10f
                val y = cy0 + span * gy / 10f
                val c = clearance(x, y)
                if (c > bestC) {
                    bestC = c
                    bx0 = x
                    by0 = y
                }
            }
            span /= 4f
        }
        safeX = bx0
        safeY = by0
        safeR = bestC
    }

    private fun pickTarget() {
        val a = Random.nextFloat() * twoPi
        val r = wanderAmp * sqrt(Random.nextFloat())
        targetX = baseCx + cos(a) * r
        targetY = baseCy + sin(a) * r
        retargetIn = 1.5f + Random.nextFloat() * 2.5f   // change course every 1.5–4 seconds
        // Keep the whole shape on screen (pointed shapes reach 1.15x the ring size), and
        // mostly clear of the top display. There's more room to roam as the ring shrinks.
        val ext = outerR * 1.15f + wobbleAmp + dp(6f)
        targetX = if (width - 2 * ext > 0) targetX.coerceIn(ext, width - ext) else width / 2f
        val minY = dp(hudHeightDp) - dp(40f) + ext
        val maxY = playBottom - ext
        targetY = if (maxY > minY) targetY.coerceIn(minY, maxY) else baseCy
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

    /** The circles touched. Work out the result, but don't save it yet: the player may revive. */
    private fun gameOver() {
        state = State.OVER
        fingerDown = false
        sound.humVolume = 0f
        sound.crash()
        lastScore = currentScore()
        newLevelBest = lastScore > levelBest
        newHighScore = best > 0 && runScore + lastScore > best
        cleared = lastScore >= par
        if (!cleared) sound.fail()     // only after we know whether par was made
        outOfTries = !cleared && triesLeft <= 1
        earnsStrike = cleared && triesLeft < strikesPerGame && cleanStreak + 1 >= roundsToEarnStrike
        if (practice) {
            // Practice never costs a strike or touches your bests
            outOfTries = false
            earnsStrike = false
            newHighScore = false
            newLevelBest = false
        }
        overAtMs = System.currentTimeMillis()
    }

    /** The player moved on without reviving: lock in the round's result. */
    private fun commitRound() {
        if (practice) {
            newRound()          // just play the same practice level again
            return
        }
        val ed = prefs.edit()
        if (newLevelBest) {
            levelBest = lastScore
            ed.putInt("lvl_$playedLevel", levelBest)
        }
        runScore += lastScore
        if (cleared) {
            level = playedLevel + 1
            if (level > maxLevel) {
                maxLevel = level
                ed.putInt("maxLevel", maxLevel)
            }
            levelFails = 0
            cleanStreak += 1
            if (cleanStreak >= roundsToEarnStrike) {
                if (triesLeft < strikesPerGame) triesLeft += 1    // strike earned back (max 3)
                cleanStreak = 0
            }
        } else {
            triesLeft -= 1          // strike!
            levelFails += 1
            cleanStreak = 0
        }
        if (outOfTries) {
            newBest = runScore > best
            if (newBest) {
                best = runScore
                bestLevel = playedLevel
            }
            ed.putInt("best", best).putInt("bestLevel", bestLevel)
        }
        // Stats (Pro screen shows them)
        statInc(ed, "st_rounds")
        statInc(ed, "st_pctSum", if (par > 0) min(300, lastScore * 100 / par) else 0)
        statInc(ed, "st_time", elapsed.toInt())
        statInc(ed, "st_rounds_${shape.name}")
        if (cleared) {
            statInc(ed, "st_cleared")
            val run = prefs.getInt("st_curRun", 0) + 1
            ed.putInt("st_curRun", run)
            if (run > prefs.getInt("st_bestRun", 0)) ed.putInt("st_bestRun", run)
        } else {
            statInc(ed, "st_strikes")
            statInc(ed, "st_miss_${shape.name}")
            ed.putInt("st_curRun", 0)
        }
        if (outOfTries) {
            statInc(ed, "st_games")
            val hist = historyList() + runScore
            ed.putString("st_history", hist.takeLast(20).joinToString(","))
        }

        ed.putInt("level", level)
            .putInt("triesLeft", triesLeft)
            .putInt("runScore", runScore)
            .putInt("revivesLeft", revivesLeft)
            .putInt("levelFails", levelFails)
            .putInt("cleanStreak", cleanStreak)
            .apply()
        if (outOfTries) {
            state = State.RUN_OVER
            overAtMs = System.currentTimeMillis()
            invalidate()
        } else {
            newRound()
        }
    }

    /** Start a fresh run from level 1. */
    private fun newRun(startLevel: Int = 1) {
        level = startLevel
        triesLeft = strikesPerGame
        runScore = 0
        revivesLeft = revivesPerRun
        levelFails = 0
        cleanStreak = 0
        lastShape = null
        prefs.edit()
            .putInt("levelFails", levelFails)
            .putInt("cleanStreak", cleanStreak)
            .putInt("level", level)
            .putInt("triesLeft", triesLeft)
            .putInt("runScore", runScore)
            .putInt("revivesLeft", revivesLeft)
            .apply()
        newRound()
    }

    // Revive is only offered when the game is over (third strike).
    private fun reviveAvailable() = revivesLeft > 0 && outOfTries

    /** Placeholder for a real rewarded ad. */
    private fun startAd() {
        state = State.AD
        adEndsAt = System.currentTimeMillis() + fakeAdSeconds * 1000L
        invalidate()
    }

    /** Ad watched: put the round back in play with room to breathe. */
    private fun revive() {
        revivesLeft -= 1
        prefs.edit().putInt("revivesLeft", revivesLeft).apply()
        // Fresh-size circle in the middle of the ring, and grow the ring back if it's too tight.
        ir = dp(innerStartRadiusDp)
        val needed = (ir * 2.5f + wobbleAmp) / unitInr
        if (outerR < needed) outerR = min(needed, startOuterR)
        ix = outerCx
        iy = outerCy
        state = State.PLAYING
        paused = true
        pausedMsg = "Revived! Touch to continue"
        fingerDown = false
        lastFrameNs = 0L
        invalidate()
    }

    // DEBUG: wipes all saved progress and scores — remove before release
    private fun resetAll() {
        prefs.edit().clear().putBoolean("pro", isPro).apply()   // keep the Pro switch through a reset
        maxLevel = 1
        best = 0
        bestLevel = 0
        lastScore = 0
        newRun()
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

        if (state == State.PLAYING && !paused) {
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

        // DEBUG: safe spot overlay
        if (showSafe) drawSafeSpot(canvas)

        // Small marker where the finger is, so you can see you're in control
        if (state == State.PLAYING && fingerDown) {
            fingerPaint.color = withAlpha(ic, 110)
            canvas.drawCircle(lastX, lastY, dp(20f), fingerPaint)
        }

        // HUD
        val cx = width / 2f
        // Timer (left) and score (right) share one line to save room
        val top = dp(108f)
        rowText.color = Color.WHITE
        canvas.drawText(String.format("%.2f", elapsed), width * 0.30f, top, rowText)
        val score = currentScore()
        rowText.color = when {
            state == State.PLAYING && !fingerDown -> gold
            score >= par -> innerSmall        // turns mint once you've reached par
            else -> accent
        }
        canvas.drawText("$score", width * 0.70f, top, rowText)
        smallText.color = muted
        canvas.drawText("Level $playedLevel  ·  Par $par  ·  Moved ${movedDp.toInt()}", cx, top + dp(26f), smallText)
        drawProgress(canvas, score)

        // High score with its level, and the running total, in the top-left corner
        cornerText.color = gold
        val bestLine = if (best > 0) "High score $best  ·  Lv $bestLevel" else "High score —"
        canvas.drawText(bestLine, dp(16f), dp(30f), cornerText)
        cornerText.color = muted
        val inRound = state == State.PLAYING || state == State.OVER
        val live = runScore + if (inRound) score else 0
        if (practice) {
            cornerText.color = accent
            canvas.drawText("PRACTICE  ·  scores don't count", dp(16f), dp(50f), cornerText)
        } else {
            canvas.drawText("Run $live    Strikes ${triesDots()}", dp(16f), dp(50f), cornerText)
            if (triesLeft < strikesPerGame) {
                // Progress toward earning a strike back
                canvas.drawText("Streak $cleanStreak/$roundsToEarnStrike", dp(16f), dp(70f), cornerText)
            }
        }

        when (state) {
            State.READY -> drawReady(canvas)
            State.OVER -> drawGameOver(canvas)
            State.PLAYING -> {
                if (paused) {
                    midText.color = innerSmall
                    canvas.drawText(pausedMsg, cx, playBottom - dp(60f), midText)
                } else {
                    if (!fingerDown) {
                        smallText.color = gold
                        val pm = String.format("%.0f", handsOffPointsMultiplier)
                        canvas.drawText("HANDS OFF: points ×$pm   ·   touch to slow", cx, playBottom - dp(60f), smallText)
                    }
                    postInvalidateOnAnimation()
                }
            }
            State.AD -> drawAd(canvas)
            State.RUN_OVER -> drawRunOver(canvas)
            State.PRO -> drawPro(canvas)
            State.PRACTICE_PICK -> drawPracticePick(canvas)
            State.THEMES -> drawThemes(canvas)
            State.STATS -> drawStats(canvas)
        }

        if (state != State.PLAYING && !isFullScreen()) drawResetButton(canvas)
        if (isPro && !isFullScreen()) {
            tinyText.color = gold
            canvas.drawText("★ PRO", resetRect.left - dp(26f), resetRect.centerY() + dp(4f), tinyText)
        }
        if (state == State.READY) drawMenu(canvas)
        if (safeButtonEnabled && state == State.READY) drawSafeButton(canvas)     // DEBUG
        if (showAdPlaceholder && !isPro && !isFullScreen()) drawAdPlaceholder(canvas)    // DEBUG
    }

    /** Strikes used show as ✕, strikes still available as ○. */
    private fun triesDots() = (1..strikesPerGame).joinToString(" ") { if (it <= strikesPerGame - triesLeft) "✕" else "○" }

    /** Fills toward par, then keeps going. The full bar is a perfect run. */
    private fun drawProgress(canvas: Canvas, score: Int) {
        val left = dp(32f)
        val right = width - dp(32f)
        val y = dp(156f)
        val h = dp(8f)
        val full = max(1, maxOf(perfect, score, par, levelBest)).toFloat()
        fun xOf(v: Int) = left + (right - left) * (v / full).coerceIn(0f, 1f)

        barRect.set(left, y, right, y + h)
        canvas.drawRoundRect(barRect, h / 2, h / 2, barBg)
        if (score > 0) {
            barFill.color = if (score >= par) innerSmall else accent   // mint once you pass par
            barRect.set(left, y, max(left + h, xOf(score)), y + h)
            canvas.drawRoundRect(barRect, h / 2, h / 2, barFill)
        }
        // Par mark
        val px = xOf(par)
        tickPaint.color = Color.WHITE
        canvas.drawLine(px, y - dp(5f), px, y + h + dp(5f), tickPaint)
        tinyText.color = Color.WHITE
        canvas.drawText("PAR", px, y - dp(8f), tinyText)
        // Your best on this level
        if (levelBest > 0) {
            val bx = xOf(levelBest)
            tickPaint.color = gold
            canvas.drawLine(bx, y - dp(5f), bx, y + h + dp(5f), tickPaint)
            tinyText.color = gold
            canvas.drawText("BEST", bx, y + h + dp(17f), tinyText)
        }
    }

    private fun drawReady(canvas: Canvas) {
        // Kept short: the top display already shows the level and par.
        val cx = width / 2f
        var y = playBottom - dp(58f)
        val mult = String.format("%.2f", scoreMult)
        val bestText = if (levelBest > 0) "Best $levelBest" else "New level"
        smallText.color = Color.WHITE
        canvas.drawText("${shape.label}  ·  Score ×$mult  ·  $bestText", cx, y, smallText)
        y -= dp(24f)
        smallText.color = innerSmall
        val pct = (parPercent * 100).toInt()
        val mercyText = if (mercy < 1f) "  (−${((1f - mercy) * 100).toInt()}% mercy)" else ""
        canvas.drawText("Par = $pct% of a perfect run$mercyText", cx, y, smallText)
        y -= dp(24f)
        val note = when (playedLevel) {
            ovalAtLevel -> "New shape: ovals!"
            spinAtLevel -> "New: shapes now spin!"
            squareAtLevel -> "New shape: squares!"
            triangleAtLevel -> "New shape: triangles!"
            starAtLevel -> "New shape: stars!"
            wobbleStartsAtLevel -> "New: the ring wobbles!"
            else -> null
        }
        // How-to hint only on the first couple of levels
        val hint = note ?: if (playedLevel <= 2 && !practice) "Touch anywhere and drag to move the circle" else null
        if (hint != null) {
            smallText.color = if (note != null) warnColor else muted
            canvas.drawText(hint, cx, y, smallText)
        }
    }

    private fun drawGameOver(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), overlayPaint)
        val cx = width / 2f
        var y = height / 2f - dp(110f)
        bigText.color = dangerColor
        canvas.drawText("Crashed!", cx, y, bigText)
        y += dp(46f)
        smallText.color = muted
        canvas.drawText(String.format("Time %.2f s   ·   Moved %d", elapsed, movedDp.toInt()), cx, y, smallText)
        y += dp(56f)
        bigText.color = if (newLevelBest) gold else accent
        canvas.drawText("$lastScore", cx, y, bigText)
        y += dp(30f)
        smallText.color = muted
        val prevLabel = if (newLevelBest) "Previous level best" else "Level best"
        canvas.drawText("$prevLabel $levelBest", cx, y, smallText)
        y += dp(34f)
        if (practice) {
            val pctOfPar = if (par > 0) lastScore * 100 / par else 0
            smallText.color = if (cleared) innerSmall else warnColor
            val msg = if (cleared) "Made par! ($pctOfPar%)  ·  Practice" else "$pctOfPar% of par  ·  Practice, no strike"
            canvas.drawText(msg, cx, y, smallText)
        } else when {
            cleared -> {
                smallText.color = innerSmall
                canvas.drawText("Level cleared! On to Level ${playedLevel + 1}", cx, y, smallText)
                if (earnsStrike) {
                    y += dp(28f)
                    midText.color = innerSmall
                    canvas.drawText("$roundsToEarnStrike in a row: strike erased!", cx, y, midText)
                }
            }
            outOfTries -> {
                midText.color = dangerColor
                canvas.drawText("Strike $strikesPerGame — you're out!", cx, y, midText)
            }
            else -> {
                smallText.color = warnColor
                val pctOfPar = if (par > 0) lastScore * 100 / par else 0
                val left = triesLeft - 1
                val word = if (left == 1) "strike" else "strikes"
                canvas.drawText("Strike!  $pctOfPar% of par  ·  $left $word left", cx, y, smallText)
            }
        }
        // Blinking gold banner for a new best
        if (newHighScore || newLevelBest) {
            y += dp(34f)
            val on = (System.currentTimeMillis() / 450) % 2 == 0L
            if (on) {
                midText.color = gold
                val msg = if (newHighScore) "★ NEW HIGH SCORE! ★" else "★ NEW LEVEL BEST! ★"
                canvas.drawText(msg, cx, y, midText)
            }
            postInvalidateOnAnimation()
        }
        y += dp(36f)
        if (reviveAvailable()) {
            reviveRect.set(cx - dp(130f), y, cx + dp(130f), y + dp(52f))
            canvas.drawRoundRect(reviveRect, dp(26f), dp(26f), reviveFill)
            val ty = reviveRect.centerY() - (reviveText.descent() + reviveText.ascent()) / 2f
            reviveText.color = Color.rgb(40, 25, 0)
            canvas.drawText(if (isPro) "▶  Revive (Pro)" else "▶  Watch ad to revive", cx, ty, reviveText)
            y += dp(84f)
            smallText.color = Color.WHITE
            canvas.drawText("Tap anywhere else to continue", cx, y, smallText)
        } else {
            reviveRect.setEmpty()
            y += dp(30f)
            smallText.color = Color.WHITE
            canvas.drawText("Tap to continue", cx, y, smallText)
        }
    }

    private fun drawRunOver(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), overlayPaint)
        val cx = width / 2f
        var y = height / 2f - dp(100f)
        bigText.color = dangerColor
        canvas.drawText("GAME OVER", cx, y, bigText)
        y += dp(42f)
        smallText.color = muted
        canvas.drawText("Struck out on Level $playedLevel", cx, y, smallText)
        y += dp(60f)
        bigText.color = if (newBest) gold else accent
        canvas.drawText("$runScore", cx, y, bigText)
        y += dp(30f)
        if (newBest) {
            midText.color = gold
            canvas.drawText("NEW HIGH SCORE!", cx, y, midText)
        } else {
            smallText.color = muted
            canvas.drawText("High score $best  ·  Level $bestLevel", cx, y, smallText)
        }
        y += dp(70f)
        checkpointLevels = checkpoints()
        if (checkpointLevels.size > 1) {
            // Pro: choose where the new game starts
            smallText.color = Color.WHITE
            canvas.drawText("Start new game at:", cx, y, smallText)
            y += dp(18f)
            val n = checkpointLevels.size
            val gap = dp(10f)
            val bw = min(dp(110f), (width - dp(32f) - gap * (n - 1)) / n)
            var x = cx - (bw * n + gap * (n - 1)) / 2f
            for (i in 0 until n) {
                checkpointRects[i].set(x, y, x + bw, y + dp(46f))
                drawProButton(canvas, checkpointRects[i], "Lv ${checkpointLevels[i]}",
                    if (i == 0) Color.argb(90, 255, 255, 255) else gold,
                    if (i == 0) Color.WHITE else Color.rgb(40, 25, 0))
                x += bw + gap
            }
        } else {
            smallText.color = Color.WHITE
            canvas.drawText("Tap to start a new game", cx, y, smallText)
        }
    }

    /** Pro checkpoints: level 1 plus the three highest of 11, 21, 31... that you've reached. */
    private fun checkpoints(): List<Int> {
        if (!isPro) return listOf(1)
        val reached = mutableListOf<Int>()
        var c = checkpointEvery + 1
        while (c <= maxLevel) {
            reached.add(c)
            c += checkpointEvery
        }
        return listOf(1) + reached.takeLast(3)
    }

    // Placeholder for a real rewarded video ad (to be replaced with Google AdMob later).
    private fun drawAd(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), adBg)
        val cx = width / 2f
        val cy = height / 2f
        midText.color = Color.rgb(50, 50, 70)
        canvas.drawText("ADVERTISEMENT", cx, cy - dp(40f), midText)
        smallText.color = Color.rgb(120, 120, 140)
        canvas.drawText("Fake ad for testing", cx, cy - dp(10f), smallText)
        val secs = ((adEndsAt - System.currentTimeMillis() + 999) / 1000).toInt()
        if (secs > 0) {
            bigText.color = Color.rgb(50, 50, 70)
            canvas.drawText("$secs", cx, cy + dp(60f), bigText)
            postInvalidateOnAnimation()
        } else {
            midText.color = Color.rgb(20, 140, 80)
            canvas.drawText("Tap to revive", cx, cy + dp(55f), midText)
        }
    }

    // DEBUG: remove before release
    private fun drawResetButton(canvas: Canvas) {
        canvas.drawRoundRect(resetRect, dp(8f), dp(8f), resetFill)
        val ty = resetRect.centerY() - (resetText.descent() + resetText.ascent()) / 2f
        canvas.drawText("RESET", resetRect.centerX(), ty, resetText)
    }

    // ---------------- Reset confirmation ----------------

    private fun confirmReset() {
        android.app.AlertDialog.Builder(context)
            .setTitle("Reset all scores?")
            .setMessage("This erases your high score, every level best and the game in progress. It can't be undone.")
            .setPositiveButton("Reset") { _, _ -> resetAll() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------------- Pro upsell (shown on Back) ----------------

    private fun drawProButton(canvas: Canvas, r: RectF, label: String, fill: Int, textColor: Int) {
        barFill.color = fill
        canvas.drawRoundRect(r, r.height() / 2, r.height() / 2, barFill)
        reviveText.color = textColor
        val ty = r.centerY() - (reviveText.descent() + reviveText.ascent()) / 2f
        canvas.drawText(label, r.centerX(), ty, reviveText)
    }

    private fun drawPro(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), overlayPaint)
        val cx = width / 2f
        var y = height * 0.18f
        bigText.color = gold
        canvas.drawText("INNER CIRCLE", cx, y, bigText)
        y += dp(40f)
        midText.color = gold
        canvas.drawText("★  P R O  ★", cx, y, midText)

        y += dp(52f)
        smallText.color = Color.WHITE
        val perks = listOf(
            "No ads, ever",
            "Free revive every game (no video)",
            "Practice any level you've reached",
            "Start new games from your checkpoint",
            "Exclusive ring & color themes",
            "Detailed stats and score history"
        )
        for (p in perks) {
            canvas.drawText("✓  $p", cx, y, smallText)
            y += dp(30f)
        }

        y += dp(18f)
        proUpgradeRect.set(cx - dp(140f), y, cx + dp(140f), y + dp(56f))
        drawProButton(canvas, proUpgradeRect, "Upgrade to Pro", gold, Color.rgb(40, 25, 0))

        y += dp(90f)
        val waitLeft = proWaitSeconds * 1000L - (System.currentTimeMillis() - proShownAt)
        if (waitLeft > 0) {
            proStayRect.setEmpty()
            proExitRect.setEmpty()
            smallText.color = muted
            canvas.drawText("Exit in ${(waitLeft + 999) / 1000}…", cx, y + dp(28f), smallText)
            postInvalidateOnAnimation()
        } else {
            if (proFromMenu) {
                proStayRect.set(cx - dp(90f), y, cx + dp(90f), y + dp(48f))
                proExitRect.setEmpty()
                drawProButton(canvas, proStayRect, "Not now", Color.argb(90, 255, 255, 255), Color.WHITE)
            } else {
                proStayRect.set(cx - dp(150f), y, cx - dp(10f), y + dp(48f))
                proExitRect.set(cx + dp(10f), y, cx + dp(150f), y + dp(48f))
                drawProButton(canvas, proStayRect, "Keep playing", innerSmall, Color.rgb(10, 40, 25))
                drawProButton(canvas, proExitRect, "Exit", Color.argb(90, 255, 255, 255), Color.WHITE)
            }
        }
    }

    private fun handleProTouch(x: Float, y: Float) {
        when {
            proUpgradeRect.contains(x, y) ->
                // Placeholder until Google Play Billing is added
                android.widget.Toast.makeText(context, "Pro is coming soon!", android.widget.Toast.LENGTH_SHORT).show()
            proStayRect.contains(x, y) -> {
                proFromMenu = false
                state = stateBeforePro
                invalidate()
            }
            proExitRect.contains(x, y) -> (context as? android.app.Activity)?.finish()
        }
    }

    // ---------------- Pro menu: practice, themes, stats ----------------

    private fun isFullScreen() = state == State.AD || state == State.PRO ||
        state == State.PRACTICE_PICK || state == State.THEMES || state == State.STATS

    private fun drawSmallButton(canvas: Canvas, r: RectF, label: String, fill: Int) {
        barFill.color = fill
        canvas.drawRoundRect(r, dp(10f), dp(10f), barFill)
        val ty = r.centerY() - (resetText.descent() + resetText.ascent()) / 2f
        canvas.drawText(label, r.centerX(), ty, resetText)
    }

    /** Row of menu buttons at the bottom of the ready screen. */
    private fun drawMenu(canvas: Canvas) {
        val fill = Color.argb(200, 40, 60, 110)
        val top = playBottom - dp(44f)
        val cx = width / 2f
        if (practice) {
            endPracticeRect.set(cx - dp(80f), top, cx + dp(80f), top + dp(32f))
            drawSmallButton(canvas, endPracticeRect, "END PRACTICE", fill)
            return
        }
        val bw = dp(100f)
        val gap = dp(10f)
        var x = cx - (bw * 3 + gap * 2) / 2f
        for (i in 0 until 3) {
            menuRects[i].set(x, top, x + bw, top + dp(32f))
            drawSmallButton(canvas, menuRects[i], if (isPro) menuLabels[i] else "★ " + menuLabels[i], fill)
            x += bw + gap
        }
    }

    private fun openMenu(i: Int) {
        if (!isPro) {
            // Not Pro yet: show what Pro includes
            proFromMenu = true
            stateBeforePro = state
            state = State.PRO
            proShownAt = 0L               // no wait when the player opened it on purpose
        } else {
            state = when (i) {
                0 -> {
                    pickLevel = level.coerceIn(1, max(1, maxLevel))
                    State.PRACTICE_PICK
                }
                1 -> State.THEMES
                else -> State.STATS
            }
        }
        invalidate()
    }

    /** Dark backdrop, a gold title and a Back button; returns the title's baseline. */
    private fun drawScreenBase(canvas: Canvas, title: String): Float {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), overlayPaint)
        val y = height * 0.12f
        bigText.color = gold
        canvas.drawText(title, width / 2f, y, bigText)
        backRect.set(width / 2f - dp(90f), height - dp(84f), width / 2f + dp(90f), height - dp(38f))
        drawProButton(canvas, backRect, "Back", Color.argb(90, 255, 255, 255), Color.WHITE)
        return y
    }

    private fun shapesAt(lv: Int): String {
        val l = mutableListOf("Circle")
        if (lv >= ovalAtLevel) l.add("Oval")
        if (lv >= squareAtLevel) l.add("Square")
        if (lv >= triangleAtLevel) l.add("Triangle")
        if (lv >= starAtLevel) l.add("Star")
        return l.joinToString(", ")
    }

    // ---- Practice ----

    private fun drawPracticePick(canvas: Canvas) {
        var y = drawScreenBase(canvas, "PRACTICE")
        val cx = width / 2f
        y += dp(34f)
        smallText.color = muted
        canvas.drawText("No strikes. Scores don't count.", cx, y, smallText)
        y += dp(70f)
        smallText.color = Color.WHITE
        canvas.drawText("Level", cx, y, smallText)
        y += dp(48f)
        bigText.color = Color.WHITE
        canvas.drawText("$pickLevel", cx, y, bigText)
        y += dp(30f)
        smallText.color = muted
        canvas.drawText(shapesAt(pickLevel), cx, y, smallText)
        y += dp(28f)
        val bw = dp(64f)
        val gap = dp(10f)
        var x = cx - (bw * 4 + gap * 3) / 2f
        for (i in 0 until 4) {
            pickRects[i].set(x, y, x + bw, y + dp(48f))
            val d = pickDeltas[i]
            drawProButton(canvas, pickRects[i], if (d > 0) "+$d" else "$d", Color.argb(200, 40, 60, 110), Color.WHITE)
            x += bw + gap
        }
        y += dp(76f)
        pickPlayRect.set(cx - dp(120f), y, cx + dp(120f), y + dp(56f))
        drawProButton(canvas, pickPlayRect, "Play Level $pickLevel", gold, Color.rgb(40, 25, 0))
        smallText.color = muted
        canvas.drawText("Reached so far: Level ${max(1, maxLevel)}", cx, pickPlayRect.bottom + dp(34f), smallText)
    }

    private fun handlePickTouch(x: Float, y: Float) {
        for (i in 0 until 4) {
            if (pickRects[i].contains(x, y)) {
                pickLevel = (pickLevel + pickDeltas[i]).coerceIn(1, max(1, maxLevel))
                invalidate()
                return
            }
        }
        when {
            pickPlayRect.contains(x, y) -> {
                practice = true
                practiceLevel = pickLevel
                lastShape = null
                newRound()
            }
            backRect.contains(x, y) -> {
                state = State.READY
                invalidate()
            }
        }
    }

    // ---- Themes ----

    private fun drawThemes(canvas: Canvas) {
        var y = drawScreenBase(canvas, "THEMES") + dp(30f)
        val cx = width / 2f
        for (i in themes.indices) {
            val t = themes[i]
            val r = themeRects[i]
            r.set(cx - dp(140f), y, cx + dp(140f), y + dp(60f))
            barFill.color = t.bgCenter
            canvas.drawRoundRect(r, dp(14f), dp(14f), barFill)
            // Mini preview: the ring and the inner circle in this theme's colors
            ringPaint.color = t.safe
            canvas.drawCircle(r.left + dp(34f), r.centerY(), dp(20f), ringPaint)
            innerPaint.shader = null
            innerPaint.color = t.innerSmall
            canvas.drawCircle(r.left + dp(30f), r.centerY() + dp(4f), dp(8f), innerPaint)
            cornerText.color = Color.WHITE
            canvas.drawText(t.name, r.left + dp(70f), r.centerY() + dp(5f), cornerText)
            if (i == themeIndex) {
                rightText.color = gold
                canvas.drawText("✓ In use", r.right - dp(16f), r.centerY() + dp(5f), rightText)
            }
            y += dp(70f)
        }
    }

    private fun handleThemeTouch(x: Float, y: Float) {
        for (i in themes.indices) {
            if (themeRects[i].contains(x, y)) {
                themeIndex = i
                prefs.edit().putInt("theme", i).apply()
                applyTheme()
                return
            }
        }
        if (backRect.contains(x, y)) {
            state = State.READY
            invalidate()
        }
    }

    // ---- Stats ----

    private fun statInc(ed: android.content.SharedPreferences.Editor, key: String, by: Int = 1) {
        ed.putInt(key, prefs.getInt(key, 0) + by)
    }

    private fun historyList(): List<Int> =
        (prefs.getString("st_history", "") ?: "").split(",").mapNotNull { it.toIntOrNull() }

    private fun formatTime(sec: Int): String {
        val h = sec / 3600
        val m = (sec % 3600) / 60
        return if (h > 0) "${h}h ${m}m" else "${m}m ${sec % 60}s"
    }

    private fun drawStats(canvas: Canvas) {
        var y = drawScreenBase(canvas, "STATS") + dp(34f)
        val left = dp(28f)
        val right = width - dp(28f)
        val rounds = prefs.getInt("st_rounds", 0)
        val rows = listOf(
            "Games played" to "${prefs.getInt("st_games", 0)}",
            "Rounds played" to "$rounds",
            "Levels cleared" to "${prefs.getInt("st_cleared", 0)}",
            "Highest level reached" to "${max(1, maxLevel)}",
            "Average score vs par" to (if (rounds > 0) "${prefs.getInt("st_pctSum", 0) / rounds}%" else "—"),
            "Longest clear streak" to "${prefs.getInt("st_bestRun", 0)}",
            "Time played" to formatTime(prefs.getInt("st_time", 0))
        )
        for ((k, v) in rows) {
            cornerText.color = muted
            canvas.drawText(k, left, y, cornerText)
            rightText.color = Color.WHITE
            canvas.drawText(v, right, y, rightText)
            y += dp(24f)
        }

        // Score history: one bar per finished game, oldest on the left
        y += dp(16f)
        val hist = historyList()
        cornerText.color = Color.WHITE
        canvas.drawText(if (hist.isEmpty()) "Game scores" else "Last ${hist.size} game scores", left, y, cornerText)
        y += dp(14f)
        val chartH = dp(70f)
        val base = y + chartH
        if (hist.isEmpty()) {
            smallText.color = muted
            canvas.drawText("Finish a game to start your history", width / 2f, y + chartH / 2f, smallText)
        } else {
            val maxV = max(1, hist.maxOrNull() ?: 1)
            val slot = (right - left) / 20f
            val barW = slot - dp(2f)                  // 2dp gap between bars
            val r = min(dp(3f), barW / 2f)
            val bestIdx = hist.indexOf(maxV)
            barFill.color = accent
            for ((i, v) in hist.withIndex()) {
                val h = max(dp(2f), chartH * v / maxV)
                val x = left + i * slot
                barRect.set(x, base - h, x + barW, base)
                canvas.drawRoundRect(barRect, r, r, barFill)
                canvas.drawRect(x, base - min(h, r), x + barW, base, barFill)   // square bottom on the baseline
            }
            // Label only the best game
            tinyText.color = Color.WHITE
            canvas.drawText("$maxV", left + bestIdx * slot + barW / 2f, base - chartH - dp(4f), tinyText)
            tickPaint.color = Color.argb(80, 255, 255, 255)
            canvas.drawLine(left, base, right, base, tickPaint)
        }
        y = base + dp(32f)

        // Which shapes cost the most strikes
        cornerText.color = Color.WHITE
        canvas.drawText("Missed par, by shape", left, y, cornerText)
        y += dp(24f)
        var any = false
        for (sh in Shape.values()) {
            val n = prefs.getInt("st_rounds_${sh.name}", 0)
            if (n == 0) continue
            any = true
            val miss = prefs.getInt("st_miss_${sh.name}", 0)
            cornerText.color = muted
            canvas.drawText(sh.label, left, y, cornerText)
            rightText.color = Color.WHITE
            canvas.drawText("${miss * 100 / n}%  of $n", right, y, rightText)
            y += dp(22f)
        }
        if (!any) {
            cornerText.color = muted
            canvas.drawText("No rounds yet", left, y, cornerText)
        }
    }

    // DEBUG: shows where the AdMob banner will go
    private fun drawAdPlaceholder(canvas: Canvas) {
        val top = playBottom + dp(5f)
        val bannerW = dp(320f)
        val left = (width - bannerW) / 2f
        barRect.set(left, top, left + bannerW, top + dp(50f))
        canvas.drawRect(barRect, safePaint)
        tinyText.color = muted
        canvas.drawText("AD BANNER SPACE (320 × 50)", barRect.centerX(), barRect.centerY() + dp(4f), tinyText)
    }

    // DEBUG: remove before release
    private fun drawSafeButton(canvas: Canvas) {
        canvas.drawRoundRect(safeRect, dp(8f), dp(8f), if (showSafe) safeFillOn else safeFill)
        val ty = safeRect.centerY() - (resetText.descent() + resetText.ascent()) / 2f
        canvas.drawText("SAFE", safeRect.centerX(), ty, resetText)
    }

    // DEBUG: remove before release
    private fun drawSafeSpot(canvas: Canvas) {
        val arm = dp(10f)
        if (state == State.READY) {
            // The searched result: the biggest circle that fits right now, and where it sits.
            canvas.drawCircle(safeX, safeY, max(0f, safeR), safePaint)
            canvas.drawLine(safeX - arm, safeY, safeX + arm, safeY, safePaint)
            canvas.drawLine(safeX, safeY - arm, safeX, safeY + arm, safePaint)
            val off = hypot(safeX - outerCx, safeY - outerCy) / density
            val fit = safeR / density
            canvas.drawText(
                String.format("Safest spot: %.1f dp from center  ·  fits radius %.0f dp", off, fit),
                width / 2f, playBottom - dp(170f), safeText
            )
            canvas.drawText("Perfect score this round: $perfect", width / 2f, playBottom - dp(150f), safeText)
        } else if (state == State.PLAYING) {
            // During play: follow the ring's center (where the search says the safe spot is).
            canvas.drawLine(outerCx - arm, outerCy, outerCx + arm, outerCy, safePaint)
            canvas.drawLine(outerCx, outerCy - arm, outerCx, outerCy + arm, safePaint)
        }
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
                if (state != State.PLAYING && !isFullScreen() &&
                    resetRect.contains(e.x, e.y)) {
                    // Tap = reset (with confirmation). Hold = toggle Pro test mode.
                    resetHeld = true
                    postDelayed(toggleProRunnable, longPressMs)
                    return true
                }
                // DEBUG: safe spot button toggles the overlay without starting the game
                if (safeButtonEnabled && state == State.READY && safeRect.contains(e.x, e.y)) {
                    showSafe = !showSafe
                    if (showSafe) findSafeSpot()
                    invalidate()
                    return true
                }
                when (state) {
                    State.READY -> {
                        val m = menuRects.indexOfFirst { it.contains(e.x, e.y) }
                        when {
                            practice && endPracticeRect.contains(e.x, e.y) -> {
                                practice = false
                                lastShape = null
                                newRound()
                            }
                            !practice && m >= 0 -> openMenu(m)
                            else -> {
                                startGame()
                                track(e, 0)
                            }
                        }
                    }
                    State.PLAYING -> {
                        if (paused) {
                            paused = false
                            sound.humVolume = humVolume
                            lastFrameNs = 0L
                            invalidate()
                        }
                        track(e, 0)
                    }
                    State.OVER -> {
                        if (reviveAvailable() && reviveRect.contains(e.x, e.y)) {
                            if (isPro) revive() else startAd()
                        }
                        else if (System.currentTimeMillis() - overAtMs > 700) commitRound()
                    }
                    State.AD -> if (System.currentTimeMillis() >= adEndsAt) revive()
                    State.RUN_OVER -> if (System.currentTimeMillis() - overAtMs > 700) {
                        if (checkpointLevels.size > 1) {
                            val i = checkpointRects.indices.firstOrNull {
                                it < checkpointLevels.size && checkpointRects[it].contains(e.x, e.y)
                            }
                            if (i != null) newRun(checkpointLevels[i])
                        } else {
                            newRun()
                        }
                    }
                    State.PRO -> handleProTouch(e.x, e.y)
                    State.PRACTICE_PICK -> handlePickTouch(e.x, e.y)
                    State.THEMES -> handleThemeTouch(e.x, e.y)
                    State.STATS -> if (backRect.contains(e.x, e.y)) {
                        state = State.READY
                        invalidate()
                    }
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
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                fingerDown = false
                if (resetHeld) {
                    // Let go before the long press fired: it was a tap
                    resetHeld = false
                    removeCallbacks(toggleProRunnable)
                    if (e.actionMasked == MotionEvent.ACTION_UP) confirmReset()
                }
            }
        }
        return true
    }
}
