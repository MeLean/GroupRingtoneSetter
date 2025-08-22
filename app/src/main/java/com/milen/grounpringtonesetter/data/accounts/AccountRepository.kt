package com.milen.grounpringtonesetter.data.accounts

import com.milen.grounpringtonesetter.data.prefs.EncryptedPreferencesHelper
import com.milen.grounpringtonesetter.data.prefs.SelectedAccountsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

internal interface AccountRepository {
    val selected: StateFlow<AccountId?>          // currently selected single account or null
    val available: StateFlow<List<AccountId>>    // available accounts (empty until permission granted)

    fun refreshAvailable()                       // call ONLY after READ_CONTACTS granted
    fun select(account: AccountId)
    fun clearSelection()
    fun cacheKeyOrAll(): String

    fun getAccountsAvailable(): Set<String>
}

internal class AccountRepositoryImpl(
    private val prefs: EncryptedPreferencesHelper,
    private val resolver: AccountsResolver,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : AccountRepository {

    private val _selected = MutableStateFlow(initialSelection())
    override val selected: StateFlow<AccountId?> = _selected

    private val _available = MutableStateFlow<List<AccountId>>(emptyList())
    override val available: StateFlow<List<AccountId>> = _available

    // IMPORTANT: No init{ refreshAvailable() } here.
    // We should not touch Contacts provider before READ_CONTACTS is granted.

    private fun initialSelection(): AccountId? {
        val first = SelectedAccountsStore.read(prefs).firstOrNull() ?: return null
        return AccountId(first)
    }

    override fun refreshAvailable() {
        scope.launch {
            try {
                val raws = resolver.getAccounts()
                _available.value = raws.map { AccountId(it) }.sortedBy { it.name.lowercase() }
            } catch (_: SecurityException) {
                _available.value = emptyList()
            }
        }
    }

    override fun select(account: AccountId) {
        SelectedAccountsStore.write(prefs, setOf(account.raw))
        _selected.value = account
    }

    override fun clearSelection() {
        SelectedAccountsStore.write(prefs, emptySet())
        _selected.value = null
    }

    override fun cacheKeyOrAll(): String =
        SelectedAccountsStore.accountsKeyOrAll(prefs)

    override fun getAccountsAvailable(): Set<String> =
        resolver.getAccounts() // TODO .map { AccountId(it) }
}

internal fun AccountRepository.selectedSetOrEmpty(): Set<String> =
    selected.value?.raw?.let { setOf(it) } ?: emptySet()