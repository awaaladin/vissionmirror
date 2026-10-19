package ai.visionmirror

import ai.visionmirror.assistant.VoiceCatalog
import ai.visionmirror.emergency.EmergencyIntent
import ai.visionmirror.emergency.EmergencyIntents
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.File

class EmergencyVoiceTest {
    @Before fun load() {
        val file = listOf("../../shared/voice-commands.json", "../shared/voice-commands.json", "shared/voice-commands.json").map(::File).first { it.exists() }
        VoiceCatalog.install(file.readText())
    }

    @Test fun settingTheContactWithNoNameStartsTheConversationInsteadOfRaisingTheAlarm() {
        for (said in listOf("set my emergency contact", "add an emergency contact", "change my emergency contact", "I want to set my emergency contact", "emergency contact settings")) {
            assertEquals(said, EmergencyIntent.StartSetContact, EmergencyIntents.parse(said))
        }
    }

    @Test fun aNameInTheSentenceStillWorksAsBefore() {
        assertEquals(EmergencyIntent.SetContact("mary"), EmergencyIntents.parse("set my emergency contact to mary"))
        assertEquals(EmergencyIntent.SetContact("tunde okon"), EmergencyIntents.parse("my emergency contact is tunde okon"))
    }

    @Test fun theOtherEmergencyCommands() {
        assertEquals(EmergencyIntent.WhereAmI, EmergencyIntents.parse("where am i"))
        assertEquals(EmergencyIntent.WhereAmI, EmergencyIntents.parse("what street am I on"))
        assertEquals(EmergencyIntent.Sos, EmergencyIntents.parse("I need help"))
        assertEquals(EmergencyIntent.Sos, EmergencyIntents.parse("emergency"))
        assertEquals(EmergencyIntent.ListContacts, EmergencyIntents.parse("who is my emergency contact"))
        assertEquals(EmergencyIntent.ClearContacts, EmergencyIntents.parse("remove my emergency contact"))
        assertEquals(EmergencyIntent.CallEmergency, EmergencyIntents.parse("call 112"))
    }
}
