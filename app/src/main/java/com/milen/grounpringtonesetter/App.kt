package com.milen.grounpringtonesetter

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Build
import android.webkit.WebView
import androidx.work.Configuration
import com.milen.grounpringtonesetter.billing.BillingEntitlementGateway
import com.milen.grounpringtonesetter.billing.BillingEntitlementManager
import com.milen.grounpringtonesetter.customviews.ui.ads.AdLoadingHelper
import com.milen.grounpringtonesetter.customviews.ui.ads.AdsGateway
import com.milen.grounpringtonesetter.customviews.ui.ads.AdsManager
import com.milen.grounpringtonesetter.customviews.ui.ads.InterstitialAdGateway
import com.milen.grounpringtonesetter.data.prefs.EncryptedHomePreferencesDataSource
import com.milen.grounpringtonesetter.data.prefs.EncryptedPreferencesHelper
import com.milen.grounpringtonesetter.data.prefs.HomePreferencesStore
import com.milen.grounpringtonesetter.data.prefs.readHomeDisplayPreferencesSync
import com.milen.grounpringtonesetter.data.repos.ContactsRepository
import com.milen.grounpringtonesetter.data.repos.RepoGraph
import com.milen.grounpringtonesetter.data.sources.ContactSourceRepository
import com.milen.grounpringtonesetter.ui.defaulttones.AndroidDeviceDefaultToneManager
import com.milen.grounpringtonesetter.ui.defaulttones.DeviceDefaultToneManager
import com.milen.grounpringtonesetter.ui.home.HomeThemeAppearance
import com.milen.grounpringtonesetter.ui.home.HomeThemeOption
import com.milen.grounpringtonesetter.ui.home.toAppearance
import com.milen.grounpringtonesetter.utils.ContactRingtoneUpdateHelper
import com.milen.grounpringtonesetter.utils.ContactsHelper
import com.milen.grounpringtonesetter.utils.DispatchersProvider
import com.milen.grounpringtonesetter.utils.Telemetry
import com.milen.grounpringtonesetter.utils.Tracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

open class App : Application(), Configuration.Provider {
    internal val tracker: Telemetry by lazy { createTelemetry() }
    internal val adsManager: AdsGateway by lazy { createAdsGateway() }
    internal val preferencesHelper: EncryptedPreferencesHelper by lazy {
        EncryptedPreferencesHelper(this, tracker)
    }
    internal val homePreferencesStore: HomePreferencesStore by lazy {
        HomePreferencesStore(
            dataSource = EncryptedHomePreferencesDataSource(preferencesHelper)
        )
    }
    private val contactRingtoneUpdateHelper: ContactRingtoneUpdateHelper by lazy {
        ContactRingtoneUpdateHelper(
            tracker = tracker,
            preferenceHelper = preferencesHelper,
        )
    }
    private val contactsHelper: ContactsHelper by lazy {
        ContactsHelper(
            appContext = this,
            preferenceHelper = preferencesHelper,
            contactRingtoneUpdateHelper = contactRingtoneUpdateHelper,
            tracker = tracker,
        )
    }
    internal lateinit var billingManager: BillingEntitlementGateway
        private set

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().build()

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        webViewDataDirectorySuffix(Build.VERSION.SDK_INT)?.let(WebView::setDataDirectorySuffix)
    }

    internal open fun createTelemetry(): Telemetry = Tracker()

    internal open fun createAdsGateway(): AdsGateway = AdsManager(this, tracker)

    internal open fun createBillingGateway(): BillingEntitlementGateway =
        BillingEntitlementManager(this, tracker)

    internal open fun provideContactsRepository(): ContactsRepository =
        RepoGraph.contactsRepo(this, contactsHelper, preferencesHelper)

    internal open fun provideContactSourceRepository(): ContactSourceRepository =
        RepoGraph.contactSourceRepo(this, contactsHelper, preferencesHelper)

    internal open fun provideDefaultToneManager(): DeviceDefaultToneManager =
        AndroidDeviceDefaultToneManager(this)

    internal open fun provideInterstitialAdGateway(
        activity: Activity,
        placement: String,
    ): InterstitialAdGateway = AdLoadingHelper(activity, placement)

    @Volatile
    private var activeThemeOption: HomeThemeOption? = null

    internal fun currentThemeOption(): HomeThemeOption {
        val cached = activeThemeOption
        if (cached != null) return cached

        val resolved = readHomeDisplayPreferencesSync(preferencesHelper).themeOption
        activeThemeOption = resolved
        return resolved
    }

    internal fun currentThemeAppearance(): HomeThemeAppearance = currentThemeOption().toAppearance()

    internal fun updateThemeOption(themeOption: HomeThemeOption) {
        activeThemeOption = themeOption
    }

    override fun onCreate() {
        super.onCreate()

        if (BuildConfig.DEBUG) {
            android.os.StrictMode.setThreadPolicy(
                android.os.StrictMode.ThreadPolicy.Builder()
                    .detectDiskReads().detectDiskWrites().detectNetwork()
                    .penaltyLog().build()
            )
            android.os.StrictMode.setVmPolicy(
                android.os.StrictMode.VmPolicy.Builder()
                    .detectLeakedClosableObjects()
                    .penaltyLog().build()
            )
        }

        adsManager.initialize()

        billingManager = createBillingGateway()
        tracker.trackEvent("billing_manager_created", mapOf("app_onCreate_complete" to true))
        val billingScope = CoroutineScope(SupervisorJob() + DispatchersProvider.io)
        billingScope.launch {
            tracker.trackEvent("billing_start_launched", mapOf("coroutine_started" to true))
            val startResult = runCatching { 
                billingManager.start() 
            }
            startResult.onSuccess {
                tracker.trackEvent("billing_start_completed_success", mapOf("result" to "success"))
            }.onFailure { e ->
                tracker.trackEvent(
                    "billing_start_completed_failure",
                    mapOf(
                        "error_type" to e::class.java.simpleName,
                        "error_message" to (e.message ?: "unknown"),
                        "is_cancellation" to (e is kotlinx.coroutines.CancellationException)
                    )
                )
                tracker.trackError(e)
            }
        }
    }

    override fun onTerminate() {
        super.onTerminate()
        billingManager.end()
    }

}
