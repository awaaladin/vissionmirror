package ai.visionmirror.data.api

/**
 * Every failure the user can meet, each carrying a sentence that is safe and kind to read aloud.
 * Server errors bring their own `spoken_text`; local ones (offline) are written here.
 */
sealed class AppError(val spokenText: String, cause: Throwable? = null) : Exception(spokenText, cause) {

    /** No network. Framing and capture still work; the photo waits in memory until we are back online. */
    class Offline(cause: Throwable? = null) : AppError(
        "I can't reach the internet. Position guidance still works, and I'll describe you as soon as you're back online.",
        cause,
    )

    /** 429. [retryAfterSeconds] comes from the Retry-After header. */
    class RateLimited(val retryAfterSeconds: Int, spoken: String) : AppError(spoken)

    /** 404 session_not_found: expired (10 min), deleted, or not ours. A new photo is needed. */
    class SessionExpired(spoken: String) : AppError(spoken)

    class ImageTooLarge(spoken: String) : AppError(spoken)

    class UnsupportedImage(spoken: String) : AppError(spoken)

    /** 401 that survived one token refresh. */
    class Unauthorized(spoken: String) : AppError(spoken)

    class Server(spoken: String) : AppError(spoken)

    /** 422 or an unreadable body: a bug in the app, not something she can fix. */
    class Unexpected(cause: Throwable? = null) : AppError(
        "Something went wrong on my side. Please try again in a moment.",
        cause,
    )

    companion object {
        const val DEFAULT_RETRY_SECONDS = 5
    }
}
