package cz.localvoice.app

import android.content.Context
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SpeakSettingsTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Before
    fun resetSettings() {
        context.getSharedPreferences(SpeakSettings.PREFERENCES, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun pausedConfigurationSurvivesReloadAndInvalidActivationDoesNotOverwriteIt() {
        val paused = SpeakConfig(livePreview = false)
        SpeakSettings.save(context, paused)
        assertEquals(paused, SpeakSettings.load(context))
        assertFailsWith<IllegalStateException> {
            SpeakSettings.save(context, paused.copy(activeModelId = "whisper-tiny-int8", enabled = true))
        }
        assertEquals(paused, SpeakSettings.load(context))
    }

    @Test
    fun corruptSuffixFailsWithoutExposingPrivateSavedPayload() {
        SpeakSettings.save(context, SpeakConfig())
        val preferences = context.getSharedPreferences(SpeakSettings.PREFERENCES, Context.MODE_PRIVATE)
        val key = preferences.all.keys.single()
        preferences.edit().putString(key, preferences.getString(key, "") + "PRIVATE_SETTINGS_SENTINEL").commit()
        val error = assertFailsWith<IllegalArgumentException> { SpeakSettings.load(context) }
        assertFalse(error.message.orEmpty().contains("PRIVATE_SETTINGS_SENTINEL"))
    }
}
