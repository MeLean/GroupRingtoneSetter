package com.milen.grounpringtonesetter.data.sources

import com.milen.grounpringtonesetter.data.accounts.AccountsResolver
import com.milen.grounpringtonesetter.data.prefs.EncryptedPreferencesHelper
import com.milen.grounpringtonesetter.data.prefs.SelectedContactSourceStore
import com.milen.grounpringtonesetter.utils.ContactsHelper
import com.milen.grounpringtonesetter.utils.DispatcherProvider
import com.milen.grounpringtonesetter.utils.DispatchersProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

internal interface ContactSourceRepository {
    val selected: StateFlow<ContactSource?>
    val available: StateFlow<List<ContactSource>>

    fun refreshAvailable()
    fun selectNewSource(source: ContactSource)
    fun clearSelection()
    fun cacheKeyOrAll(): String

    fun getSourcesAvailable(): Set<ContactSource>
}

internal class ContactSourceRepositoryImpl(
    private val prefs: EncryptedPreferencesHelper,
    private val resolver: AccountsResolver,
    private val contactsHelper: ContactsHelper,
    private val dispatchers: DispatcherProvider = DispatchersProvider,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + dispatchers.default),
) : ContactSourceRepository {

    private val _selected = MutableStateFlow(initialSelection())
    override val selected: StateFlow<ContactSource?> = _selected

    private val _available = MutableStateFlow<List<ContactSource>>(emptyList())
    override val available: StateFlow<List<ContactSource>> = _available

    private fun initialSelection(): ContactSource? = SelectedContactSourceStore.read(prefs)

    override fun refreshAvailable() {
        scope.launch {
            try {
                _available.value = getSourcesAvailable()
                    .sortedWith(
                        compareBy<ContactSource> { it !is ContactSource.OnDevice }
                            .thenBy {
                                when (it) {
                                    ContactSource.OnDevice -> ""
                                    is ContactSource.CloudAccount -> it.account.name.lowercase()
                                }
                            }
                    )
            } catch (_: SecurityException) {
                _available.value = emptyList()
            }
        }
    }

    override fun selectNewSource(source: ContactSource) {
        _selected.value = source
        scope.launch {
            runCatching {
                SelectedContactSourceStore.writeAsync(prefs, source)
            }
        }
    }

    override fun clearSelection() {
        _selected.value = null
        scope.launch {
            runCatching {
                SelectedContactSourceStore.clearAsync(prefs)
            }
        }
    }

    override fun cacheKeyOrAll(): String =
        SelectedContactSourceStore.sourceKeyOrAll(prefs)

    override fun getSourcesAvailable(): Set<ContactSource> {
        val sources = linkedSetOf<ContactSource>()
        if (contactsHelper.hasPureOnDeviceContacts()) {
            sources += ContactSource.OnDevice
        }
        resolver.getAccounts()
            .sortedBy { it.name.lowercase() }
            .mapTo(sources) { ContactSource.CloudAccount(it) }
        return sources
    }
}
