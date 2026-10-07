package ai.visionmirror.app

/** Words the app says that are not from the server. Kept in one place so they stay consistent and honest. */
object Spoken {
    const val WELCOME =
        "Hi, I'm VisionMirror, a talking mirror. I'll help you frame yourself, take a photo, and tell you how you look."

    /**
     * Matches docs/privacy.md: nothing is saved on the phone or the server's disk. The photo is held in
     * memory for up to ten minutes only so follow-up questions work, then deleted.
     */
    const val PRIVACY =
        "Your photos are never stored. I keep a photo for a few minutes only so you can ask follow-up questions, then it is deleted."

    const val CONTINUE_HINT = "Tap anywhere, or say continue."

    const val CAMERA_WHY =
        "To guide you, I need to use the front camera. Photos stay private. Tap continue, then choose Allow."

    const val CAMERA_DENIED =
        "Without the camera I can't see you. You can allow it in your phone's settings. Tap continue to try again."

    const val MIC_WHY =
        "To hear your questions, I need the microphone. It is only on when you ask. Tap continue, then choose Allow."

    const val MIC_DENIED =
        "That's fine. You can still use the buttons, and allow the microphone later in your phone's settings."

    const val ALL_SET = "All set. Let's look at you."
}
