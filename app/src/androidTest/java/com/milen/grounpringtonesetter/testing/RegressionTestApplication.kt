package com.milen.grounpringtonesetter.testing

import android.app.Activity
import android.content.Intent
import android.net.Uri
import com.android.billingclient.api.BillingClient
import com.milen.grounpringtonesetter.App
import com.milen.grounpringtonesetter.billing.BillingEntitlementGateway
import com.milen.grounpringtonesetter.billing.EntitlementState
import com.milen.grounpringtonesetter.customviews.ui.ads.AdsGateway
import com.milen.grounpringtonesetter.customviews.ui.ads.InterstitialAdGateway
import com.milen.grounpringtonesetter.customviews.ui.ads.InterstitialAdPolicy
import com.milen.grounpringtonesetter.customviews.ui.ads.InterstitialAdShowResult
import com.milen.grounpringtonesetter.data.Contact
import com.milen.grounpringtonesetter.data.LabelItem
import com.milen.grounpringtonesetter.data.repos.ContactsRepository
import com.milen.grounpringtonesetter.data.repos.GroupReassignmentValidation
import com.milen.grounpringtonesetter.data.repos.RingtoneChoiceOption
import com.milen.grounpringtonesetter.data.sources.ContactSource
import com.milen.grounpringtonesetter.data.sources.ContactSourceRepository
import com.milen.grounpringtonesetter.ui.defaulttones.DeviceDefaultToneManager
import com.milen.grounpringtonesetter.ui.defaulttones.DeviceDefaultToneType
import com.milen.grounpringtonesetter.ui.defaulttones.ImportedCustomTone
import com.milen.grounpringtonesetter.utils.Telemetry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Test process application: no Billing, AdMob, or UMP network calls are made. */
internal class RegressionTestApplication : App() {
    val fakeBilling = InstrumentationBillingGateway()
    val fakeAds = InstrumentationAdsGateway()
    val fakeContacts = InstrumentationContactsRepository()
    val fakeSources = InstrumentationContactSourceRepository()
    val fakeDefaultTones = InstrumentationDefaultToneManager()
    private val fakeInterstitial = InstrumentationInterstitialAdGateway()

    override fun createTelemetry(): Telemetry = InstrumentationTelemetry

    override fun createAdsGateway(): AdsGateway = fakeAds

    override fun createBillingGateway(): BillingEntitlementGateway = fakeBilling

    override fun provideContactsRepository(): ContactsRepository = fakeContacts

    override fun provideContactSourceRepository(): ContactSourceRepository = fakeSources

    override fun provideDefaultToneManager(): DeviceDefaultToneManager = fakeDefaultTones

    override fun provideInterstitialAdGateway(
        activity: Activity,
        placement: String,
    ): InterstitialAdGateway = fakeInterstitial
}

private object InstrumentationTelemetry : Telemetry {
    override fun trackEvent(eventName: String, params: Map<String, Any>?) = Unit

    override fun trackError(error: Throwable) = Unit
}

internal class InstrumentationBillingGateway : BillingEntitlementGateway {
    override val state = MutableStateFlow(EntitlementState.OWNED)
    var purchaseResponseCode = BillingClient.BillingResponseCode.OK
    var purchaseLaunchCount = 0

    override suspend fun start() = Unit

    override suspend fun launchPurchase(activity: Activity): Int {
        purchaseLaunchCount += 1
        return purchaseResponseCode
    }

    override fun end() = Unit
}

internal class InstrumentationAdsGateway : AdsGateway {
    override val interstitialAdPolicy = InterstitialAdPolicy()
    private val mutableCanLoadAds = MutableStateFlow(false)
    private val mutablePrivacyRequired = MutableStateFlow(false)
    override val canLoadAds: StateFlow<Boolean> = mutableCanLoadAds
    override val isPrivacyOptionsRequired: StateFlow<Boolean> = mutablePrivacyRequired

    override fun initialize() = Unit

    override fun requestConsent(activity: Activity) = Unit

    override fun showPrivacyOptionsForm(activity: Activity) = Unit

    override fun openAdInspector(activity: Activity) = Unit

    fun setCanLoadAds(canLoad: Boolean) {
        mutableCanLoadAds.value = canLoad
    }

    fun setPrivacyOptionsRequired(required: Boolean) {
        mutablePrivacyRequired.value = required
    }
}

internal class InstrumentationInterstitialAdGateway : InterstitialAdGateway {
    var result = InterstitialAdShowResult.SKIPPED

    override fun updateActivity(activity: Activity) = Unit

    override fun preloadInterstitialAd() = Unit

    override fun showInterstitialAd(
        onAdLoadingFinished: (InterstitialAdShowResult) -> Unit,
    ) {
        onAdLoadingFinished(result)
    }
}

internal class InstrumentationContactSourceRepository : ContactSourceRepository {
    override val selected = MutableStateFlow<ContactSource?>(ContactSource.OnDevice)
    override val available = MutableStateFlow(listOf<ContactSource>(ContactSource.OnDevice))

    override fun refreshAvailable() {
        // Test fixtures own the available-source list. Refresh must not erase the scenario.
    }

    override fun selectNewSource(source: ContactSource) {
        selected.value = source
    }

    override fun clearSelection() {
        selected.value = null
    }

    override fun cacheKeyOrAll(): String = selected.value?.stableKey ?: "all"

    override fun getSourcesAvailable(): Set<ContactSource> = available.value.toSet()
}

internal class InstrumentationContactsRepository : ContactsRepository {
    override val labelsFlow = MutableStateFlow<List<LabelItem>>(emptyList())
    override val allContacts = MutableStateFlow<List<Contact>?>(emptyList())
    val createdNames = mutableListOf<String>()
    val renamedGroups = mutableListOf<Pair<LabelItem, String>>()
    val deletedGroups = mutableListOf<LabelItem>()
    val memberUpdates = mutableListOf<Pair<LabelItem, List<Contact>>>()
    val ringtoneUpdates = mutableListOf<Triple<LabelItem, String, String>>()
    var clearAllRingtonesCount = 0

    override suspend fun loadAccountLabels() = Unit

    override suspend fun loadAccountLabelsShallow() = Unit

    override suspend fun enrichRingtonesForCurrentLabels(batchSize: Int) = Unit

    override suspend fun setGroupRingtone(group: LabelItem, uriStr: String, fileName: String) {
        ringtoneUpdates += Triple(group, uriStr, fileName)
        labelsFlow.value = labelsFlow.value.map { current ->
            if (current.id == group.id) {
                current.copy(ringtoneUriList = listOf(uriStr), ringtoneFileName = fileName)
            } else {
                current
            }
        }
    }

    override suspend fun refreshAllPhoneContacts() = Unit

    override suspend fun createGroup(name: String) {
        createdNames += name
        val group = LabelItem(id = "test-${createdNames.size}", groupName = name, contacts = emptyList())
        labelsFlow.value = labelsFlow.value + group
    }

    override suspend fun renameGroup(group: LabelItem, newName: String) {
        renamedGroups += group to newName
        labelsFlow.value = labelsFlow.value.map { current ->
            if (current.id == group.id) current.copy(groupName = newName) else current
        }
    }

    override suspend fun deleteGroup(group: LabelItem) {
        deletedGroups += group
        labelsFlow.value = labelsFlow.value.filterNot { it.id == group.id }
    }

    override suspend fun updateGroupMembers(
        group: LabelItem,
        newSelected: List<Contact>,
        oldSelected: List<Contact>,
        ringtoneForNewContactsUri: String?,
    ) {
        memberUpdates += group to newSelected
        labelsFlow.value = labelsFlow.value.map { current ->
            if (current.id == group.id) current.copy(contacts = newSelected) else current
        }
    }

    override suspend fun validateGroupReassignment(
        group: LabelItem,
        candidates: List<Contact>,
    ): GroupReassignmentValidation = GroupReassignmentValidation(candidates, emptyList())

    override suspend fun clearAllRingtones() {
        clearAllRingtonesCount += 1
        labelsFlow.value = labelsFlow.value.map { label ->
            label.copy(ringtoneUriList = emptyList(), ringtoneFileName = "")
        }
    }

    override suspend fun getContactsByIdsPreferCache(ids: List<Long>, batchSize: Int): List<Contact> =
        allContacts.value.orEmpty().filter { it.id in ids }

    override suspend fun enrichGroupContactsBasics(labelId: String, batchSize: Int) = Unit

    override fun getRingtoneChoiceOptions(uriList: List<String>): List<RingtoneChoiceOption> =
        uriList.map { uri -> RingtoneChoiceOption(uri, uri.substringAfterLast('/')) }
}

internal class InstrumentationDefaultToneManager : DeviceDefaultToneManager {
    var canWrite = true
    private val selectedTones = mutableMapOf<DeviceDefaultToneType, Uri?>()
    val appliedTones = mutableListOf<Pair<DeviceDefaultToneType, Uri?>>()
    val deletedImportedTones = mutableListOf<Uri>()

    override fun canWriteSystemSettings(): Boolean = canWrite

    override fun createManageWriteSettingsIntent(): Intent = Intent("test.manage.write.settings")

    override fun createSoundSettingsIntent(): Intent = Intent("test.sound.settings")

    override fun getCurrentDefaultUri(type: DeviceDefaultToneType): Uri? = selectedTones[type]

    override fun getCurrentDisplayName(type: DeviceDefaultToneType): String =
        selectedTones[type]?.lastPathSegment ?: "Test ${type.name.lowercase()}"

    override fun validateToneSelection(type: DeviceDefaultToneType, uriOrNull: Uri?): Result<Unit> =
        Result.success(Unit)

    override fun importCustomTone(
        type: DeviceDefaultToneType,
        sourceUri: Uri,
    ): Result<ImportedCustomTone> = Result.success(ImportedCustomTone(sourceUri, false))

    override fun deleteImportedTone(uri: Uri) {
        deletedImportedTones += uri
    }

    override fun setDefaultTone(type: DeviceDefaultToneType, uriOrNull: Uri?): Result<Unit> {
        appliedTones += type to uriOrNull
        selectedTones[type] = uriOrNull
        return Result.success(Unit)
    }
}
