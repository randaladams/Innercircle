package com.adamselite.innercircle

import android.app.Activity
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.window.OnBackInvokedDispatcher

class MainActivity : Activity() {
    private lateinit var game: GameView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        game = GameView(this)

        // Android 15+ draws apps edge-to-edge (behind the status and navigation bars).
        // Pad the game away from the bars and the camera cutout so nothing hides under them.
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(game, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
        root.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            }
            insets
        }
        setContentView(root)

        // Back button: Android 13+ uses this callback (and Android 16 no longer calls onBackPressed).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT
            ) { handleBack() }
        }
    }

    // Back shows the Pro screen first; the game tells us when it's OK to actually close.
    private fun handleBack() {
        if (!game.onBackRequested()) finish()
    }

    override fun onResume() {
        super.onResume()
        game.resumeAudio()
    }

    override fun onPause() {
        game.pauseAudio()
        super.onPause()
    }

    // Android 12 and older still deliver Back here.
    @Suppress("DEPRECATION", "MissingSuperCall")
    @Deprecated("Replaced by OnBackInvokedCallback on Android 13+")
    override fun onBackPressed() {
        handleBack()
    }
}
