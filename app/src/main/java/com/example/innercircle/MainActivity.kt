package com.example.innercircle

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
}
