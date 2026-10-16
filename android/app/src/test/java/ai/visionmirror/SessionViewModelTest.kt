package ai.visionmirror

import ai.visionmirror.audio.Earcon
import ai.visionmirror.audio.VoiceEvent
import ai.visionmirror.data.api.AppError
import ai.visionmirror.data.api.AskResponse
import ai.visionmirror.data.api.DetailLevel
import ai.visionmirror.session.AskPhase
import ai.visionmirror.session.Phase
import ai.visionmirror.session.SessionEvent
import ai.visionmirror.session.SessionViewModel
import app.cash.turbine.test
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val repo = FakeRepo()
    private val speaker = FakeSpeaker()
    private val earcons = FakeEarcons()
    private val voice = FakeVoice()
    private val connectivity = FakeConnectivity()
    private val settings = FakeSettings()
    private val permissions = FakePermissions()
    private val wake = FakeWake()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private fun vm() = SessionViewModel(
        repo, speaker, earcons, ai.visionmirror.haptics.NoopHaptics, voice, connectivity, settings, permissions, wake,
        CoroutineScope(dispatcher),
    )

    @Test fun successfulDescribeIsSpokenAndShown() = runTest(dispatcher) {
        repo.describeResults += Result.success(described(text = "Your navy shirt looks crisp."))
        val vm = vm()
        vm.submitPhoto(byteArrayOf(1), null)
        assertEquals(Phase.Analysing, vm.state.value.phase)
        advanceUntilIdle()

        assertEquals(Phase.Result, vm.state.value.phase)
        assertEquals("Your navy shirt looks crisp.", vm.state.value.displayText)
        assertTrue(speaker.spoken.first().contains("One moment"))
        assertEquals("Your navy shirt looks crisp.", speaker.spoken.last())
        assertTrue(Earcon.Ready in earcons.played)
    }

    @Test fun unusablePhotoReadsAdviceAndHasNoSession() = runTest(dispatcher) {
        repo.describeResults += Result.success(described(session = null, text = "Try facing a window.", usable = false))
        val vm = vm()
        vm.submitPhoto(byteArrayOf(1), null)
        advanceUntilIdle()

        assertEquals(Phase.Unusable, vm.state.value.phase)
        assertNull(vm.state.value.result)
        assertEquals("Try facing a window.", speaker.spoken.last())

        // Nothing was stored, so there is nothing to ask about.
        vm.startAsking()
        assertEquals(0, voice.starts)
        assertTrue(speaker.spoken.last().contains("no photo"))
    }

    @Test fun offlinePhotoIsQueuedThenDescribedWhenBackOnline() = runTest(dispatcher) {
        connectivity.flow.value = false
        repo.describeResults += Result.failure(AppError.Offline())
        repo.describeResults += Result.success(described(text = "Back with you."))
        val vm = vm()
        vm.submitPhoto(byteArrayOf(1), null)
        advanceUntilIdle()

        assertEquals(Phase.Offline, vm.state.value.phase)
        assertTrue(speaker.spoken.any { it.contains("can't reach the internet") })
        assertEquals(1, repo.describeDetails.size)

        connectivity.flow.value = true
        advanceUntilIdle()
        assertEquals(Phase.Result, vm.state.value.phase)
        assertEquals(2, repo.describeDetails.size)
        assertTrue(speaker.spoken.any { it.contains("back online") })
    }

    @Test fun slowNetworkRetriesTheSamePhotoAutomatically() = runTest(dispatcher) {
        repo.describeResults += Result.failure(AppError.NetworkTimeout("Timed out."))
        repo.describeResults += Result.success(described(text = "Your blue jacket."))
        val vm = vm()
        vm.submitPhoto(byteArrayOf(1), null)
        advanceUntilIdle()

        assertEquals(2, repo.describeDetails.size)
        assertEquals(Phase.Result, vm.state.value.phase)
        assertEquals("Your blue jacket.", vm.state.value.displayText)
    }

    @Test fun briefConnectionDropRetriesWhenAndroidStillReportsOnline() = runTest(dispatcher) {
        repo.describeResults += Result.failure(AppError.Offline())
        repo.describeResults += Result.success(described(text = "Your green sweater."))
        val vm = vm()
        vm.submitPhoto(byteArrayOf(1), null)
        advanceUntilIdle()

        assertEquals(2, repo.describeDetails.size)
        assertEquals(Phase.Result, vm.state.value.phase)
        assertEquals("Your green sweater.", vm.state.value.displayText)
    }

    @Test fun rateLimitWaitsRetryAfterThenTriesAgain() = runTest(dispatcher) {
        repo.describeResults += Result.failure(AppError.RateLimited(5, "I'm busy. Trying again in 5 seconds."))
        repo.describeResults += Result.success(described())
        val vm = vm()
        vm.submitPhoto(byteArrayOf(1), null)
        advanceTimeBy(4_000)
        assertEquals("not yet", 1, repo.describeDetails.size)
        advanceTimeBy(1_500)
        advanceUntilIdle()
        assertEquals(2, repo.describeDetails.size)
        assertEquals(Phase.Result, vm.state.value.phase)
    }

    @Test fun otherFailuresSpeakTheServerLineAndOfferRetry() = runTest(dispatcher) {
        repo.describeResults += Result.failure(AppError.Server("My servers are having a moment."))
        repo.describeResults += Result.success(described())
        val vm = vm()
        vm.submitPhoto(byteArrayOf(1), null)
        advanceUntilIdle()
        assertEquals(Phase.Failed, vm.state.value.phase)
        assertEquals("My servers are having a moment.", vm.state.value.banner)
        assertTrue(Earcon.Error in earcons.played)

        vm.retry()
        advanceUntilIdle()
        assertEquals(Phase.Result, vm.state.value.phase)
    }

    @Test fun askingWithVoiceSendsTheQuestionAndSpeaksTheAnswer() = runTest(dispatcher) {
        repo.describeResults += Result.success(described(session = "s1"))
        repo.askResults += Result.success(AskResponse("It looks like a small stain.", "It looks like a small stain.", 0.7f))
        val vm = vm()
        vm.submitPhoto(byteArrayOf(1), null)
        advanceUntilIdle()

        vm.startAsking()
        assertEquals(AskPhase.Listening, vm.state.value.ask)
        assertEquals(1, voice.starts)
        assertTrue(Earcon.ListeningOn in earcons.played)

        voice.emit(VoiceEvent.Partial("is that a"))
        advanceUntilIdle()
        assertEquals("is that a", vm.state.value.partialQuestion)

        voice.emit(VoiceEvent.Final("is that a stain"))
        advanceUntilIdle()

        assertEquals(listOf("is that a stain"), repo.questions)
        assertEquals(AskPhase.Idle, vm.state.value.ask)
        assertEquals("It looks like a small stain.", vm.state.value.displayText)
        assertEquals(1, vm.state.value.conversation.size)
        assertEquals("It looks like a small stain.", speaker.spoken.last())
        assertTrue(Earcon.ListeningOff in earcons.played)
    }

    @Test fun followUpsAccumulateInTheSession() = runTest(dispatcher) {
        repo.describeResults += Result.success(described())
        repo.askResults += Result.success(AskResponse("a1", "a1", 0.7f))
        repo.askResults += Result.success(AskResponse("a2", "a2", 0.7f))
        val vm = vm()
        vm.submitPhoto(byteArrayOf(1), null)
        advanceUntilIdle()

        vm.startAsking(); voice.emit(VoiceEvent.Final("q1")); advanceUntilIdle()
        vm.startAsking(); voice.emit(VoiceEvent.Final("q2")); advanceUntilIdle()

        assertEquals(listOf("q1", "q2"), vm.state.value.conversation.map { it.question })
    }

    @Test fun expiredSessionAsksForANewPhoto() = runTest(dispatcher) {
        repo.describeResults += Result.success(described())
        repo.askResults += Result.failure(AppError.SessionExpired("That photo has expired."))
        val vm = vm()
        vm.submitPhoto(byteArrayOf(1), null)
        advanceUntilIdle()

        vm.events.test {
            vm.startAsking()
            voice.emit(VoiceEvent.Final("anything"))
            advanceUntilIdle()
            assertEquals(SessionEvent.NeedNewPhoto, awaitItem())
        }
        assertEquals("That photo has expired.", speaker.spoken.last())
    }

    @Test fun silenceWhileAskingGivesAGentleRetryPrompt() = runTest(dispatcher) {
        repo.describeResults += Result.success(described())
        val vm = vm()
        vm.submitPhoto(byteArrayOf(1), null)
        advanceUntilIdle()

        vm.startAsking()
        voice.emit(VoiceEvent.Failed(ai.visionmirror.audio.VoiceFailure.NoSpeech))
        advanceUntilIdle()
        assertEquals(AskPhase.Idle, vm.state.value.ask)
        assertTrue(speaker.spoken.last().contains("didn't catch"))
    }

    @Test fun noMicrophonePermissionExplainsInsteadOfFailingSilently() = runTest(dispatcher) {
        permissions.mic = false
        repo.describeResults += Result.success(described())
        val vm = vm()
        vm.submitPhoto(byteArrayOf(1), null)
        advanceUntilIdle()
        vm.startAsking()
        assertEquals(0, voice.starts)
        assertTrue(speaker.spoken.last().contains("microphone"))
    }

    @Test fun changingDetailRedescribesTheSamePhotoAndReplacesTheSession() = runTest(dispatcher) {
        repo.describeResults += Result.success(described(session = "s1", text = "short"))
        repo.describeResults += Result.success(described(session = "s2", text = "a much longer description"))
        val vm = vm()
        vm.submitPhoto(byteArrayOf(1), null)
        advanceUntilIdle()

        vm.changeDetail(DetailLevel.Detailed)
        assertTrue(vm.state.value.refreshing)
        assertEquals("old result stays until the new one arrives", "short", vm.state.value.displayText)
        advanceUntilIdle()

        assertFalse(vm.state.value.refreshing)
        assertEquals("a much longer description", vm.state.value.displayText)
        assertEquals(listOf(DetailLevel.Standard, DetailLevel.Detailed), repo.describeDetails)
        assertEquals(listOf("s1"), repo.deleted)
    }

    @Test fun retakeForgetsEverythingAndDeletesTheServerSession() = runTest(dispatcher) {
        repo.describeResults += Result.success(described(session = "s1"))
        val vm = vm()
        vm.submitPhoto(byteArrayOf(1), null)
        advanceUntilIdle()

        vm.events.test {
            vm.retake()
            advanceUntilIdle()
            assertEquals(SessionEvent.Retake, awaitItem())
        }
        assertEquals(Phase.Idle, vm.state.value.phase)
        assertEquals("", vm.state.value.displayText)
        assertEquals(listOf("s1"), repo.deleted)
        assertTrue(speaker.stops > 0)
    }

    @Test fun commandWindowOpensAfterTheDescriptionIsRead_andRetakeWorksByVoice() = runTest(dispatcher) {
        repo.describeResults += Result.success(described(session = "s1"))
        val vm = vm()
        vm.submitPhoto(byteArrayOf(1), null)
        advanceUntilIdle()
        assertEquals(0, voice.starts)

        // The description finished being spoken: listen once for a command.
        speaker.finishLast()
        advanceUntilIdle()
        assertEquals(1, voice.starts)

        vm.events.test {
            voice.emit(VoiceEvent.Final("retake"))
            advanceUntilIdle()
            assertEquals(SessionEvent.Retake, awaitItem())
        }
    }

    @Test fun commandWindowCanBeSwitchedOff() = runTest(dispatcher) {
        settings.flow.value = settings.flow.value.copy(voiceCommands = false)
        repo.describeResults += Result.success(described(session = "s1"))
        val vm = vm()
        vm.submitPhoto(byteArrayOf(1), null)
        advanceUntilIdle()
        speaker.finishLast()
        advanceUntilIdle()
        assertEquals(0, voice.starts)
    }

    private suspend fun TestScope.openCommandWindow(vm: SessionViewModel) {
        vm.submitPhoto(byteArrayOf(1), null)
        advanceUntilIdle()
        speaker.finishLast()
        advanceUntilIdle()
        assertEquals(1, voice.starts)
    }

    @Test fun specialRequestWithAQuestionIsAnsweredAtOnce() = runTest(dispatcher) {
        repo.describeResults += Result.success(described(session = "s1"))
        repo.askResults += Result.success(AskResponse("Yes, it is tucked in.", "Yes, it is tucked in.", 0.9f))
        val vm = vm()
        openCommandWindow(vm)
        voice.emit(VoiceEvent.Final("Special request, is my shirt tucked in"))
        advanceUntilIdle()
        assertEquals(listOf("is my shirt tucked in"), repo.questions)
        assertEquals("Yes, it is tucked in.", vm.state.value.displayText)
    }

    @Test fun specialRequestAloneOpensTheMicForTheQuestion() = runTest(dispatcher) {
        repo.describeResults += Result.success(described(session = "s1"))
        val vm = vm()
        openCommandWindow(vm)
        voice.emit(VoiceEvent.Final("special request"))
        advanceUntilIdle()
        assertEquals(AskPhase.Listening, vm.state.value.ask)
        assertEquals(2, voice.starts)
        assertTrue(repo.questions.isEmpty())
    }

    @Test fun speechWithoutTheKeyPhraseIsNeverSentAnywhere() = runTest(dispatcher) {
        repo.describeResults += Result.success(described(session = "s1"))
        val vm = vm()
        openCommandWindow(vm)
        voice.emit(VoiceEvent.SpeechStarted)
        voice.emit(VoiceEvent.Final("is my shirt tucked in"))
        advanceUntilIdle()
        assertTrue(repo.questions.isEmpty())
        assertEquals(AskPhase.Idle, vm.state.value.ask)
    }

    @Test fun droppedConnectionWhileAskingIsRetriedBeforeGivingUp() = runTest(dispatcher) {
        repo.describeResults += Result.success(described(session = "s1"))
        repo.askResults += Result.failure(AppError.NetworkTimeout("slow"))
        repo.askResults += Result.failure(AppError.Offline())
        repo.askResults += Result.success(AskResponse("It is neat.", "It is neat.", 0.9f))
        val vm = vm()
        vm.submitPhoto(byteArrayOf(1), null)
        advanceUntilIdle()
        vm.startAsking()
        voice.emit(VoiceEvent.Final("is it neat"))
        advanceUntilIdle()
        assertEquals(3, repo.questions.size)
        assertEquals("It is neat.", vm.state.value.displayText)
    }

    @Test fun saidAnywhere_adannaWithAQuestionAnswersAboutThePhoto() = runTest(dispatcher) {
        repo.describeResults += Result.success(described(session = "s1"))
        repo.askResults += Result.success(AskResponse("It is neat.", "It is neat.", 0.9f))
        val vm = vm()
        vm.submitPhoto(byteArrayOf(1), null)
        advanceUntilIdle()
        wake.wake("is my shirt neat")
        advanceUntilIdle()
        assertEquals(listOf("is my shirt neat"), repo.questions)
    }

    @Test fun saidAnywhere_adannaThenARequestOpensATool() = runTest(dispatcher) {
        val vm = vm()
        advanceUntilIdle()
        vm.actions.test {
            wake.wake()
            advanceUntilIdle()
            assertEquals(1, voice.starts)
            assertEquals(AskPhase.Listening, vm.state.value.ask)
            voice.emit(VoiceEvent.Final("read this"))
            advanceUntilIdle()
            assertEquals(ai.visionmirror.assistant.AssistantAction.OpenRead, awaitItem())
            assertEquals(AskPhase.Idle, vm.state.value.ask)
        }
    }

    @Test fun adannaWithAToolRequestInOneBreath() = runTest(dispatcher) {
        val vm = vm()
        advanceUntilIdle()
        vm.actions.test {
            wake.wake("look around")
            advanceUntilIdle()
            assertEquals(ai.visionmirror.assistant.AssistantAction.OpenLook, awaitItem())
        }
    }
}
