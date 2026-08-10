package com.milen.grounpringtonesetter.data.repos

import com.milen.grounpringtonesetter.data.LabelItem
import com.milen.grounpringtonesetter.data.LabelStorageKind
import org.junit.Assert.assertEquals
import org.junit.Test

class OnDeviceLabelItemsTest {

    @Test
    fun `merging on-device labels keeps compatible provider and local labels`() {
        val providerLabel = LabelItem(
            id = "group:1",
            groupName = "Existing device group",
            contacts = emptyList(),
            storageKind = LabelStorageKind.PROVIDER_GROUP,
            providerGroupId = 1L,
        )
        val localLabel = LabelItem(
            id = "local:1",
            groupName = "Created in app",
            contacts = emptyList(),
            storageKind = LabelStorageKind.LOCAL_STORE,
        )

        val merged = mergeOnDeviceLabelItems(
            providerLabels = listOf(providerLabel),
            localLabels = listOf(localLabel),
        )

        assertEquals(listOf(providerLabel, localLabel), merged)
    }

    @Test
    fun `merging on-device labels removes duplicate identifiers`() {
        val providerLabel = LabelItem(
            id = "group:1",
            groupName = "Existing device group",
            contacts = emptyList(),
            storageKind = LabelStorageKind.PROVIDER_GROUP,
            providerGroupId = 1L,
        )

        val merged = mergeOnDeviceLabelItems(
            providerLabels = listOf(providerLabel),
            localLabels = listOf(providerLabel),
        )

        assertEquals(listOf(providerLabel), merged)
    }

    @Test
    fun `editable provider group ids exclude protected groups`() {
        val editableProviderLabel = LabelItem(
            id = "group:1",
            groupName = "Editable",
            contacts = emptyList(),
            storageKind = LabelStorageKind.PROVIDER_GROUP,
            providerGroupId = 1L,
        )
        val readOnlyProviderLabel = LabelItem(
            id = "group:2",
            groupName = "Read-only",
            contacts = emptyList(),
            isReadOnly = true,
            canModify = false,
            canDelete = false,
            storageKind = LabelStorageKind.PROVIDER_GROUP,
            providerGroupId = 2L,
        )
        val localLabel = LabelItem(
            id = "local:1",
            groupName = "Local",
            contacts = emptyList(),
            storageKind = LabelStorageKind.LOCAL_STORE,
        )

        assertEquals(
            setOf(1L),
            editableProviderGroupIds(
                listOf(editableProviderLabel, readOnlyProviderLabel, localLabel)
            )
        )
    }
}
