package ai.visionmirror

import ai.visionmirror.audio.VoiceCommand
import ai.visionmirror.audio.VoiceCommands
import ai.visionmirror.imaging.ImageProcessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ImageAndVoiceTest {
    @Test fun largePhotoScalesToLongSide1280KeepingAspect() {
        assertEquals(960 to 1280, ImageProcessor.targetSize(3000, 4000))
        assertEquals(1280 to 720, ImageProcessor.targetSize(4000, 2250))
    }

    @Test fun smallPhotoIsNeverUpscaled() {
        assertEquals(800 to 600, ImageProcessor.targetSize(800, 600))
        assertEquals(1280 to 1280, ImageProcessor.targetSize(1280, 1280))
    }

    @Test fun commandsAreRecognisedForgivingly() {
        assertEquals(VoiceCommand.Continue, VoiceCommands.parse("Continue"))
        assertEquals(VoiceCommand.Continue, VoiceCommands.parse("okay, continue please"))
        assertEquals(VoiceCommand.Ask, VoiceCommands.parse("I want to ask a question"))
        assertEquals(VoiceCommand.Retake, VoiceCommands.parse("retake"))
        assertEquals(VoiceCommand.Retake, VoiceCommands.parse("Try again!"))
        assertEquals(VoiceCommand.Repeat, VoiceCommands.parse("say that again"))
        assertEquals(VoiceCommand.Pause, VoiceCommands.parse("pause"))
        assertEquals(VoiceCommand.Stop, VoiceCommands.parse("stop"))
    }

    @Test fun ambiguousOrLongSpeechIsNotACommand() {
        assertNull(VoiceCommands.parse("stop and ask"))
        assertNull(VoiceCommands.parse("what colour is the thing near my collar and is it dirty or not"))
        assertNull(VoiceCommands.parse(""))
        assertNull(VoiceCommands.parse("banana"))
    }
}
