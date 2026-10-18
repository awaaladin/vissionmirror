package ai.visionmirror.account

/**
 * Checks what was typed before anything is sent, so a typo is explained at once, next to the field, instead of after a
 * round trip. The server checks again (it never trusts the app), and its wording matches.
 */
object AuthValidation {
    const val MIN_PASSWORD = 8
    const val MAX_PASSWORD = 128

    data class Errors(val email: String? = null, val password: String? = null, val confirm: String? = null) {
        val any: Boolean get() = email != null || password != null || confirm != null
        val first: String? get() = email ?: password ?: confirm
    }

    private val emailShape = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]{2,}$")

    fun email(raw: String): String? = when {
        raw.isBlank() -> "Please type your email address."
        !emailShape.matches(raw.trim()) -> "That doesn't look like an email address. Check for a missing @ or dot."
        else -> null
    }

    fun password(raw: String, signUp: Boolean): String? = when {
        raw.isEmpty() -> "Please type your password."
        signUp && raw.length < MIN_PASSWORD -> "Use at least $MIN_PASSWORD characters."
        signUp && raw.length > MAX_PASSWORD -> "That password is too long. Use $MAX_PASSWORD characters or fewer."
        else -> null
    }

    fun confirm(password: String, confirm: String): String? =
        if (confirm != password) "The two passwords don't match." else null

    fun check(mode: AuthMode, email: String, password: String, confirm: String): Errors {
        val signUp = mode == AuthMode.SignUp
        return Errors(
            email = email(email),
            password = password(password, signUp),
            confirm = if (signUp) confirm(password, confirm) else null,
        )
    }
}
