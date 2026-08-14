package com.milen.grounpringtonesetter.customviews.ui.ads

import android.app.Activity
import android.app.Application
import android.os.Build
import com.google.android.gms.ads.AdInspectorError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.RequestConfiguration
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.FormError
import com.google.android.ump.UserMessagingPlatform
import com.milen.grounpringtonesetter.BuildConfig
import com.milen.grounpringtonesetter.utils.Telemetry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.atomic.AtomicBoolean

internal class AdsManager(
    private val application: Application,
    private val tracker: Telemetry,
) : AdsGateway {
    private val consentInformation: ConsentInformation =
        UserMessagingPlatform.getConsentInformation(application)
    private val hasRequestedConsentThisProcess = AtomicBoolean(false)
    private val hasStartedMobileAdsInitialization = AtomicBoolean(false)
    override val interstitialAdPolicy = InterstitialAdPolicy()

    private val _canRequestAds = MutableStateFlow(false)
    val canRequestAds: StateFlow<Boolean> = _canRequestAds

    private val _isMobileAdsInitialized = MutableStateFlow(false)
    val isMobileAdsInitialized: StateFlow<Boolean> = _isMobileAdsInitialized

    private val _canLoadAds = MutableStateFlow(false)
    override val canLoadAds: StateFlow<Boolean> = _canLoadAds

    private val _isPrivacyOptionsRequired = MutableStateFlow(false)
    override val isPrivacyOptionsRequired: StateFlow<Boolean> = _isPrivacyOptionsRequired

    override fun initialize() {
        if (!hasStartedMobileAdsInitialization.compareAndSet(false, true)) return

        if (!isWorkManagerPlatformCompatible()) {
            runCatching {
                tracker.trackEvent(
                    "ads_init_skipped_job_scheduler",
                    mapOf(
                        "sdk_int" to Build.VERSION.SDK_INT,
                        "os_release" to Build.VERSION.RELEASE,
                        "manufacturer" to Build.MANUFACTURER,
                        "model" to Build.MODEL,
                    ),
                )
            }
            return
        }

        runCatching {
            val requestConfiguration = RequestConfiguration.Builder().apply {
                if (BuildConfig.DEBUG) {
                    setTestDeviceIds(listOf(AdRequest.DEVICE_ID_EMULATOR))
                }
            }.build()

            MobileAds.setRequestConfiguration(requestConfiguration)
            MobileAds.initialize(application) {
                _isMobileAdsInitialized.value = true
                updateAdLoadReadiness()
                AdDiagnostics.logDebugEvent(
                    format = "sdk",
                    placement = "startup",
                    message = "initialized adapterCount=${it.adapterStatusMap.size}"
                )
            }
        }.onFailure { throwable ->
            AdDiagnostics.trackUnexpectedState(
                tracker = tracker,
                format = "sdk",
                placement = "startup",
                stage = "initialize",
                reason = "mobile_ads_initialize_threw",
                throwable = throwable
            )
        }
    }

    override fun requestConsent(activity: Activity) {
        if (!hasRequestedConsentThisProcess.compareAndSet(false, true)) return

        val parameters = ConsentRequestParameters.Builder().build()
        runCatching {
            consentInformation.requestConsentInfoUpdate(
                activity,
                parameters,
                {
                    updateConsentState()
                    AdDiagnostics.logDebugEvent(
                        format = "consent",
                        placement = "startup",
                        message = "info updated canRequestAds=${consentInformation.canRequestAds()}"
                    )
                    UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { formError ->
                        formError?.let { logConsentFormError("load_and_show", it) }
                        updateConsentState()
                    }
                },
                { formError ->
                    logConsentFormError("info_update", formError)
                    updateConsentState()
                }
            )
        }.onFailure { throwable ->
            updateConsentState()
            AdDiagnostics.trackUnexpectedState(
                tracker = tracker,
                format = "consent",
                placement = "startup",
                stage = "request_info_update",
                reason = "consent_info_update_threw",
                throwable = throwable,
            )
        }
    }

    override fun showPrivacyOptionsForm(activity: Activity) {
        if (_isPrivacyOptionsRequired.value.not()) {
            AdDiagnostics.logDebugEvent(
                format = "consent",
                placement = "privacy_options",
                message = "ignored because privacy options are not required"
            )
            return
        }

        runCatching {
            UserMessagingPlatform.showPrivacyOptionsForm(activity) { formError ->
                formError?.let { logConsentFormError("privacy_options", it) }
                updateConsentState()
            }
        }.onFailure { throwable ->
            AdDiagnostics.trackUnexpectedState(
                tracker = tracker,
                format = "consent",
                placement = "privacy_options",
                stage = "show_form",
                reason = "show_privacy_options_form_threw",
                throwable = throwable
            )
        }
    }

    override fun openAdInspector(activity: Activity) {
        if (!BuildConfig.DEBUG) return

        runCatching {
            MobileAds.openAdInspector(activity) { error ->
                if (error == null) {
                    AdDiagnostics.logDebugEvent(
                        format = "sdk",
                        placement = "ad_inspector",
                        message = "opened"
                    )
                } else {
                    tracker.trackEvent(
                        "ad_inspector_error",
                        mapOf(
                            "error_code" to error.code,
                            "error_domain" to error.domain,
                            "error_message" to error.message
                        )
                    )
                    logAdInspectorError(error)
                }
            }
        }.onFailure { throwable ->
            AdDiagnostics.trackUnexpectedState(
                tracker = tracker,
                format = "sdk",
                placement = "ad_inspector",
                stage = "open",
                reason = "open_ad_inspector_threw",
                throwable = throwable
            )
        }
    }

    private fun updateConsentState() {
        _canRequestAds.value = consentInformation.canRequestAds()
        _isPrivacyOptionsRequired.value =
            consentInformation.privacyOptionsRequirementStatus ==
                    ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED
        updateAdLoadReadiness()
    }

    private fun updateAdLoadReadiness() {
        _canLoadAds.value = _canRequestAds.value && _isMobileAdsInitialized.value
    }

    private fun logConsentFormError(
        stage: String,
        error: FormError,
    ) {
        tracker.trackEvent(
            "ads_consent_error",
            mapOf(
                "stage" to stage,
                "error_code" to error.errorCode,
                "error_message" to error.message
            )
        )
        AdDiagnostics.logDebugEvent(
            format = "consent",
            placement = stage,
            message = "errorCode=${error.errorCode} message=${error.message}"
        )
    }

    private fun logAdInspectorError(error: AdInspectorError) {
        AdDiagnostics.logDebugEvent(
            format = "sdk",
            placement = "ad_inspector",
            message = "errorCode=${error.code} domain=${error.domain} message=${error.message}"
        )
    }
}
