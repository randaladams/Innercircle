package com.adamselite.innercircle

import android.app.Activity
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
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

        // Android 15+ always draws apps edge to edge: behind the status bar, the camera cutout and
        // the navigation bar. Give the game margins equal to those areas so nothing ends up under them.
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        root.addView(
            game,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            root.setOnApplyWindowInsetsListener { _, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                val lp = game.layoutParams as FrameLayout.LayoutParams
                if (lp.leftMargin != bars.left || lp.topMargin != bars.top ||
                    lp.rightMargin != bars.right || lp.bottomMargin != bars.bottom
                ) {
                    lp.setMargins(bars.left, bars.top, bars.right, bars.bottom)
                    game.layoutParams = lp
                }
                insets
            }
        }
        setContentView(root)

        // Back button / gesture. On Android 13+ it arrives through this callback; apps targeting
        // Android 16 no longer get onBackPressed() at all. The game decides whether Back pauses,
        // shows the Pro screen, or closes the app.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) {
                if (!game.onBackRequested()) finish()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        game.resumeAudio()
    }

    override fun onPause() {
        game.pauseAudio()
        super.onPause()
    }

    // Back on Android 12 and older.
    @Deprecated("Replaced by OnBackInvokedCallback on Android 13+")
    @Suppress("DEPRECATION", "MissingSuperCall")
    override fun onBackPressed() {
        if (!game.onBackRequested()) finish()
    }
}
