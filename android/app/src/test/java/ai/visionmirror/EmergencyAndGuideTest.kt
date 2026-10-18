package ai.visionmirror

import ai.visionmirror.data.settings.EmergencyContact
import ai.visionmirror.emergency.Emergency
import ai.visionmirror.emergency.EmergencyIntent
import ai.visionmirror.emergency.EmergencyIntents
import ai.visionmirror.emergency.GeoPoint
import ai.visionmirror.emergency.ShareResult
import ai.visionmirror.guide.Detected
import ai.visionmirror.guide.GuideAnnouncer
import ai.visionmirror.guide.GuideLogic
import ai.visionmirror.guide.Range
import ai.visionmirror.guide.Zone
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EmergencyAndGuideTest {
    // ---- emergency intents
    @Test fun emergencyIntents() {
        assertEquals(EmergencyIntent.WhereAmI, EmergencyIntents.parse("where am I"))
        assertEquals(EmergencyIntent.Sos, EmergencyIntents.parse("I need help"))
        assertEquals(EmergencyIntent.Sos, EmergencyIntents.parse("help me"))
        assertEquals(EmergencyIntent.Share("mum"), EmergencyIntents.parse("send my location to mum"))
        assertEquals(EmergencyIntent.Share(null), EmergencyIntents.parse("share my location"))
        assertEquals(EmergencyIntent.SetContact("mum"), EmergencyIntents.parse("set my emergency contact to mum"))
        assertEquals(EmergencyIntent.SetContact("aunty bisi"), EmergencyIntents.parse("add aunty bisi as my emergency contact"))
        assertEquals(EmergencyIntent.ListContacts, EmergencyIntents.parse("who is my emergency contact"))
        assertEquals(EmergencyIntent.ClearContacts, EmergencyIntents.parse("remove my emergency contacts"))
        assertEquals(EmergencyIntent.CallEmergency, EmergencyIntents.parse("call 112"))
        assertNull(EmergencyIntents.parse("what is the weather in abuja"))
        assertNull(EmergencyIntents.parse("is my shirt neat"))
        // The word "emergency" inside setting a contact must not start the SOS countdown.
        assertTrue(EmergencyIntents.parse("set my emergency contact to dad") is EmergencyIntent.SetContact)
    }

    @Test fun yesMeansYesAndNothingElseDoes() {
        for (said in listOf("yes", "yeah please", "okay", "send it")) assertTrue(said, EmergencyIntents.isYes(said))
        for (said in listOf("no", "no thanks", "wait", "cancel", "")) assertFalse(said, EmergencyIntents.isYes(said))
    }

    // ---- sending
    private val mum = EmergencyContact("Mum", "+2348012344821")
    private val here = GeoPoint(9.0765, 7.3986)
    private val noon = ZonedDateTime.of(2026, 10, 18, 12, 5, 0, 0, ZoneId.of("Africa/Lagos"))

    @Test fun theMessageHasTheReasonThePlaceTheMapLinkAndTheTime() {
        val text = Emergency.message("I need help.", "Garki, Abuja", here, noon)
        assertEquals("I need help. I am near Garki, Abuja. My location: https://maps.google.com/?q=9.076500,7.398600 (sent by VisionMirror at 12:05 PM)", text)
        assertTrue(Emergency.message("I need help.", null, here, noon).contains("My location: https://maps.google.com/?q="))
    }

    @Test fun numbersAreConfirmedByTheirLastFourDigitsOnly() {
        assertEquals("4 8 2 1", Emergency.endingIn("+234 801 234 4821"))
    }

    @Test fun sharingReportsWhoGotItAndWhoDidNot() = runTest {
        val sms = object : ai.visionmirror.emergency.SmsSender {
            override fun send(number: String, text: String) = number == mum.number
        }
        val e = Emergency(FakeLocation(here), FakeAddress("Garki, Abuja"), sms)
        val result = e.share(listOf(mum, EmergencyContact("Dad", "+2348000000000")), "I need help.", noon) as ShareResult.Sent
        assertEquals(listOf("Mum"), result.names)
        assertEquals(listOf("Dad"), result.failed)
        assertTrue(Emergency.say(result).contains("couldn't reach Dad"))
    }

    @Test fun noLocationAndNoContactAreSaidPlainly() = runTest {
        val noFix = Emergency(FakeLocation(null), FakeAddress(null), FakeSms())
        assertEquals(ShareResult.NoLocation, noFix.share(listOf(mum), "x", noon))
        assertEquals(ShareResult.NoContacts, noFix.share(emptyList(), "x", noon))
        assertTrue(noFix.whereAmI().contains("can't find your location"))
    }

    // ---- guide mode
    private fun box(label: String, l: Float, t: Float, r: Float, b: Float) = Detected(label, l, t, r, b, 0.9f)

    @Test fun zonesAndRanges() {
        assertEquals(Zone.Left, GuideLogic.zoneOf(0.2f))
        assertEquals(Zone.Ahead, GuideLogic.zoneOf(0.5f))
        assertEquals(Zone.Right, GuideLogic.zoneOf(0.85f))
        assertEquals(Range.VeryClose, GuideLogic.rangeOf(0.8f, 0.3f))
        assertEquals(Range.Close, GuideLogic.rangeOf(0.45f, 0.2f))
        assertEquals(Range.Near, GuideLogic.rangeOf(0.25f, 0.1f))
        assertEquals(Range.Far, GuideLogic.rangeOf(0.1f, 0.05f))
    }

    @Test fun aPersonAheadAndCloseIsAnnouncedUrgently() {
        val a = GuideAnnouncer { 10_000 }
        val s = GuideLogic.sightings(listOf(box("person", 0.4f, 0.1f, 0.6f, 0.9f)))
        val said = a.next(s)!!
        assertEquals("Person ahead, very close.", said.text)
        assertTrue(said.urgent)
    }

    @Test fun farThingsAreNeverAnnounced() {
        val a = GuideAnnouncer { 10_000 }
        assertNull(a.next(GuideLogic.sightings(listOf(box("chair", 0.1f, 0.5f, 0.15f, 0.58f)))))
    }

    @Test fun theSameThingIsNotRepeatedButComingCloserIsNews() {
        var now = 10_000L
        val a = GuideAnnouncer { now }
        val near = GuideLogic.sightings(listOf(box("chair", 0.05f, 0.3f, 0.3f, 0.55f)))
        assertNotNull(a.next(near))
        now += 2_000
        assertNull(a.next(near)) // same chair, same place, nothing changed
        now += 2_000
        val closer = GuideLogic.sightings(listOf(box("chair", 0.0f, 0.1f, 0.35f, 0.7f)))
        assertEquals("Chair on your left, close.", a.next(closer)!!.text)
    }

    @Test fun theSpeechIsRateLimitedSoItIsNeverAStreamOfChatter() {
        var now = 10_000L
        val a = GuideAnnouncer { now }
        assertNotNull(a.next(GuideLogic.sightings(listOf(box("person", 0.4f, 0.1f, 0.6f, 0.9f)))))
        now += 300
        assertNull(a.next(GuideLogic.sightings(listOf(box("car", 0.7f, 0.1f, 0.95f, 0.8f)))))
        now += 1_500
        assertNotNull(a.next(GuideLogic.sightings(listOf(box("car", 0.7f, 0.1f, 0.95f, 0.8f)))))
    }

    @Test fun groupsAndFriendlyNames() {
        val a = GuideAnnouncer { 10_000 }
        val s = GuideLogic.sightings(listOf(
            box("chair", 0.0f, 0.3f, 0.2f, 0.6f), box("chair", 0.05f, 0.3f, 0.25f, 0.6f), box("couch", 0.7f, 0.2f, 0.95f, 0.7f),
        ))
        val text = a.next(s)!!.text
        assertTrue(text, text.contains("2 chairs on your left") || text.contains("Sofa on your right"))
    }
}
