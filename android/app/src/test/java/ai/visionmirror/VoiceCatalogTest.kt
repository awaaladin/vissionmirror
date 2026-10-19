package ai.visionmirror

import ai.visionmirror.assistant.AssistantAction
import ai.visionmirror.assistant.AssistantIntents
import ai.visionmirror.assistant.SettingChange
import ai.visionmirror.assistant.VoiceCatalog
import ai.visionmirror.assistant.VoiceSettings
import ai.visionmirror.data.settings.Settings
import ai.visionmirror.design.tokens.ThemeMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class VoiceCatalogTest {
    private val file = listOf("../../shared/voice-commands.json", "../shared/voice-commands.json", "shared/voice-commands.json")
        .map(::File).first { it.exists() }
    private val text = file.readText()
    private val commands: List<JsonObject> = Json.parseToJsonElement(text).jsonObject["commands"]!!.jsonArray.map { it.jsonObject }

    @Before fun load() = VoiceCatalog.install(text)

    private fun strings(c: JsonObject, key: String) = (c[key] as JsonArray).map { it.jsonPrimitive.content }

    @Test fun everyPhraseOfEveryLiveCommandReachesItsOwnCommand() {
        val wrong = mutableListOf<String>()
        var checked = 0
        for (c in commands) {
            if (c["doc_only"]!!.jsonPrimitive.boolean) continue
            val id = c["id"]!!.jsonPrimitive.content
            val verbs = strings(c, "verbs")
            val bare = c["bare"]!!.jsonPrimitive.int
            val screen = strings(c, "screens").firstOrNull()
            for (p in strings(c, "phrases")) {
                val sentences = if (verbs.isEmpty() || p.split(' ').size <= bare) listOf(p) else emptyList()
                for (s in sentences + verbs.take(6).map { "$it $p" }) {
                    checked++
                    val got = VoiceCatalog.match(s, screen)
                    if (got != id) wrong += "\"$s\" -> $got, wanted $id"
                }
            }
        }
        assertTrue("$checked sentences checked; clashes: ${wrong.take(15)}", wrong.isEmpty())
        assertTrue("catalog is large", checked > 4_000)
    }

    @Test fun darkModeAndItsSynonymsReallyChangeTheTheme() {
        for (said in listOf("switch to dark mode", "change to dark mode", "make it darker", "night mode", "go dark", "use the black theme", "set the app to night theme")) {
            val change = VoiceSettings.parse(said)
            assertTrue(said, change is SettingChange.Set)
            assertEquals(said, ThemeMode.Dark, (change as SettingChange.Set).change(Settings()).themeMode)
        }
    }

    @Test fun textSizeCanBeAskedForInDozensOfWays() {
        val bigger = Settings().textScale
        for (said in listOf("increase the font size", "make the text bigger", "i cant read this", "zoom in", "bigger letters", "larger print")) {
            val after = (VoiceSettings.parse(said) as SettingChange.Set).change(Settings()).textScale
            assertTrue(said, after > bigger)
        }
        for (said in listOf("reduce the font size", "make the letters smaller", "the text is too big", "shrink the text")) {
            val after = (VoiceSettings.parse(said) as SettingChange.Set).change(Settings()).textScale
            assertTrue(said, after < bigger)
        }
    }

    @Test fun placesAreOpenedWhateverWayTheyAreAskedFor() {
        fun go(said: String, home: Boolean = false) = AssistantIntents.parse(said, hasSession = false, screen = if (home) "home" else null)
        assertEquals(AssistantAction.OpenRead, go("open the reader"))
        assertEquals(AssistantAction.OpenRead, go("what does it say"))
        assertEquals(AssistantAction.OpenRead, go("read the label"))
        assertEquals(AssistantAction.OpenLook, go("check my surroundings"))
        assertEquals(AssistantAction.OpenLook, go("what is in front of me"))
        assertEquals(AssistantAction.OpenGuide, go("watch for obstacles"))
        assertEquals(AssistantAction.OpenGuide, go("walk with me"))
        assertEquals(AssistantAction.OpenSettings, go("take me to the options"))
        assertEquals(AssistantAction.OpenMirror, go("how do i look"))
        assertEquals(AssistantAction.OpenHome, go("back to the start"))
        assertEquals(AssistantAction.GoBack, go("take me back"))
    }

    @Test fun aQuestionAboutAPhotoStaysAQuestion() {
        assertEquals(null, AssistantIntents.parse("what does the sign in the picture say about the weather today", true, "result"))
        assertEquals(null, AssistantIntents.parse("is the text on my shirt readable", true, "result"))
    }

    @Test fun theMirrorTakesThePhotoOnPhotoOrNowButOtherScreensDoNot() {
        assertEquals(AssistantAction.TakePhoto, AssistantIntents.parse("now", false, "mirror"))
        assertEquals(AssistantAction.TakePhoto, AssistantIntents.parse("snap it", false, "mirror"))
        assertNotNull(AssistantIntents.parse("photo", false, "home")) // opens the mirror
        assertEquals(AssistantAction.LookAgain, AssistantIntents.parse("scan again", false, "look/read"))
    }
}
