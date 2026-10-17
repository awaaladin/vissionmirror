package ai.visionmirror

import ai.visionmirror.assistant.SettingChange
import ai.visionmirror.assistant.VoiceSettings
import ai.visionmirror.data.api.DetailLevel
import ai.visionmirror.data.settings.Settings
import ai.visionmirror.design.tokens.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceSettingsTest {
    private fun applied(said: String, from: Settings = Settings()): Settings =
        (VoiceSettings.parse(said) as SettingChange.Set).change(from)

    @Test fun brightnessGoesUpAndDown() {
        assertEquals(1f, applied("brighter").brightness, 0.001f)
        assertEquals(0.4f, applied("dimmer").brightness, 0.001f)
        assertEquals(-1f, applied("auto brightness", Settings(brightness = 0.5f)).brightness, 0.001f)
        assertEquals(1f, applied("full brightness").brightness, 0.001f)
    }

    @Test fun themeAndContrast() {
        assertEquals(ThemeMode.Light, applied("light mode").themeMode)
        assertEquals(ThemeMode.Dark, applied("switch to dark mode").themeMode)
        assertTrue(applied("turn on high contrast").highContrast)
        assertEquals(false, applied("high contrast off", Settings(highContrast = true)).highContrast)
    }

    @Test fun speedAndDetailAndVolume() {
        assertTrue(applied("speak slower").speechRate < 1f)
        assertTrue(applied("faster").speechRate > 1f)
        assertEquals(DetailLevel.Brief, applied("brief descriptions").detailLevel)
        assertEquals(SettingChange.Volume(1), VoiceSettings.parse("louder"))
        assertEquals(SettingChange.Volume(-1), VoiceSettings.parse("turn it down"))
        assertEquals(SettingChange.NextVoice, VoiceSettings.parse("change voice"))
    }

    @Test fun switchingBetweenAdannaAndBola() {
        assertEquals(ai.visionmirror.audio.Persona.Bola, applied("switch to Bola").persona)
        assertEquals(ai.visionmirror.audio.Persona.Bola, applied("male voice").persona)
        assertEquals(ai.visionmirror.audio.Persona.Adanna, applied("switch to Adanna", Settings(persona = ai.visionmirror.audio.Persona.Bola)).persona)
        assertEquals(ai.visionmirror.audio.Persona.Adanna, applied("female voice", Settings(persona = ai.visionmirror.audio.Persona.Bola)).persona)
    }

    @Test fun anythingElseIsLeftAlone() {
        for (said in listOf("is my shirt bright", "what does the text on my tag say", "i like the dark blue colour", "read this", "")) {
            assertNull(said, VoiceSettings.parse(said))
        }
    }
}
