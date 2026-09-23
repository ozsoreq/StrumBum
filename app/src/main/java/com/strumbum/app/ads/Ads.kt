package com.strumbum.app.ads

import android.app.Activity
import android.content.Context
import android.util.Log
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.MobileAds
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import com.strumbum.app.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Consent (Google UMP) and AdMob start-up.
 *
 * Placement rules from the spec: no ads on the tuner screen or during onboarding.
 * Banners appear only on secondary screens. Interstitials and rewarded ads arrive
 * with Tune-All and the rewarded unlocks in Phase 2.
 */
object Ads {
    private const val TAG = "Ads"
    private val sdkStarted = AtomicBoolean(false)

    private val _canShowAds = MutableStateFlow(false)
    val canShowAds: StateFlow<Boolean> = _canShowAds.asStateFlow()

    private val _privacyOptionsRequired = MutableStateFlow(false)
    val privacyOptionsRequired: StateFlow<Boolean> = _privacyOptionsRequired.asStateFlow()

    /** Ask for consent where the law requires it (EU/UK etc.), then start the Mobile Ads SDK. */
    fun gatherConsent(activity: Activity) {
        val info = UserMessagingPlatform.getConsentInformation(activity)
        info.requestConsentInfoUpdate(
            activity,
            ConsentRequestParameters.Builder().build(),
            {
                UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { error ->
                    if (error != null) Log.w(TAG, "consent form: ${error.message}")
                    onConsentKnown(activity, info)
                }
            },
            { error ->
                Log.w(TAG, "consent update: ${error.message}")
                onConsentKnown(activity, info)
            },
        )
        // Consent from a previous session is valid immediately; don't wait for the network.
        if (info.canRequestAds()) onConsentKnown(activity, info)
    }

    fun showPrivacyOptions(activity: Activity) {
        UserMessagingPlatform.showPrivacyOptionsForm(activity) { error ->
            if (error != null) Log.w(TAG, "privacy options: ${error.message}")
            onConsentKnown(activity, UserMessagingPlatform.getConsentInformation(activity))
        }
    }

    private fun onConsentKnown(context: Context, info: ConsentInformation) {
        _privacyOptionsRequired.value =
            info.privacyOptionsRequirementStatus == ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED
        if (!info.canRequestAds()) {
            _canShowAds.value = false
            return
        }
        if (sdkStarted.compareAndSet(false, true)) {
            val app = context.applicationContext
            // MobileAds.initialize does disk work; keep it off the main thread.
            thread(name = "strumbum-ads-init", isDaemon = true) {
                MobileAds.initialize(app) { _canShowAds.value = true }
            }
        } else {
            _canShowAds.value = true
        }
    }
}

/** Anchored adaptive banner. Renders nothing until consent allows ads. */
@Composable
fun BannerAd(modifier: Modifier = Modifier) {
    val canShow by Ads.canShowAds.collectAsState()
    if (!canShow) return
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val widthDp = maxWidth.value.toInt()
        key(widthDp) {
            AndroidView(
                modifier = Modifier.fillMaxWidth(),
                factory = { ctx ->
                    AdView(ctx).apply {
                        adUnitId = BuildConfig.ADMOB_BANNER_ID
                        setAdSize(AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(ctx, widthDp))
                        loadAd(AdRequest.Builder().build())
                    }
                },
                onRelease = { it.destroy() },
            )
        }
    }
}
