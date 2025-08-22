package com.milen.grounpringtonesetter.data.repos

import android.accounts.Account
import com.milen.grounpringtonesetter.data.LabelItem
import com.milen.grounpringtonesetter.utils.ContactsHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal interface ContactsRepository {
    val labelsFlow: StateFlow<List<LabelItem>>

    /**
     * Load labels into cache if needed.
     * - forceRefresh=false: return fast if cache present & not marked dirty.
     * - forceRefresh=true: always re-query ContactsProvider.
     * Returns the *current* snapshot (possibly from cache).
     */
    suspend fun load(forceRefresh: Boolean = false): List<LabelItem>

    /** Mark cache as dirty; next load(force=false) will refresh. */
    fun invalidate()

    fun getGoogleAccounts(): List<Account>

    fun getAllLabelItemsForAccounts(selected: Set<String>): List<LabelItem>

    fun getContactsAccountName(): String?
}


internal class ContactsRepositoryImpl(
    private val helper: ContactsHelper,
    private val accountsProvider: () -> Set<String>,
) : ContactsRepository {

    private val lock = Mutex()
    private val _labels = MutableStateFlow<List<LabelItem>>(emptyList())
    override val labelsFlow: StateFlow<List<LabelItem>> = _labels

    @Volatile
    private var dirty: Boolean = true

    override suspend fun load(forceRefresh: Boolean): List<LabelItem> {
        return lock.withLock {
            val shouldQuery = forceRefresh || dirty || _labels.value.isEmpty()
            if (shouldQuery) {
                val selected = accountsProvider()
                val fresh = if (selected.isEmpty()) {
                    helper.getAllLabelItems()
                } else {
                    helper.getAllLabelItemsForAccounts(selected)
                }
                _labels.value = fresh
                dirty = false
            }
            _labels.value
        }
    }

    override fun invalidate() {
        dirty = true
    }

    override fun getGoogleAccounts(): List<Account> =
        helper.getGoogleAccounts()

    override fun getAllLabelItemsForAccounts(selected: Set<String>): List<LabelItem> =
        helper.getAllLabelItemsForAccounts(selected)

    override fun getContactsAccountName(): String? = accountsProvider().firstOrNull()
}