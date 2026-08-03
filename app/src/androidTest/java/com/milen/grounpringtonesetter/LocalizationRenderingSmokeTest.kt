package com.milen.grounpringtonesetter

import android.content.res.Configuration
import android.os.LocaleList
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class LocalizationRenderingSmokeTest {

    @Test
    fun everySupportedLocaleResolvesCriticalFeatureText() {
        val baseContext = InstrumentationRegistry.getInstrumentation().targetContext
        val localeTags = listOf("en", "bg", "de", "fr", "hi", "it", "ja", "pl", "ru", "zh")
        val criticalStrings = listOf(
            R.string.app_name,
            R.string.add_group,
            R.string.manage_contacts_group_name,
            R.string.device_default_tones_title,
            R.string.backup_restore_title,
            R.string.no_internet_description,
        )

        localeTags.forEach { languageTag ->
            val configuration = Configuration(baseContext.resources.configuration).apply {
                setLocales(LocaleList(Locale.forLanguageTag(languageTag)))
            }
            val localizedContext = baseContext.createConfigurationContext(configuration)
            criticalStrings.forEach { stringId ->
                val text = localizedContext.getString(stringId)
                assertFalse("$languageTag resolved blank text for $stringId", text.isBlank())
                assertNotEquals("$languageTag exposed a resource placeholder", true, text.startsWith("@"))
            }
        }
    }
}
