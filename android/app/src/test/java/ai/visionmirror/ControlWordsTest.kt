package ai.visionmirror

import ai.visionmirror.assistant.ControlHints
import ai.visionmirror.assistant.SettingChange
import ai.visionmirror.assistant.VoiceCatalog
import ai.visionmirror.assistant.VoiceSettings
import ai.visionmirror.data.settings.Settings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/** "Reduce the brightness" worked but "increase the screen brightness" did not: any object with any direction must work. */
class ControlWordsTest {
    private val file = listOf("../../shared/voice-commands.json", "../shared/voice-commands.json", "shared/voice-commands.json").map(::File).first { it.exists() }

    @Before fun load() = VoiceCatalog.install(file.readText())

    private fun set(said: String): Settings {
        val change = VoiceSettings.parse(said)
        assertTrue("$said -> $change", change is SettingChange.Set)
        return (change as SettingChange.Set).change(Settings(brightness = 0.6f))
    }

    @Test fun brightnessGoesUpAndDownInAnyWording() {
        for (said in listOf("increase the brightness", "increase the screen brightness", "increase the screen brightness a bit", "raise the brightness",
            "brightness higher please", "turn the screen brightness up", "put the brightness up", "make the brightness higher", "more brightness",
            "can you increase the brightness", "please raise the display brightness", "screen brightness up")) {
            assertTrue(said, set(said).brightness > 0.6f)
        }
        for (said in listOf("reduce the brightness", "decrease the screen brightness", "lower the screen brightness a bit", "brightness lower",
            "turn the brightness down", "dim the screen", "make the brightness lower", "less brightness", "screen brightness down")) {
            assertTrue(said, set(said).brightness < 0.6f)
        }
    }

    @Test fun everyObjectWithEveryDirectionReachesItsCommand() {
        val commands = Json.parseToJsonElement(file.readText()).jsonObject["commands"]!!.jsonArray.map { it.jsonObject }
        val wrong = mutableListOf<String>()
        var checked = 0
        for (c in commands) {
            val id = c["id"]!!.jsonPrimitive.content
            val objects = (c["objects"] as? JsonArray)?.map { it.jsonPrimitive.content }.orEmpty()
            val directions = (c["directions"] as? JsonArray)?.map { it.jsonPrimitive.content }.orEmpty()
            for (o in objects) for (d in directions) for (shape in listOf("%s the %s", "%2\$s %1\$s", "please %s the %s a bit")) {
                val said = if (shape.startsWith("%2")) "$o $d" else shape.format(d, o)
                checked++
                val got = VoiceCatalog.match(said)
                if (got != id) wrong += "\"$said\" -> $got, wanted $id"
            }
        }
        assertTrue("$checked checked; ${wrong.take(10)}", wrong.isEmpty())
        assertTrue(checked > 1_000)
    }

    @Test fun questionsAboutAPhotoAreNotCommands() {
        // As settings they are refused: a question is a question (the open-the-reader and photo paths take it from there).
        assertNull(VoiceSettings.parse("what does the bigger text on the label say"))
        assertNull(VoiceSettings.parse("is the screen brighter than the window"))
        assertNull(VoiceSettings.parse("how big is the text on this tin"))
        assertNull(VoiceSettings.parse("read the larger text"))
    }

    @Test fun aControlRequestThatWasNotRecognisedGetsAHintNotAnAiRefusal() {
        assertNotNull(ControlHints.forRequest("could you adjust the screen brightness for me", false))
        assertNotNull(ControlHints.forRequest("make the volume right", false))
        assertTrue(ControlHints.forRequest("adjust the text size", false)!!.contains("bigger text"))
        // a question about the photo stays a question
        assertNull(ControlHints.forRequest("what is the volume level on this machine", true))
        assertNull(ControlHints.forRequest("is the text bigger on the left", true))
        assertNull(ControlHints.forRequest("what is the weather in lagos", false))
    }
}
