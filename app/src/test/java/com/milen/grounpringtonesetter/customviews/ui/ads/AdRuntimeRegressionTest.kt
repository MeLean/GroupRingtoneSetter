package com.milen.grounpringtonesetter.customviews.ui.ads

import android.app.Activity
import androidx.lifecycle.SavedStateHandle
import com.milen.grounpringtonesetter.testing.contact
import com.milen.grounpringtonesetter.testing.label
import com.milen.grounpringtonesetter.ui.RingtoneCompletionStore
import android.content.Context
import android.content.res.Resources
import android.os.SystemClock
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.BaseAdView
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.OnPaidEventListener
import com.google.android.gms.ads.ResponseInfo
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.milen.grounpringtonesetter.App
import com.milen.grounpringtonesetter.R
import com.milen.grounpringtonesetter.billing.EntitlementState
import com.milen.grounpringtonesetter.testing.FakeBillingGateway
import com.milen.grounpringtonesetter.testing.RecordingTelemetry
import com.milen.grounpringtonesetter.testing.FakeContactsRepository
import com.milen.grounpringtonesetter.testing.FakeContactSourceRepository
import com.milen.grounpringtonesetter.testing.FakeInterstitialAdGateway
import com.milen.grounpringtonesetter.testing.InMemoryHomePreferencesDataSource
import com.milen.grounpringtonesetter.testing.TestDispatcherProvider
import com.milen.grounpringtonesetter.data.prefs.HomePreferencesStore
import com.milen.grounpringtonesetter.ui.home.viewmodel.HomeViewModel
import com.milen.grounpringtonesetter.utils.DispatchersProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.Shadows
import android.os.Looper
import org.robolectric.util.ReflectionHelpers
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, application = AdRuntimeTestApplication::class,
    shadows = [InterstitialLoadShadow::class, BannerLoadShadow::class])
class AdRuntimeRegressionTest {
    private val app get() = RuntimeEnvironment.getApplication() as AdRuntimeTestApplication

    @Before
    fun reset() {
        InterstitialLoadShadow.requests.clear()
        InterstitialLoadShadow.contexts.clear()
        InterstitialLoadShadow.throwOnLoad = false
        BannerLoadShadow.requests.clear()
        app.ads.canLoadAds.value = true
        app.billing.state.value = EntitlementState.NOT_OWNED
    }

    @Test
    fun `banner loaded while parent hidden survives visibility restoration without another request`() {
        val controller = Robolectric.buildActivity(AdRuntimeTestActivity::class.java).setup()
        try {
            val parent = android.widget.FrameLayout(controller.get())
            val banner = AdBannerView(controller.get())
            parent.addView(banner)
            controller.get().setContentView(parent)
            banner.layout(0, 0, 320, 50)
            banner.setAdsEnabled(true)
            val ad = BannerLoadShadow.requests.single()
            parent.visibility = android.view.View.INVISIBLE
            ad.adListener.onAdLoaded()
            assertSame(ad, field<BaseAdView?>(banner, "bannerAdView"))
            assertTrue(field<Boolean>(banner, "bannerLoaded"))
            parent.visibility = android.view.View.VISIBLE
            assertSame(ad, field<BaseAdView?>(banner, "bannerAdView"))
            assertEquals(1, BannerLoadShadow.requests.size)
        } finally { controller.pause().stop().destroy() }
    }

    @Test
    fun `banner failure while hidden retains backoff and resumes once visible`() {
        val controller = Robolectric.buildActivity(AdRuntimeTestActivity::class.java).setup()
        try {
            val parent = android.widget.FrameLayout(controller.get())
            val banner = AdBannerView(controller.get())
            parent.addView(banner)
            controller.get().setContentView(parent)
            banner.layout(0, 0, 320, 50)
            banner.setAdsEnabled(true)
            parent.visibility = android.view.View.INVISIBLE
            BannerLoadShadow.requests.single().adListener.onAdFailedToLoad(noFill())
            assertNotNull(field<Runnable?>(banner, "pendingRetry"))
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(30))
            assertEquals(1, BannerLoadShadow.requests.size)
            parent.visibility = android.view.View.VISIBLE
            assertEquals(2, BannerLoadShadow.requests.size)
        } finally { controller.pause().stop().destroy() }
    }

    @Test
    fun `hidden banner load is discarded when purchase removes eligibility`() {
        val controller = Robolectric.buildActivity(AdRuntimeTestActivity::class.java).setup()
        try {
            val banner = AdBannerView(controller.get())
            controller.get().setContentView(banner)
            banner.layout(0, 0, 320, 50)
            banner.setAdsEnabled(true)
            banner.visibility = android.view.View.INVISIBLE
            app.billing.state.value = EntitlementState.OWNED
            BannerLoadShadow.requests.single().adListener.onAdLoaded()
            banner.visibility = android.view.View.VISIBLE
            assertNull(field<BaseAdView?>(banner, "bannerAdView"))
            assertEquals(1, BannerLoadShadow.requests.size)
        } finally { controller.pause().stop().destroy() }
    }

    @Test
    fun `banner enable emissions preserve scheduled backoff and cancellation rejects stale runnable`() {
        val controller = Robolectric.buildActivity(AdRuntimeTestActivity::class.java).setup()
        val banner = AdBannerView(controller.get())
        controller.get().setContentView(banner)
        banner.layout(0, 0, 320, 50)
        banner.setAdsEnabled(true)
        BannerLoadShadow.requests.last().adListener.onAdFailedToLoad(noFill())
        val retry = field<Runnable>(banner, "pendingRetry")
        repeat(5) { banner.setAdsEnabled(true) }
        assertEquals(1, BannerLoadShadow.requests.size)
        assertSame(retry, field<Runnable>(banner, "pendingRetry"))
        assertEquals(1, field<Int>(banner, "bannerRetryAttempt"))
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(29_999))
        assertSame(retry, field<Runnable>(banner, "pendingRetry"))
        banner.setAdsEnabled(false)
        assertNull(field<Runnable?>(banner, "pendingRetry"))
        retry.run()
        assertNull(field<Runnable?>(banner, "pendingRetry"))
        assertEquals(0, field<Int>(banner, "bannerRetryAttempt"))
        banner.setAdsEnabled(true)
        BannerLoadShadow.requests.last().adListener.onAdFailedToLoad(noFill())
        assertNotNull(field<Runnable?>(banner, "pendingRetry"))
        controller.pause().stop().destroy()
        assertNull(field<Runnable?>(banner, "pendingRetry"))
    }

    @Test
    fun `banner retry delays remain thirty sixty and capped one hundred twenty seconds`() {
        val controller = Robolectric.buildActivity(AdRuntimeTestActivity::class.java).setup()
        val banner = AdBannerView(controller.get())
        controller.get().setContentView(banner)
        banner.layout(0, 0, 320, 50)
        banner.setAdsEnabled(true)
        listOf(30L, 60L, 120L, 120L).forEach { seconds ->
            val listener = BannerLoadShadow.requests.last().adListener
            listener.onAdFailedToLoad(noFill())
            val retry = field<Runnable>(banner, "pendingRetry")
            val requestsBeforeRetry = BannerLoadShadow.requests.size
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(seconds).minusMillis(1))
            assertSame(retry, field<Runnable>(banner, "pendingRetry"))
            assertEquals(requestsBeforeRetry, BannerLoadShadow.requests.size)
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))
            assertNull(field<Runnable?>(banner, "pendingRetry"))
            assertEquals(requestsBeforeRetry + 1, BannerLoadShadow.requests.size)
            // Late callbacks from the replaced SDK view cannot change the new request's state.
            listener.onAdLoaded()
            assertFalse(field<Boolean>(banner, "bannerLoaded"))
            assertTrue(field<Boolean>(banner, "isBannerLoading"))
        }
        BannerLoadShadow.requests.last().adListener.onAdLoaded()
        assertEquals(0, field<Int>(banner, "bannerRetryAttempt"))
        assertTrue(field<Boolean>(banner, "bannerLoaded"))
        controller.pause().stop().destroy()
    }

    @Test
    fun `only confirmed entitlement and readiness permit a request`() = withHelper { helper ->
        EntitlementState.entries.filter { it != EntitlementState.NOT_OWNED }.forEach { state ->
            app.billing.state.value = state
            helper.preloadInterstitialAd()
            helper.showInterstitialAd { assertEquals(InterstitialAdShowResult.SKIPPED, it) }
        }
        app.billing.state.value = EntitlementState.NOT_OWNED
        app.ads.canLoadAds.value = false
        helper.preloadInterstitialAd()
        assertTrue(InterstitialLoadShadow.requests.isEmpty())
        app.ads.canLoadAds.value = true
        repeat(5) { helper.preloadInterstitialAd() }
        assertEquals(1, InterstitialLoadShadow.requests.size)
    }

    @Test
    fun `late load is discarded after entitlement or readiness changes`() = withHelper { helper ->
        val results = mutableListOf<Boolean>()
        helper.loadInterstitialAd { results += it }
        app.billing.state.value = EntitlementState.OWNED
        InterstitialLoadShadow.requests.single().onAdLoaded(TestInterstitial())
        assertEquals(listOf(false), results)
        assertNull(field<InterstitialAd?>(helper, "interstitialAd"))
    }

    @Test
    fun `cache miss completes immediately and a later load never displays the old operation`() = withHelper { helper ->
        val results = mutableListOf<InterstitialAdShowResult>()
        helper.showInterstitialAd { results += it }
        assertEquals(listOf(InterstitialAdShowResult.SKIPPED), results)
        val ad = TestInterstitial()
        InterstitialLoadShadow.requests.single().onAdLoaded(ad)
        assertNull(ad.shownActivity)
        assertEquals(listOf(InterstitialAdShowResult.SKIPPED), results)
    }

    @Test
    fun `duplicate load callers complete once and failure enforces backoff`() = withHelper { helper ->
        val results = mutableListOf<Boolean>()
        repeat(2) { helper.loadInterstitialAd { results += it } }
        assertEquals(1, InterstitialLoadShadow.requests.size)
        InterstitialLoadShadow.requests.single().onAdFailedToLoad(LoadAdError(3, "no fill", "test", null, null))
        assertEquals(listOf(false, false), results)
        helper.preloadInterstitialAd()
        assertEquals(1, InterstitialLoadShadow.requests.size)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(30))
        helper.preloadInterstitialAd()
        assertEquals(2, InterstitialLoadShadow.requests.size)
    }

    @Test
    fun `load path reuses fresh cache and expires it at fifty five minutes`() = withHelper { helper ->
        seedCache(helper, TestInterstitial())
        helper.loadInterstitialAd { assertTrue(it) }
        assertTrue(InterstitialLoadShadow.requests.isEmpty())
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(55))
        helper.preloadInterstitialAd()
        assertNull(field<InterstitialAd?>(helper, "interstitialAd"))
        assertEquals(1, InterstitialLoadShadow.requests.size)
    }

    @Test
    fun `many immediate impressions dismiss once and preload the next ad every time`() = withHelper { helper ->
        var ad = TestInterstitial()
        seedCache(helper, ad)
        repeat(12) {
            val results = mutableListOf<InterstitialAdShowResult>()
            helper.showInterstitialAd { results += it }
            assertEquals(1, ad.showCalls)
            val callback = requireNotNull(ad.callback)
            callback.onAdImpression()
            callback.onAdDismissedFullScreenContent()
            callback.onAdFailedToShowFullScreenContent(AdError(0, "late", "test"))
            assertEquals(listOf(InterstitialAdShowResult.SHOWN), results)
            assertNull(ad.callback)
            assertTrue(app.ads.interstitialAdPolicy.canShow())
            // Complete the automatic next preload; no clock advancement is needed.
            ad = TestInterstitial()
            InterstitialLoadShadow.requests.last().onAdLoaded(ad)
        }
        assertEquals(12, InterstitialLoadShadow.requests.size)
    }

    @Test
    fun `display failure consumes cache releases fullscreen lock and completes once`() = withHelper { helper ->
        val ad = TestInterstitial()
        seedCache(helper, ad)
        val results = mutableListOf<InterstitialAdShowResult>()
        helper.showInterstitialAd { results += it }
        val callback = requireNotNull(ad.callback)
        helper.showInterstitialAd { assertEquals(InterstitialAdShowResult.SKIPPED, it) }
        callback.onAdFailedToShowFullScreenContent(AdError(0, "failed", "test"))
        callback.onAdDismissedFullScreenContent()
        assertEquals(listOf(InterstitialAdShowResult.SHOW_FAILED), results)
        assertNull(ad.callback)
        assertTrue(app.ads.interstitialAdPolicy.canShow())
        assertEquals(1, InterstitialLoadShadow.requests.size)
    }

    @Test
    fun `expired ad is never displayed and replacement failure completes once`() = withHelper { helper ->
        val expired = TestInterstitial()
        seedCache(helper, expired)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(55))
        val results = mutableListOf<InterstitialAdShowResult>()
        helper.showInterstitialAd { results += it }
        assertNull(expired.shownActivity)
        InterstitialLoadShadow.requests.single().onAdFailedToLoad(noFill())
        assertEquals(listOf(InterstitialAdShowResult.SKIPPED), results)
    }

    @Test
    fun `synchronous display exception releases lock and completes once`() = withHelper { helper ->
        val ad = TestInterstitial().apply { throwOnShow = true }
        seedCache(helper, ad)
        val results = mutableListOf<InterstitialAdShowResult>()
        helper.showInterstitialAd { results += it }
        assertEquals(listOf(InterstitialAdShowResult.SHOW_FAILED), results)
        assertNull(ad.callback)
        assertNull(field<InterstitialAd?>(helper, "interstitialAd"))
        assertTrue(app.ads.interstitialAdPolicy.canShow())
    }

    @Test
    fun `synchronous request exception completes once and starts backoff`() = withHelper { helper ->
        InterstitialLoadShadow.throwOnLoad = true
        val results = mutableListOf<Boolean>()
        helper.loadInterstitialAd { results += it }
        assertEquals(listOf(false), results)
        assertFalse(field<Boolean>(helper, "isLoadingInterstitialAd"))
        assertFalse(app.ads.interstitialAdPolicy.canLoad(SystemClock.elapsedRealtime()))
    }

    @Test
    fun `separate helpers cannot display fullscreen ads concurrently`() = withHelper { first ->
        val firstAd = TestInterstitial()
        seedCache(first, firstAd)
        val second = AdLoadingHelper(requireNotNull(first.currentActivityOrNull()), "second")
        val secondAd = TestInterstitial()
        seedCache(second, secondAd)
        first.showInterstitialAd {}
        second.showInterstitialAd { assertEquals(InterstitialAdShowResult.SKIPPED, it) }
        assertNull(secondAd.shownActivity)
        requireNotNull(firstAd.callback).onAdDismissedFullScreenContent()
    }

    @Test
    fun `late load respects readiness and remains application inventory after host release`() {
        withHelper { helper ->
            val results = mutableListOf<Boolean>()
            helper.loadInterstitialAd { results += it }
            app.ads.canLoadAds.value = false
            InterstitialLoadShadow.requests.last().onAdLoaded(TestInterstitial())
            assertEquals(listOf(false), results)
            assertNull(field<InterstitialAd?>(helper, "interstitialAd"))
        }
        app.ads.canLoadAds.value = true
        val controller = Robolectric.buildActivity(AdRuntimeTestActivity::class.java).setup()
        val helper = AdLoadingHelper(controller.get(), "test")
        val results = mutableListOf<Boolean>()
        helper.loadInterstitialAd { results += it }
        helper.release()
        controller.pause().stop().destroy()
        InterstitialLoadShadow.requests.last().onAdLoaded(TestInterstitial())
        assertEquals(listOf(false), results)
        assertNull(helper.currentActivityOrNull())
        assertNotNull(field<InterstitialAd?>(helper, "interstitialAd"))

    }

    @Test
    fun `obsolete load callback cannot overwrite a newer in flight request`() = withHelper { helper ->
        helper.preloadInterstitialAd()
        val obsolete = InterstitialLoadShadow.requests.single()
        obsolete.onAdFailedToLoad(noFill())
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(30))
        helper.preloadInterstitialAd()
        obsolete.onAdLoaded(TestInterstitial())
        assertTrue(field<Boolean>(helper, "isLoadingInterstitialAd"))
        assertNull(field<InterstitialAd?>(helper, "interstitialAd"))
    }

    @Test
    fun `application placement cache and in flight load survive Activity destruction without a late show`() {
        val original = Robolectric.buildActivity(AdRuntimeTestActivity::class.java).setup()
        val helper = app.provideInterstitialAdGateway(original.get(), "retained_test") as AdLoadingHelper
        helper.preloadInterstitialAd()
        assertSame(app, InterstitialLoadShadow.contexts.single())
        helper.release()
        original.pause().stop().destroy()
        val ad = TestInterstitial()
        InterstitialLoadShadow.requests.single().onAdLoaded(ad)
        assertNull(ad.shownActivity)
        val replacement = Robolectric.buildActivity(AdRuntimeTestActivity::class.java).setup()
        try {
            val restored = app.provideInterstitialAdGateway(replacement.get(), "retained_test")
            assertSame(helper, restored)
            restored.preloadInterstitialAd()
            assertEquals(1, InterstitialLoadShadow.requests.size)
            restored.showInterstitialAd {}
            assertSame(replacement.get(), ad.shownActivity)
            requireNotNull(ad.callback).onAdDismissedFullScreenContent()
        } finally { helper.release(); replacement.pause().stop().destroy() }
    }

    @Test
    fun `released or inactive screen cannot display a cached ad`() = withHelper { helper ->
        val ad = TestInterstitial()
        seedCache(helper, ad)
        helper.setHostActive(false)
        helper.showInterstitialAd { assertEquals(InterstitialAdShowResult.SKIPPED, it) }
        assertNull(ad.shownActivity)
        helper.release()
        assertSame(ad, field<InterstitialAd?>(helper, "interstitialAd"))
        assertNull(helper.currentActivityOrNull())
    }

    @Test
    fun `destroyed host resolves feedback but fullscreen lease lasts until SDK dismissal`() {
        val first = Robolectric.buildActivity(AdRuntimeTestActivity::class.java).setup()
        val helper = AdLoadingHelper(first.get(), "test")
        val ad = TestInterstitial()
        seedCache(helper, ad)
        val outcomes = mutableListOf<InterstitialAdShowResult>()
        helper.showInterstitialAd { outcomes += it }
        val obsolete = requireNotNull(ad.callback)
        helper.release()
        first.pause().stop().destroy()
        assertEquals(listOf(InterstitialAdShowResult.SKIPPED), outcomes)
        assertFalse(app.ads.interstitialAdPolicy.canShow())
        val second = Robolectric.buildActivity(AdRuntimeTestActivity::class.java).setup()
        try {
            helper.updateActivity(second.get())
            helper.preloadInterstitialAd()
            val replacement = TestInterstitial()
            InterstitialLoadShadow.requests.last().onAdLoaded(replacement)
            helper.showInterstitialAd { assertEquals(InterstitialAdShowResult.SKIPPED, it) }
            assertNull(replacement.shownActivity)
            obsolete.onAdDismissedFullScreenContent()
            assertNull(ad.callback)
            helper.showInterstitialAd { outcomes += it }
            obsolete.onAdDismissedFullScreenContent()
            assertFalse(app.ads.interstitialAdPolicy.canShow())
            requireNotNull(replacement.callback).onAdDismissedFullScreenContent()
            assertEquals(listOf(InterstitialAdShowResult.SKIPPED, InterstitialAdShowResult.SHOWN), outcomes)
        } finally { helper.release(); second.pause().stop().destroy() }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `home readiness uses current billing entitlement even before UI state subscription`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val originalDispatchers = DispatchersProvider.delegate
        Dispatchers.setMain(dispatcher)
        DispatchersProvider.delegate = TestDispatcherProvider(dispatcher)
        try {
            withHelper { helper ->
                val gateway = FakeInterstitialAdGateway()
                val billing = FakeBillingGateway(EntitlementState.UNKNOWN)
                val viewModel = HomeViewModel(
                    adHelper = gateway,
                    tracker = RecordingTelemetry(),
                    billing = billing,
                    contactsRepo = FakeContactsRepository(),
                    sourceRepo = FakeContactSourceRepository(),
                    homePreferencesStore = HomePreferencesStore(InMemoryHomePreferencesDataSource()),
                    savedState = SavedStateHandle(),
                )
                val activity = requireNotNull(helper.currentActivityOrNull())
                val readiness = MutableStateFlow(false)
                val emissions = mutableListOf<Boolean>()
                fun observe() = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                    viewModel.interstitialPreloadEligibility(readiness).collect { eligible ->
                        emissions += eligible
                        if (eligible) viewModel.onAdsReady(activity)
                    }
                }
                viewModel.onHomeResumed(activity)
                val observation = observe()
                readiness.value = true // SDK/consent completes while billing is still unknown.
                assertEquals(listOf(false), emissions)
                assertEquals(0, gateway.preloadCalls)
                billing.state.value = EntitlementState.NOT_OWNED
                assertEquals(EntitlementState.UNKNOWN, viewModel.state.value.entitlement)
                assertEquals(listOf(false, true), emissions)
                assertEquals(1, gateway.preloadCalls)
                repeat(3) { readiness.value = true; billing.state.value = EntitlementState.NOT_OWNED }
                assertEquals(1, gateway.preloadCalls)
                observation.cancel() // Background stops the view's lifecycle collector.
                readiness.value = false
                readiness.value = true
                assertEquals(1, gateway.preloadCalls)
                val resumedObservation = observe()
                assertEquals(2, gateway.preloadCalls) // Current eligibility replays on return.
                readiness.value = false
                readiness.value = true
                assertEquals(3, gateway.preloadCalls)
                listOf(EntitlementState.OWNED, EntitlementState.PENDING, EntitlementState.UNKNOWN).forEach {
                    billing.state.value = it
                    viewModel.onAdsReady(activity)
                }
                assertEquals(3, gateway.preloadCalls)
                resumedObservation.cancel()
                assertEquals(0, gateway.showCalls)
            }
            advanceUntilIdle()
        } finally {
            DispatchersProvider.delegate = originalDispatchers
            Dispatchers.resetMain()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `group picker selection survives a new ViewModel and consecutive successes attempt one ad each`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val prior = DispatchersProvider.delegate
        Dispatchers.setMain(dispatcher)
        DispatchersProvider.delegate = TestDispatcherProvider(dispatcher)
        try {
            withHelper { activityHelper ->
                val activity = requireNotNull(activityHelper.currentActivityOrNull())
                val contacts = FakeContactsRepository()
                val group = label(contacts = listOf(contact(1)))
                contacts.labelsFlow.value = listOf(group)
                val gateway = FakeInterstitialAdGateway()
                fun model(handle: SavedStateHandle) = HomeViewModel(
                    gateway, RecordingTelemetry(), FakeBillingGateway(EntitlementState.NOT_OWNED),
                    contacts, FakeContactSourceRepository(), HomePreferencesStore(InMemoryHomePreferencesDataSource()), handle,
                )
                val originalHandle = SavedStateHandle()
                model(originalHandle).selectingGroup = group
                val saved = SavedStateHandle(originalHandle.keys().associateWith { originalHandle.get<Any?>(it) })
                val restored = model(saved)
                val observation = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { restored.state.collect {} }
                advanceUntilIdle()
                repeat(8) { index ->
                    if (index > 0) restored.selectingGroup = group
                    val uri = android.net.Uri.parse("content://media/internal/audio/media/${100 + index}")
                    restored.onRingtoneChosen(activity, uri, "test.mp3", shouldValidateFormat = false)
                    restored.onRingtoneChosen(activity, uri, "duplicate.mp3", shouldValidateFormat = false)
                    advanceUntilIdle()
                    assertEquals(index + 1, contacts.ringtoneChanges.size)
                    val completion = requireNotNull(restored.state.value.pendingRingtoneCompletion)
                    gateway.result = listOf(InterstitialAdShowResult.SHOWN, InterstitialAdShowResult.SKIPPED, InterstitialAdShowResult.SHOW_FAILED)[index % 3]
                    restored.presentRingtoneCompletion(activity, completion.id)
                    restored.presentRingtoneCompletion(activity, completion.id)
                    advanceUntilIdle()
                    assertEquals(index + 1, gateway.showCalls)
                    assertTrue(requireNotNull(restored.state.value.pendingRingtoneCompletion).isReady)
                    restored.onRingtoneCompletionDismissed(completion.id)
                    advanceUntilIdle()
                    assertNull(restored.state.value.pendingRingtoneCompletion)
                }
                observation.cancel()
            }
            advanceUntilIdle()
        } finally {
            DispatchersProvider.delegate = prior
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `saved success restores feedback without replaying an ad and acknowledgment survives restoration`() {
        val state = SavedStateHandle()
        val original = RingtoneCompletionStore(state)
        val completed = original.record(waitForAd = true)
        assertTrue(original.tryStartAd(completed.id))
        assertFalse(original.tryStartAd(completed.id))
        val saved = SavedStateHandle(state.keys().associateWith { state.get<Any?>(it) })
        val recreated = RingtoneCompletionStore(saved)
        assertTrue(requireNotNull(recreated.current).isReady)
        assertFalse(recreated.tryStartAd(completed.id))
        recreated.acknowledge(completed.id)
        val again = RingtoneCompletionStore(SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) }))
        assertNull(again.current)
        assertTrue(again.record(waitForAd = true).id > completed.id)
    }

    private fun withHelper(block: (AdLoadingHelper) -> Unit) {
        val controller = Robolectric.buildActivity(AdRuntimeTestActivity::class.java).setup()
        try { block(AdLoadingHelper(controller.get(), "test")) }
        finally { controller.pause().stop().destroy() }
    }

    private fun seedCache(helper: AdLoadingHelper, ad: TestInterstitial) {
        ReflectionHelpers.setField(helper, "interstitialAd", ad)
        ReflectionHelpers.setField(helper, "interstitialLoadedAtMs", SystemClock.elapsedRealtime())
    }

    private fun noFill() = LoadAdError(3, "no fill", "test", null, null)
    private fun <T> field(target: Any, name: String): T = ReflectionHelpers.getField(target, name)
}

class AdRuntimeTestApplication : App() {
    internal val ads = RuntimeAdsGateway()
    internal val billing = FakeBillingGateway(EntitlementState.NOT_OWNED)
    override fun getResources(): Resources {
        val base = super.getResources()
        return object : Resources(base.assets, base.displayMetrics, base.configuration) {
            override fun getString(id: Int): String = if (id == R.string.admob_interstitial_unit_id) "ca-app-pub-3940256099942544/1033173712" else super.getString(id)
        }
    }
    override fun onCreate() {
        // Avoid production startup, encrypted storage, Billing and UMP in this test process.
        ReflectionHelpers.setField(this, "billingManager", billing)
    }
    internal override fun createAdsGateway(): AdsGateway = ads
    internal override fun createTelemetry() = RecordingTelemetry()
}

class AdRuntimeTestActivity : Activity() {
    override fun getResources(): Resources {
        val base = super.getResources()
        return object : Resources(base.assets, base.displayMetrics, base.configuration) {
            override fun getString(id: Int): String =
                when (id) {
                    R.string.admob_interstitial_unit_id -> "ca-app-pub-3940256099942544/1033173712"
                    R.string.admob_banner_unit_id -> "ca-app-pub-3940256099942544/6300978111"
                    else -> super.getString(id)
                }
        }
    }
}

internal class RuntimeAdsGateway : AdsGateway {
    override val interstitialAdPolicy = InterstitialAdPolicy()
    override val canLoadAds = MutableStateFlow(true)
    override val isPrivacyOptionsRequired = MutableStateFlow(false)
    override fun initialize() = Unit
    override fun requestConsent(activity: Activity) = Unit
    override fun showPrivacyOptionsForm(activity: Activity) = Unit
    override fun openAdInspector(activity: Activity) = Unit
}

@Implements(InterstitialAd::class)
class InterstitialLoadShadow {
    companion object {
        val requests = mutableListOf<InterstitialAdLoadCallback>()
        val contexts = mutableListOf<Context>()
        var throwOnLoad = false
        @JvmStatic @Implementation
        fun load(context: Context, id: String, request: AdRequest, callback: InterstitialAdLoadCallback) {
            if (throwOnLoad) error("Synthetic SDK load exception")
            contexts += context
            requests += callback
        }
    }
}

@Implements(BaseAdView::class)
class BannerLoadShadow : org.robolectric.shadows.ShadowViewGroup() {
    @RealObject private lateinit var view: BaseAdView
    @Implementation fun loadAd(request: AdRequest) { requests += view }
    companion object { val requests = mutableListOf<BaseAdView>() }
}

private class TestInterstitial : InterstitialAd() {
    var callback: FullScreenContentCallback? = null
    var shownActivity: Activity? = null
    var throwOnShow = false
    var showCalls = 0
    private var paid: OnPaidEventListener? = null
    private var placement = 0L
    override fun getAdUnitId() = "test"
    override fun show(activity: Activity) {
        if (throwOnShow) error("Synthetic SDK show exception")
        showCalls += 1
        shownActivity = activity
    }
    override fun setFullScreenContentCallback(callback: FullScreenContentCallback?) { this.callback = callback }
    override fun getFullScreenContentCallback() = callback
    override fun setImmersiveMode(immersive: Boolean) = Unit
    override fun getResponseInfo(): ResponseInfo =
        ResponseInfo::class.java.declaredConstructors.single().let { constructor ->
            constructor.isAccessible = true
            constructor.newInstance(null) as ResponseInfo
        }
    override fun setOnPaidEventListener(listener: OnPaidEventListener?) { paid = listener }
    override fun getOnPaidEventListener() = paid
    override fun getPlacementId() = placement
    override fun setPlacementId(id: Long) { placement = id }
}
