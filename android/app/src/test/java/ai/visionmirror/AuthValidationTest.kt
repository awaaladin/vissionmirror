package ai.visionmirror

import ai.visionmirror.account.AuthMode
import ai.visionmirror.account.AuthValidation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthValidationTest {
    @Test fun goodAndBadEmails() {
        for (ok in listOf("a@b.co", "ada.o@example.com", " ada@example.ng ", "x+tag@mail.example.org")) assertNull(ok, AuthValidation.email(ok))
        for (bad in listOf("", "   ", "ada", "ada@", "@example.com", "ada@example", "ada @example.com", "ada@@example.com")) {
            assertNotNull(bad, AuthValidation.email(bad))
        }
    }

    @Test fun signUpNeedsEightCharactersSignInOnlyNeedsSomething() {
        assertNotNull(AuthValidation.password("short", signUp = true))
        assertNull(AuthValidation.password("longenough", signUp = true))
        assertNull(AuthValidation.password("x", signUp = false)) // existing accounts may predate the rule; the server decides
        assertNotNull(AuthValidation.password("", signUp = false))
        assertNotNull(AuthValidation.password("a".repeat(129), signUp = true))
    }

    @Test fun theTwoPasswordsMustMatchOnlyWhenCreatingAnAccount() {
        assertNull(AuthValidation.check(AuthMode.SignUp, "a@b.co", "longenough", "longenough").first)
        assertEquals("The two passwords don't match.", AuthValidation.check(AuthMode.SignUp, "a@b.co", "longenough", "longenougH").confirm)
        assertNull(AuthValidation.check(AuthMode.SignIn, "a@b.co", "whatever", "").confirm)
    }

    @Test fun theFirstProblemIsTheOneSpokenAndEmailComesFirst() {
        val e = AuthValidation.check(AuthMode.SignUp, "nope", "abc", "xyz")
        assertTrue(e.any)
        assertEquals(e.email, e.first)
        assertFalse(AuthValidation.check(AuthMode.SignIn, "a@b.co", "pw", "").any)
    }
}
