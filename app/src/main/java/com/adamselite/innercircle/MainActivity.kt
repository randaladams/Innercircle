package com.adamselite.innercircle

import android.app.Activity
import android.os.Bundle
import android.view.WindowManager

class MainActivity : Activity() {
    private lateinit var game: GameView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        game = GameView(this)
        setContentView(game)
    }

    override fun onResume() {
        super.onResume()
        game.resumeAudio()
    }

    override fun onPause() {
        game.pauseAudio()
        super.onPause()
    }

    // Back shows the Pro screen first; the game tells us when it's OK to actually close.
    @Suppress("DEPRECATION", "MissingSuperCall")
    override fun onBackPressed() {
        if (!game.onBackRequested()) finish()
    }
}
