package com.milen.grounpringtonesetter.testing

import android.app.Activity
import com.android.billingclient.api.BillingClient
import com.milen.grounpringtonesetter.billing.BillingEntitlementGateway
import com.milen.grounpringtonesetter.billing.EntitlementState
import com.milen.grounpringtonesetter.customviews.ui.ads.InterstitialAdGateway
import com.milen.grounpringtonesetter.customviews.ui.ads.InterstitialAdShowResult
import com.milen.grounpringtonesetter.data.Contact
import com.milen.grounpringtonesetter.data.LabelItem
import com.milen.grounpringtonesetter.data.prefs.HomePreferencesDataSource
import com.milen.grounpringtonesetter.data.repos.ContactsRepository
import com.milen.grounpringtonesetter.data.repos.GroupReassignmentValidation
import com.milen.grounpringtonesetter.data.repos.RingtoneChoiceOption
import com.milen.grounpringtonesetter.data.sources.ContactSource
import com.milen.grounpringtonesetter.data.sources.ContactSourceRepository
import com.milen.grounpringtonesetter.utils.DispatcherProvider
import com.milen.grounpringtonesetter.utils.Telemetry
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow

internal class TestDispatcherProvider(
    dispatcher: CoroutineDispatcher,
) : DispatcherProvider {
    override val io = dispatcher
    override val default = dispatcher
    override val main = dispatcher
    override val mainImmediate = dispatcher
}

internal class RecordingTelemetry : Telemetry {
    val events = mutableListOf<Pair<String, Map<String, Any>?>>()
    val errors = mutableListOf<Throwable>()

    override fun trackEvent(eventName: String, params: Map<String, Any>?) {
        events += eventName to params
    }

    override fun trackError(error: Throwable) {
        errors += error
    }
}

internal class FakeBillingGateway(
    initialState: EntitlementState = EntitlementState.OWNED,
) : BillingEntitlementGateway {
    override val state = MutableStateFlow(initialState)
    var purchaseResult: Int = BillingClient.BillingResponseCode.OK
    var purchaseError: Throwable? = null
    var purchaseCalls = 0

    override suspend fun start() = Unit

    override suspend fun launchPurchase(activity: Activity): Int {
        purchaseCalls += 1
        purchaseError?.let { throw it }
        return purchaseResult
    }

    override fun end() = Unit
}

internal class FakeInterstitialAdGateway : InterstitialAdGateway {
    var result = InterstitialAdShowResult.SHOWN
    var updateCalls = 0
    var preloadCalls = 0
    var showCalls = 0

    override fun updateActivity(activity: Activity) {
        updateCalls += 1
    }

    override fun preloadInterstitialAd() {
        preloadCalls += 1
    }

    override fun showInterstitialAd(
        onAdLoadingFinished: (InterstitialAdShowResult) -> Unit,
    ) {
        showCalls += 1
        onAdLoadingFinished(result)
    }
}

internal class InMemoryHomePreferencesDataSource(
    initialValues: Map<String, String> = emptyMap(),
) : HomePreferencesDataSource {
    val values = initialValues.toMutableMap()
    var readError: Throwable? = null
    var writeError: Throwable? = null

    override suspend fun getString(key: String, defaultValue: String?): String? {
        readError?.let { throw it }
        return values[key] ?: defaultValue
    }

    override suspend fun saveString(key: String, value: String) {
        writeError?.let { throw it }
        values[key] = value
    }
}

internal class FakeContactSourceRepository(
    initialSelected: ContactSource? = ContactSource.OnDevice,
    initialAvailable: List<ContactSource> = listOf(ContactSource.OnDevice),
) : ContactSourceRepository {
    override val selected = MutableStateFlow(initialSelected)
    override val available = MutableStateFlow(initialAvailable)
    var sources = initialAvailable.toSet()
    var refreshCalls = 0
    val selectedSources = mutableListOf<ContactSource>()
    var selectionError: Throwable? = null

    override fun refreshAvailable() {
        refreshCalls += 1
        available.value = sources.toList()
    }

    override fun selectNewSource(source: ContactSource) {
        selectionError?.let { throw it }
        selected.value = source
        selectedSources += source
    }

    override fun clearSelection() {
        selected.value = null
    }

    override fun cacheKeyOrAll(): String = selected.value?.stableKey ?: "all"

    override fun getSourcesAvailable(): Set<ContactSource> = sources
}

internal class FakeContactsRepository : ContactsRepository {
    override val labelsFlow = MutableStateFlow<List<LabelItem>>(emptyList())
    override val allContacts = MutableStateFlow<List<Contact>?>(emptyList())

    var loadAccountLabelsCalls = 0
    var loadAccountLabelsShallowCalls = 0
    var enrichRingtonesCalls = 0
    var refreshContactsCalls = 0
    var clearRingtonesCalls = 0
    var loadError: Throwable? = null
    var refreshError: Throwable? = null
    var clearError: Throwable? = null
    var createError: Throwable? = null
    var renameError: Throwable? = null
    var deleteError: Throwable? = null
    var updateMembersError: Throwable? = null
    var validationError: Throwable? = null
    var validation = GroupReassignmentValidation(emptyList(), emptyList())
    var ringtoneOptions: List<RingtoneChoiceOption> = emptyList()
    val createdNames = mutableListOf<String>()
    val renamedGroups = mutableListOf<Pair<LabelItem, String>>()
    val deletedGroups = mutableListOf<LabelItem>()
    val updatedMembers = mutableListOf<UpdatedMembersCall>()
    val validatedCandidates = mutableListOf<List<Contact>>()
    val ringtoneChanges = mutableListOf<Triple<LabelItem, String, String>>()

    override suspend fun loadAccountLabels() {
        loadAccountLabelsCalls += 1
        loadError?.let { throw it }
    }

    override suspend fun loadAccountLabelsShallow() {
        loadAccountLabelsShallowCalls += 1
        loadError?.let { throw it }
    }

    override suspend fun enrichRingtonesForCurrentLabels(batchSize: Int) {
        enrichRingtonesCalls += 1
    }

    override suspend fun setGroupRingtone(group: LabelItem, uriStr: String, fileName: String) {
        ringtoneChanges += Triple(group, uriStr, fileName)
    }

    override suspend fun refreshAllPhoneContacts() {
        refreshContactsCalls += 1
        refreshError?.let { throw it }
    }

    override suspend fun createGroup(name: String) {
        createError?.let { throw it }
        createdNames += name
    }

    override suspend fun renameGroup(group: LabelItem, newName: String) {
        renameError?.let { throw it }
        renamedGroups += group to newName
    }

    override suspend fun deleteGroup(group: LabelItem) {
        deleteError?.let { throw it }
        deletedGroups += group
    }

    override suspend fun updateGroupMembers(
        group: LabelItem,
        newSelected: List<Contact>,
        oldSelected: List<Contact>,
        ringtoneForNewContactsUri: String?,
    ) {
        updateMembersError?.let { throw it }
        updatedMembers += UpdatedMembersCall(
            group = group,
            newSelected = newSelected,
            oldSelected = oldSelected,
            ringtoneUri = ringtoneForNewContactsUri,
        )
    }

    override suspend fun validateGroupReassignment(
        group: LabelItem,
        candidates: List<Contact>,
    ): GroupReassignmentValidation {
        validationError?.let { throw it }
        validatedCandidates += candidates
        return validation
    }

    override suspend fun clearAllRingtones() {
        clearRingtonesCalls += 1
        clearError?.let { throw it }
    }

    override suspend fun getContactsByIdsPreferCache(
        ids: List<Long>,
        batchSize: Int,
    ): List<Contact> =
        allContacts.value.orEmpty().filter { it.id in ids }

    override suspend fun enrichGroupContactsBasics(labelId: String, batchSize: Int) = Unit

    override fun getRingtoneChoiceOptions(uriList: List<String>): List<RingtoneChoiceOption> =
        ringtoneOptions.filter { it.uri in uriList }
}

internal data class UpdatedMembersCall(
    val group: LabelItem,
    val newSelected: List<Contact>,
    val oldSelected: List<Contact>,
    val ringtoneUri: String?,
)

internal fun contact(id: Long, name: String = "Contact $id") = Contact(
    id = id,
    lookupKey = "lookup-$id",
    name = name,
    phone = "+359$id",
    ringtoneUriStr = null,
)

internal fun label(
    id: String = "group-1",
    name: String = "Friends",
    contacts: List<Contact> = emptyList(),
    ringtoneUris: List<String> = emptyList(),
) = LabelItem(
    id = id,
    groupName = name,
    contacts = contacts,
    ringtoneUriList = ringtoneUris,
)
