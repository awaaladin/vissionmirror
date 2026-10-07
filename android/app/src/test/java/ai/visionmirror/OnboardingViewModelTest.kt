package ai.visionmirror

import ai.visionmirror.app.Spoken
import ai.visionmirror.audio.VoiceEvent
import ai.visionmirror.onboarding.OnboardingEvent
import ai.visionmirror.onboarding.OnboardingStep
import ai.visionmirror.onboarding.OnboardingViewModel
import app.cash.turbine.test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val speaker = FakeSpeaker()
    private val voice = FakeVoice()
    private val permissions = FakePermissions(camera = false, mic = false)
    private val settings = FakeSettings()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private fun vm() = OnboardingViewModel(speaker, FakeEarcons(), voice, permissions, settings)

    @Test fun welcomeAndPrivacyAreSpokenOnce() = runTest(dispatcher) {
        val vm = vm()
        vm.onEnter()
        vm.onEnter()
        assertEquals(Spoken.WELCOME, speaker.spoken[0])
        assertEquals(Spoken.PRIVACY, speaker.spoken[1])
        assertEquals(1, speaker.spoken.count { it == Spoken.WELCOME })
        assertTrue(Spoken.PRIVACY.contains("never stored"))
    }

    @Test fun cameraIsExplainedAloudBeforeTheSystemDialog() = runTest(dispatcher) {
        val vm = vm()
        vm.onEnter()
        vm.onContinue() // welcome -> camera explanation
        assertEquals(OnboardingStep.Camera, vm.state.value.step)
        assertTrue(speaker.spoken.contains(Spoken.CAMERA_WHY))

        vm.events.test {
            vm.onContinue() // now (and only now) ask the system
            advanceUntilIdle()
            assertEquals(OnboardingEvent.RequestCamera, awaitItem())
        }
    }

    @Test fun deniedCameraStaysPutAndExplains() = runTest(dispatcher) {
        val vm = vm()
        vm.onContinue()
        vm.onCameraResult(false)
        assertEquals(OnboardingStep.Camera, vm.state.value.step)
        assertTrue(speaker.spoken.contains(Spoken.CAMERA_DENIED))

        vm.onCameraResult(true)
        assertEquals(OnboardingStep.Microphone, vm.state.value.step)
        assertTrue(speaker.spoken.contains(Spoken.MIC_WHY))
    }

    @Test fun microphoneIsOptional_andFinishingIsRemembered() = runTest(dispatcher) {
        val vm = vm()
        vm.onContinue()
        vm.onCameraResult(true)

        vm.events.test {
            vm.onMicrophoneResult(false) // denied: still allowed to continue
            advanceUntilIdle()
            assertEquals(OnboardingStep.Done, vm.state.value.step)
            assertTrue(speaker.spoken.contains(Spoken.MIC_DENIED))
            assertTrue(speaker.spoken.contains(Spoken.ALL_SET))
            assertTrue(settings.flow.value.onboardingDone)

            speaker.finishLast() // "All set" has been spoken
            advanceUntilIdle()
            assertEquals(OnboardingEvent.Finished, awaitItem())
        }
    }

    @Test fun finishesEvenIfSpeechNeverReports() = runTest(dispatcher) {
        val vm = vm()
        vm.onContinue()
        vm.onCameraResult(true)
        vm.events.test {
            vm.onMicrophoneResult(true)
            advanceTimeBy(8_100)
            assertEquals(OnboardingEvent.Finished, awaitItem())
        }
    }

    @Test fun alreadyGrantedPermissionsAreSkippedStraightThrough() = runTest(dispatcher) {
        permissions.camera = true
        permissions.mic = true
        val vm = vm()
        vm.onContinue() // -> camera
        vm.onContinue() // camera already granted -> microphone
        assertEquals(OnboardingStep.Microphone, vm.state.value.step)
        vm.onContinue() // mic already granted -> done
        assertEquals(OnboardingStep.Done, vm.state.value.step)
    }

    @Test fun spokenContinueWorksOnceTheMicrophoneIsAllowed() = runTest(dispatcher) {
        permissions.mic = true
        val vm = vm()
        vm.onEnter()
        speaker.finishLast()
        advanceTimeBy(600)
        assertEquals("listens after the queue goes quiet", 1, voice.starts)

        voice.emit(VoiceEvent.Final("continue"))
        advanceUntilIdle()
        assertEquals(OnboardingStep.Camera, vm.state.value.step)
    }

    @Test fun doesNotListenWithoutTheMicrophone() = runTest(dispatcher) {
        val vm = vm()
        vm.onEnter()
        speaker.finishLast()
        advanceTimeBy(600)
        assertEquals(0, voice.starts)
        assertFalse(settings.flow.value.onboardingDone)
    }
}
