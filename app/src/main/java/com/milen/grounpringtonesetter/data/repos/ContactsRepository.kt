package com.milen.grounpringtonesetter.data.repos

import android.app.Application
import com.milen.grounpringtonesetter.data.LabelItem
import com.milen.grounpringtonesetter.utils.ContactsHelper
import com.milen.grounpringtonesetter.utils.Tracker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

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
}


internal class ContactsRepositoryImpl(
    private val app: Application,
    private val helper: ContactsHelper,
    private val tracker: Tracker,
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
                val fresh = withContext(Dispatchers.IO) { helper.getAllLabelItems() }
                _labels.value = fresh
                dirty = false
            }
            _labels.value
        }
    }

    override fun invalidate() {
        dirty = true
    }
}