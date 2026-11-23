package com.milen.grounpringtonesetter.ui

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiScrollable
import androidx.test.uiautomator.UiSelector
import com.milen.grounpringtonesetter.testing.pcmTestWav
import org.junit.Assert.*
import java.io.File

/** Test APK only. The host runner restores runtime grants after instrumentation has exited. */
internal class P0UiEnvironment : AutoCloseable {
    init {
        check(Build.MODEL.contains("sdk", true) || Build.FINGERPRINT.contains("generic")) { "SETUP: dedicated emulator required" }
    }
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    val device: UiDevice = UiDevice.getInstance(instrumentation)
    private val owned = mutableListOf<Pair<Uri, File?>>()
    private val previousMode = shell("settings get global always_finish_activities")
    private val originalTones = listOf(RingtoneManager.TYPE_RINGTONE, RingtoneManager.TYPE_NOTIFICATION, RingtoneManager.TYPE_ALARM)
        .associateWith { RingtoneManager.getActualDefaultRingtoneUri(context, it) }

    data class Tone(val title: String, val uri: Uri)

    fun prepare(grantAudio: Boolean = true) {
        assertTrue("SETUP: dedicated emulator required", Build.MODEL.contains("sdk", true) || Build.FINGERPRINT.contains("generic"))
        assertTrue("SETUP: invalid developer setting '$previousMode'", previousMode in listOf("null", "0", "1"))
        val permissions = listOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS) +
            if (grantAudio) audioPermissions() else emptyList()
        permissions.forEach { permission ->
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, permission)
            assertEquals("SETUP: permission not granted: $permission", PackageManager.PERMISSION_GRANTED,
                context.checkSelfPermission(permission))
        }
        // No WRITE_SETTINGS app-op is changed: this suite deliberately uses the fake tone manager.
        regressionApp().resetUiScenario()
    }

    fun createTone(second: Boolean = false): Tone {
        val title = if (second) "GRS_P0_Test_Tone_2" else "GRS_P0_Test_Tone"
        val legacyFile = if (Build.VERSION.SDK_INT < 29) {
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "GRS_P0/$title.wav").also {
                check(it.parentFile?.mkdirs() == true || it.parentFile?.isDirectory == true) { "SETUP: cannot create audio directory" }
                check(!it.exists()) { "SETUP: refusing to overwrite existing file $it" }
            }
        } else null
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, "$title.wav")
            put(MediaStore.Audio.Media.TITLE, title)
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
            put(MediaStore.Audio.Media.ARTIST, "GRS instrumentation fixture")
            put(MediaStore.Audio.Media.DURATION, 2500)
            put(MediaStore.Audio.Media.IS_RINGTONE, 1)
            put(MediaStore.Audio.Media.IS_NOTIFICATION, 1)
            put(MediaStore.Audio.Media.IS_ALARM, 1)
            put(MediaStore.Audio.Media.IS_MUSIC, 1)
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/GRS_P0")
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            } else put(MediaStore.Audio.Media.DATA, requireNotNull(legacyFile).absolutePath)
        }
        val resolver = context.contentResolver
        val uri = requireNotNull(resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)) { "SETUP: MediaStore insert failed" }
        owned += uri to legacyFile // Register cleanup before any subsequent operation can fail.
        val bytes = pcmTestWav(if (second) 660.0 else 440.0)
        requireNotNull(resolver.openOutputStream(uri)) { "SETUP: audio output unavailable" }.use { it.write(bytes) }
        if (Build.VERSION.SDK_INT >= 29) assertEquals("SETUP: publication failed", 1,
            resolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null))
        requireNotNull(resolver.openInputStream(uri)) { "SETUP: fixture unreadable: $uri" }.use {
            assertArrayEquals("SETUP: WAV contents differ", bytes, it.readBytes())
        }
        resolver.query(uri, arrayOf(MediaStore.Audio.Media.DISPLAY_NAME), null, null, null).use {
            assertTrue("SETUP: published row unavailable", it?.moveToFirst() == true)
            assertEquals("$title.wav", it?.getString(0))
        }
        return Tone(title, uri)
    }

    fun mode(destroy: Boolean) {
        shell("settings put global always_finish_activities ${if (destroy) 1 else 0}")
        assertEquals("SETUP: developer option not applied", if (destroy) "1" else "0", shell("settings get global always_finish_activities"))
        Log.i("GRS_P0_TEST", "always_finish_activities=${if (destroy) 1 else 0}")
    }

    fun selectSystemTone(tone: Tone) {
        await("system ringtone picker") { device.currentPackageName != context.packageName && device.hasObject(By.res("android", "button1")) }
        findTone(tone).click()
        requireNotNull(device.findObject(By.res("android", "button1"))) { "SETUP: system picker confirmation missing" }.click()
    }

    fun selectFileTone(tone: Tone) {
        await("DocumentsUI picker") { device.currentPackageName?.contains("documentsui") == true }
        val pkg = requireNotNull(device.currentPackageName)
        val resources = context.packageManager.getResourcesForApplication(pkg)
        val audioId = resources.getIdentifier("root_audio", "string", pkg)
        check(audioId != 0) { "SETUP: DocumentsUI audio root resource unavailable ($pkg)" }
        val audioTitle = resources.getString(audioId)
        // GET_CONTENT may reopen Recents or a previous folder; always choose the shared Audio root.
        val drawer = device.findObject(By.res("android", "home"))
            ?: device.findObject(By.res(pkg, "toolbar").hasChild(By.clickable(true)))?.children?.firstOrNull { it.isClickable }
        requireNotNull(drawer) { "SETUP: DocumentsUI navigation drawer unavailable" }.click()
        await("DocumentsUI shared Audio root '$audioTitle'") { device.hasObject(By.text(audioTitle)) }
        requireNotNull(device.findObject(By.text(audioTitle))).click()
        // Audio root presents artist/album folders on some releases; use its actual search UI.
        val search = device.wait(androidx.test.uiautomator.Until.findObject(By.res(pkg, "menu_search")), 5_000)
        requireNotNull(search) { "SETUP: Audio-root search unavailable" }.click()
        await("DocumentsUI search input") { device.hasObject(By.res("android", "search_src_text")) }
        requireNotNull(device.findObject(By.res("android", "search_src_text"))).text = tone.title
        device.pressEnter()
        findTone(tone).click()
    }

    private fun findTone(tone: Tone): androidx.test.uiautomator.UiObject2 {
        val selector = By.textContains(tone.title)
        if (device.wait(androidx.test.uiautomator.Until.hasObject(selector), 3_000) != true) {
            val scroll = UiScrollable(UiSelector().scrollable(true))
            check(scroll.exists() && scroll.scrollIntoView(UiSelector().textContains(tone.title))) {
                "SETUP: published fixture ${tone.title}.wav (${tone.uri}) not locatable in ${device.currentPackageName}"
            }
        }
        return requireNotNull(device.findObject(selector)) { "SETUP: fixture row missing: ${tone.title}" }
    }

    fun await(description: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(50)
        }
        error("UI prerequisite/transition failed: $description; foreground=${device.currentPackageName}")
    }

    override fun close() {
        // Run every cleanup even when another cleanup fails; preserve the original test exception via use().
        val failures = mutableListOf<Throwable>()
        fun cleanup(action: () -> Unit) { runCatching(action).exceptionOrNull()?.let(failures::add) }
        cleanup {
            val foreground = device.currentPackageName
            if (foreground?.contains("documentsui") == true || foreground?.contains("soundpicker") == true) {
                shell("am force-stop $foreground")
            }
            device.pressHome()
        }
        owned.asReversed().forEach { (uri, file) -> cleanup {
            assertEquals("CLEANUP: fixture delete failed: $uri", 1, context.contentResolver.delete(uri, null, null))
            if (file?.exists() == true) check(file.delete())
        } }
        cleanup {
            if (previousMode == "null") shell("settings delete global always_finish_activities")
            else if (previousMode in listOf("0", "1")) shell("settings put global always_finish_activities $previousMode")
            assertEquals("CLEANUP: developer setting not restored", previousMode, shell("settings get global always_finish_activities"))
        }
        cleanup { originalTones.forEach { (type, original) ->
            assertEquals("System tone unexpectedly changed; fake gateway must not write settings", original,
                RingtoneManager.getActualDefaultRingtoneUri(context, type))
        } }
        if (failures.isNotEmpty()) throw AssertionError("P0 cleanup failed", failures.first()).apply {
            failures.drop(1).forEach(::addSuppressed)
        }
    }

    companion object {
        fun audioPermissions(): List<String> = when {
            Build.VERSION.SDK_INT >= 33 -> listOf(Manifest.permission.READ_MEDIA_AUDIO)
            Build.VERSION.SDK_INT >= 29 -> listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
            else -> listOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        ).use { it.readBytes().toString(Charsets.UTF_8).trim() }
    }
}
