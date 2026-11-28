package com.example.bpm_player

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.FrameLayout
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.MobileAds
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import java.util.concurrent.atomic.AtomicBoolean

/** Banner AdMob com consentimento UMP (GDPR/EUA). Plano: docs/MONETIZACAO.md */
object Ads {

    const val PRIVACY_URL = "https://netstart.github.io/beattrack/privacy-policy.html"

    fun setup(activity: Activity, container: FrameLayout, toolbar: MaterialToolbar) {
        // Play exige o link da política também dentro do app
        toolbar.menu.add(R.string.privacy_policy).setOnMenuItemClickListener {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PRIVACY_URL)))
            true
        }
        val started = AtomicBoolean(false)
        val consent = UserMessagingPlatform.getConsentInformation(activity)
        val start = {
            if (consent.canRequestAds() && started.compareAndSet(false, true)) {
                Thread {
                    MobileAds.initialize(activity) {} // fora da main thread, como o Google recomenda
                    activity.runOnUiThread { if (!activity.isDestroyed) loadBanner(activity, container) }
                }.start()
            }
        }
        consent.requestConsentInfoUpdate(activity, ConsentRequestParameters.Builder().build(), {
            UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) {
                if (consent.privacyOptionsRequirementStatus == ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED) {
                    toolbar.menu.add(R.string.ads_privacy).setOnMenuItemClickListener {
                        UserMessagingPlatform.showPrivacyOptionsForm(activity) {}
                        true
                    }
                }
                start()
            }
        }, { start() }) // falha de rede: usa o consentimento da sessão anterior, se houver
        start() // consentimento já dado antes: não espera a atualização
    }

    private fun loadBanner(activity: Activity, container: FrameLayout) {
        val metrics = activity.resources.displayMetrics
        // banner na largura útil da área que rola (descontado o padding lateral da tela)
        val parent = container.parent as? android.view.ViewGroup
        val px = parent?.let { it.width - it.paddingLeft - it.paddingRight }?.takeIf { it > 0 } ?: metrics.widthPixels
        val adView = AdView(activity).apply {
            adUnitId = BuildConfig.ADMOB_BANNER_ID
            setAdSize(AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(activity, (px / metrics.density).toInt()))
            adListener = object : AdListener() {
                override fun onAdLoaded() { container.visibility = View.VISIBLE }
            }
        }
        container.addView(adView)
        adView.loadAd(AdRequest.Builder().build())
    }
}
