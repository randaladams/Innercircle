package com.adamselite.innercircle

import android.app.Activity
import android.view.View
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform

/**
 * Google AdMob: the banner in the bottom strip and the rewarded video for the revive.
 * Before any ad is requested, Google's consent form (UMP) is shown to players who need it (EU/UK etc.).
 *
 * The ad IDs live in res/values/ads.xml. They are Google's TEST IDs until you replace them
 * with your own from the AdMob console.
 */
class Ads(private val activity: Activity) {

    val banner: AdView = AdView(activity).apply {
        adUnitId = activity.getString(R.string.admob_banner_unit_id)
        setAdSize(AdSize.BANNER)          // 320 x 50, fits the 60dp strip the game keeps free
    }

    /** Called with true when the player must be offered a way to change their privacy choices. */
    var onPrivacyRequirementKnown: ((Boolean) -> Unit)? = null

    private val consent: ConsentInformation = UserMessagingPlatform.getConsentInformation(activity)
    private var started = false
    private var bannerWanted = true
    private var rewarded: RewardedAd? = null
    private var loadingRewarded = false

    fun start() {
        consent.requestConsentInfoUpdate(
            activity,
            ConsentRequestParameters.Builder().build(),
            {
                reportPrivacyRequirement()
                UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { _ ->
                    reportPrivacyRequirement()
                    if (consent.canRequestAds()) initAds()
                }
            },
            { _ -> if (consent.canRequestAds()) initAds() }
        )
        // Consent given on an earlier launch: ads can start right away.
        if (consent.canRequestAds()) initAds()
    }

    private fun reportPrivacyRequirement() {
        onPrivacyRequirementKnown?.invoke(
            consent.privacyOptionsRequirementStatus ==
                ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED
        )
    }

    fun showPrivacyOptions() {
        UserMessagingPlatform.showPrivacyOptionsForm(activity) { _ -> reportPrivacyRequirement() }
    }

    private fun initAds() {
        if (started) return
        started = true
        // Google recommends initializing on a background thread.
        Thread {
            MobileAds.initialize(activity) { }
            activity.runOnUiThread {
                if (bannerWanted) banner.loadAd(AdRequest.Builder().build())
                loadRewarded()
            }
        }.start()
    }

    /** Pro players see no banner. */
    fun setBannerVisible(show: Boolean) {
        bannerWanted = show
        banner.visibility = if (show) View.VISIBLE else View.GONE
        if (show && started) banner.loadAd(AdRequest.Builder().build())
    }

    private fun loadRewarded() {
        if (!started || rewarded != null || loadingRewarded) return
        loadingRewarded = true
        RewardedAd.load(
            activity,
            activity.getString(R.string.admob_rewarded_unit_id),
            AdRequest.Builder().build(),
            object : RewardedAdLoadCallback() {
                override fun onAdLoaded(ad: RewardedAd) {
                    rewarded = ad
                    loadingRewarded = false
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    rewarded = null
                    loadingRewarded = false
                }
            }
        )
    }

    /**
     * Plays the rewarded video and calls [onRewarded] if the player watched it.
     * If no video is available (offline, no ad to show), the revive is given anyway
     * so players aren't punished for a missing ad.
     */
    fun showRewarded(onRewarded: () -> Unit) {
        val ad = rewarded
        if (ad == null) {
            onRewarded()
            loadRewarded()
            return
        }
        var earned = false
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                rewarded = null
                loadRewarded()
                if (earned) onRewarded()
            }

            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                rewarded = null
                loadRewarded()
                onRewarded()
            }
        }
        ad.show(activity) { earned = true }
    }

    fun pause() = banner.pause()
    fun resume() = banner.resume()
    fun destroy() = banner.destroy()
}
