package ai.visionmirror

import ai.visionmirror.assistant.HandsFree
import ai.visionmirror.assistant.VoiceCatalog
import ai.visionmirror.audio.VoiceCommand
import ai.visionmirror.audio.VoiceCommands
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/** What the website lists must be what the app does: these commands were once documented but only partly understood. */
class EveryListedPhraseWorksTest {
    private val file = listOf("../../shared/voice-commands.json", "../shared/voice-commands.json", "shared/voice-commands.json").map(::File).first { it.exists() }
    private val commands = Json.parseToJsonElement(file.readText()).jsonObject["commands"]!!.jsonArray.map { it.jsonObject }

    @Before fun load() = VoiceCatalog.install(file.readText())

    private fun phrases(id: String, maxWords: Int) =
        commands.first { it["id"]!!.jsonPrimitive.content == id }["phrases"]!!.jsonArray.map { it.jsonPrimitive.content }.filter { it.split(' ').size <= maxWords }

    @Test fun everyWayOfSayingThatAgainAndRetakeAndAskIsUnderstood() {
        val wrong = mutableListOf<String>()
        for ((id, want) in listOf("res.repeat" to VoiceCommand.Repeat, "res.retake" to VoiceCommand.Retake, "res.ask" to VoiceCommand.Ask)) {
            for (p in phrases(id, 6)) if (VoiceCommands.parse(p) != want) wrong += "$p -> ${VoiceCommands.parse(p)} (wanted $want)"
        }
        assertTrue(wrong.take(10).toString(), wrong.isEmpty())
    }

    @Test fun everyWayOfStartingAndEndingHandsFreeIsUnderstood() {
        val wrong = mutableListOf<String>()
        for (p in phrases("hands.on", 7)) if (HandsFree.parse(p) != HandsFree.Change.On) wrong += "$p -> ${HandsFree.parse(p)} (wanted On)"
        for (p in phrases("hands.off", 7)) if (HandsFree.parse(p) != HandsFree.Change.Off) wrong += "$p -> ${HandsFree.parse(p)} (wanted Off)"
        assertTrue(wrong.take(10).toString(), wrong.isEmpty())
    }

    @Test fun sleepIsFoundInSleepSentencesOnly() {
        for (p in phrases("wake.sleep", 12)) assertEquals(p, "wake.sleep", VoiceCatalog.match(p))
        assertEquals(null, VoiceCatalog.match("what is the capital of france"))
    }
}
