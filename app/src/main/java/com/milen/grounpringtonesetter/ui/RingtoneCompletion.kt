package com.milen.grounpringtonesetter.ui

import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import com.milen.grounpringtonesetter.R

internal data class RingtoneCompletion(
    val id: Long,
    @param:StringRes val messageResId: Int = R.string.everything_set,
    val isReady: Boolean,
)

/** Records only operations that already succeeded. Restoring feedback never replays an ad. */
internal class RingtoneCompletionStore(private val savedState: SavedStateHandle) {
    init {
        if (savedState.contains(ID)) {
            savedState[READY] = true
            savedState[STARTED] = true
        }
    }

    val current: RingtoneCompletion?
        get() = savedState.get<Long>(ID)?.let { RingtoneCompletion(it, isReady = savedState[READY] ?: true) }

    fun record(waitForAd: Boolean): RingtoneCompletion {
        val id = (savedState.get<Long>(SEQUENCE) ?: 0L) + 1L
        savedState[SEQUENCE] = id
        savedState[ID] = id
        savedState[READY] = !waitForAd
        savedState[STARTED] = !waitForAd
        return requireNotNull(current)
    }

    fun tryStartAd(id: Long): Boolean {
        if (current?.id != id || savedState.get<Boolean>(READY) == true || savedState.get<Boolean>(STARTED) == true) return false
        savedState[STARTED] = true
        return true
    }

    fun finishAd(id: Long) {
        if (current?.id == id) savedState[READY] = true
    }

    fun acknowledge(id: Long) {
        if (current?.id != id) return
        savedState.remove<Long>(ID)
        savedState.remove<Boolean>(READY)
        savedState.remove<Boolean>(STARTED)
    }

    private companion object {
        const val ID = "ringtone_completion_id"
        const val READY = "ringtone_completion_ready"
        const val STARTED = "ringtone_completion_ad_started"
        const val SEQUENCE = "ringtone_completion_sequence"
    }
}
