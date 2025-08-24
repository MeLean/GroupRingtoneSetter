package com.milen.grounpringtonesetter.data.accounts

import com.milen.grounpringtonesetter.data.prefs.EncryptedPreferencesHelper
import com.milen.grounpringtonesetter.data.prefs.SelectedAccountsStore
import com.milen.grounpringtonesetter.utils.DispatcherProvider
import com.milen.grounpringtonesetter.utils.DispatchersProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

internal interface AccountRepository {
    val selected: StateFlow<AccountId?>          // currently selected single account or null
    val available: StateFlow<List<AccountId>>    // available accounts (empty until permission granted)

    fun refreshAvailable()
    fun selectNewAccount(account: AccountId)
    fun clearSelection()
    fun cacheKeyOrAll(): String

    fun getAccountsAvailable(): Set<AccountId>
}

internal class AccountRepositoryImpl(
    private val prefs: EncryptedPreferencesHelper,
    private val resolver: AccountsResolver,
    private val dispatchers: DispatcherProvider = DispatchersProvider,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + dispatchers.default),
) : AccountRepository {

    private val _selected = MutableStateFlow(initialSelection())
    override val selected: StateFlow<AccountId?> = _selected

    private val _available = MutableStateFlow<List<AccountId>>(emptyList())
    override val available: StateFlow<List<AccountId>> = _available

    private fun initialSelection(): AccountId? {
        val first = SelectedAccountsStore.read(prefs).firstOrNull() ?: return null
        return AccountId(first)
    }

    override fun refreshAvailable() {
        scope.launch {
            try {
                _available.value = resolver.getAccounts().sortedBy { it.name.lowercase() }
            } catch (_: SecurityException) {
                _available.value = emptyList()
            }
        }
    }

    override fun selectNewAccount(account: AccountId) {
        _selected.value = account
        scope.launch {
            try {
                SelectedAccountsStore.writeAsync(prefs, setOf(account.raw))
            } catch (_: Throwable) {
            }
        }
    }

    override fun clearSelection() {
        _selected.value = null
        scope.launch {
            try {
                SelectedAccountsStore.clearAsync(prefs)
            } catch (_: Throwable) {
            }
        }
    }

    override fun cacheKeyOrAll(): String =
        SelectedAccountsStore.accountsKeyOrAll(prefs)

    override fun getAccountsAvailable(): Set<AccountId> =
        resolver.getAccounts()
}
