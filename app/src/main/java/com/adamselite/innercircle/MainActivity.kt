package com.adamselite.innercircle

import android.app.Activity
import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.window.OnBackInvokedDispatcher

class MainActivity : Activity() {
    private lateinit var game: GameView
    private lateinit var ads: Ads
    private lateinit var billing: Billing

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        game = GameView(this)
        ads = Ads(this)

        // Android 15+ draws apps edge-to-edge (behind the status and navigation bars).
        // Pad the game away from the bars and the camera cutout so nothing hides under them.
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(game, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            // AdMob banner in the strip the game keeps free at the bottom
            addView(ads.banner, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
        }
        root.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            }
            insets
        }
        setContentView(root)

        // Ads: no banner for Pro, rewarded video for the revive, privacy button where required
        ads.setBannerVisible(!game.proActive)
        game.onProChanged = { pro -> ads.setBannerVisible(!pro) }
        game.rewardedAdHandler = { onRewarded -> ads.showRewarded(onRewarded) }
        game.onPrivacyClicked = { ads.showPrivacyOptions() }
        ads.onPrivacyRequirementKnown = { required -> runOnUiThread { game.privacyOptionsRequired = required } }
        ads.start()

        // Billing: Google Play decides who owns Pro. In debug builds the RESET long-press test
        // switch stays usable, so Play only ever turns Pro on there.
        val debuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        billing = Billing(this) { owned -> if (owned || !debuggable) game.setProOwned(owned) }
        game.onUpgradeClicked = { billing.purchasePro() }
        billing.start()

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
        ads.resume()
        billing.refresh()
    }

    override fun onPause() {
        game.pauseAudio()
        ads.pause()
        super.onPause()
    }

    override fun onDestroy() {
        ads.destroy()
        billing.end()
        super.onDestroy()
    }

    // Android 12 and older still deliver Back here.
    @Suppress("DEPRECATION", "MissingSuperCall")
    @Deprecated("Replaced by OnBackInvokedCallback on Android 13+")
    override fun onBackPressed() {
        handleBack()
    }
}
