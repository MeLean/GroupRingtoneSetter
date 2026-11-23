package com.milen.grounpringtonesetter.ui.home

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.milen.grounpringtonesetter.R
import com.milen.grounpringtonesetter.data.Contact
import com.milen.grounpringtonesetter.data.LabelItem
import com.milen.grounpringtonesetter.databinding.ItemGroupEntityBinding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import com.milen.grounpringtonesetter.ui.contactsPermissionRule
import com.milen.grounpringtonesetter.ui.launchMain
import com.milen.grounpringtonesetter.ui.regressionApp
import com.milen.grounpringtonesetter.ui.resetUiScenario
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.ceil

/** Real Android measurement/drawing, including repeated binds of the same recycled card. */
@RunWith(AndroidJUnit4::class)
class GroupCountLayoutTest {
    @get:Rule
    val permissions = contactsPermissionRule

    @Test
    fun scrollingRecyclesCardsWithoutStaleCounts() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val counts = listOf(0, 9, 10, 90, 100, 999, 1000)
        val contacts = List(1000) { Contact(it.toLong(), "key-$it", "Contact $it", null, null) }
        val groups = List(35) { index ->
            LabelItem("group-$index", "Group ${index.toString().padStart(2, '0')}", contacts.take(counts[index % counts.size]))
        }
        regressionApp().resetUiScenario(contacts, groups)
        launchMain().use { scenario ->
            instrumentation.waitForIdleSync()
            listOf(0, 6, 18, 34, 0, 3, 6).forEach { position ->
                scenario.onActivity { activity ->
                    activity.findViewById<RecyclerView>(R.id.rwGroupItems).scrollToPosition(position)
                }
                instrumentation.waitForIdleSync()
                scenario.onActivity { activity ->
                    val list = activity.findViewById<RecyclerView>(R.id.rwGroupItems)
                    val row = requireNotNull(list.findViewHolderForAdapterPosition(position)).itemView
                    val badge = row.findViewById<TextView>(R.id.cwtContacts)
                    val count = groups[position].contacts.size
                    assertEquals(count.toString(), badge.text.toString())
                    assertTrue(badge.paint.measureText(badge.text.toString()) <= badge.width - badge.compoundPaddingLeft - badge.compoundPaddingRight)
                    assertEquals(TextView::class.java.name, badge.accessibilityClassName.toString())
                    assertTrue(badge.contentDescription.toString().contains(count.toString()))
                    assertFalse(badge.isClickable)
                }
            }
            val screenshot = instrumentation.uiAutomation.takeScreenshot()
            File(instrumentation.targetContext.cacheDir, "group-count-live.png").outputStream().use {
                screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            screenshot.recycle()
        }
    }

    @Test
    fun countsFitAtAccessibleFontAndDisplaySizes() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            listOf(2f, 1f, 1.3f, 1.5f).forEach { fontScale ->
                listOf(480, 600).forEach { density ->
                    HomeThemeOption.entries.forEach { theme ->
                        val configuration = Configuration(instrumentation.targetContext.resources.configuration).apply {
                            this.fontScale = fontScale
                            densityDpi = density
                        }
                        val context = ContextThemeWrapper(
                            instrumentation.targetContext.createConfigurationContext(configuration),
                            R.style.Theme_GroupRingtoneSetter,
                        )
                        val binding = ItemGroupEntityBinding.inflate(LayoutInflater.from(context))
                        val holder = GroupsAdapter.ViewHolder(binding)
                        listOf(288, 328, 608).forEach { widthDp ->
                            // Wide-to-narrow-to-wide numbers expose stale measurements on rebind.
                            listOf(90, 0, 9, 10, 100, 999, 1000, 9, 1000).forEach { count ->
                                val group = LabelItem(
                                    id = count.toString(),
                                    groupName = "Long group name ".repeat(12),
                                    contacts = List(count) { Contact(it.toLong(), "key-$it", "Contact $it", null, null) },
                                    ringtoneFileName = "Long_ringtone_filename_".repeat(12) + ".mp3",
                                )
                                holder.bind(group, NoActions, theme.toAppearance())
                                val width = (widthDp * context.resources.displayMetrics.density).toInt()
                                binding.root.measure(
                                    View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                                )
                                binding.root.layout(0, 0, width, binding.root.measuredHeight)
                                val badge = binding.cwtContacts
                                val available = badge.width - badge.compoundPaddingLeft - badge.compoundPaddingRight
                                val required = ceil(badge.paint.measureText(count.toString())).toInt()
                                val case = "count=$count font=$fontScale density=$density width=$widthDp theme=$theme"
                                assertEquals(case, count.toString(), badge.text.toString())
                                assertTrue("$case: required=$required available=$available", required <= available)
                                assertEquals(case, 1, badge.layout.lineCount)
                                assertEquals(case, 0, badge.layout.getEllipsisCount(0))
                                assertTrue(case, badge.layout.height <= badge.height - badge.compoundPaddingTop - badge.compoundPaddingBottom)
                                assertTrue(case, badge.right <= binding.crbChooseRingtone.left)
                                assertTrue(case, binding.ctwRingtone.top >= maxOf(badge.bottom, binding.crbChooseRingtone.bottom))
                                listOf(badge, binding.ctwRingtone, binding.crbChooseRingtone,
                                    binding.ctcibManageContacts, binding.ctcibEdit, binding.ctcibDelete).forEach { view ->
                                    assertTrue("$case: ${view.id} outside card", view.left >= 0 && view.top >= 0 && view.right <= width && view.bottom <= binding.root.height)
                                }
                                assertFalse(case, badge.isClickable)
                                assertFalse(case, badge.isLongClickable)
                                assertEquals(case, TextView::class.java.name, badge.accessibilityClassName.toString())
                                assertEquals(case, HomeGroupCardAccessibilityText.buildContactsAccessibilityText(
                                    context.getString(R.string.group_contacts_count), count,
                                    context.getString(R.string.accessibility_label_with_number),
                                ), badge.contentDescription.toString())
                                assertEquals(case, ContextCompat.getColor(context, theme.toAppearance().counterTextColorRes), badge.currentTextColor)
                                val bitmap = Bitmap.createBitmap(width, binding.root.height, Bitmap.Config.ARGB_8888)
                                // TextView centers single-line text in its pre-draw callback.
                                badge.viewTreeObserver.dispatchOnPreDraw()
                                binding.root.draw(Canvas(bitmap))
                                val pixels = IntArray(badge.width * badge.height)
                                bitmap.getPixels(pixels, 0, badge.width, badge.left, badge.top, badge.width, badge.height)
                                assertTrue("$case: text pixels absent", pixels.any { it == badge.currentTextColor })
                                if (widthDp == 288 && density == 480 && fontScale in listOf(1f, 2f) && count in listOf(90, 1000)) {
                                    File(context.cacheDir, "group-count-$count-$fontScale-$theme.png").outputStream().use {
                                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                                    }
                                }
                                bitmap.recycle()
                            }
                        }
                    }
                }
            }
        }
    }

    private object NoActions : GroupsAdapter.GroupItemsInteractor {
        override fun onManageContacts(labelItem: LabelItem) = Unit
        override fun onEditName(labelItem: LabelItem) = Unit
        override fun onGroupDelete(labelItem: LabelItem) = Unit
        override fun onChoseRingtoneIntent(labelItem: LabelItem) = Unit
    }
}
