package ai.visionmirror

import ai.visionmirror.emergency.HumanPlace
import ai.visionmirror.emergency.SpokenInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactDialogTest {
    @Test fun digitsAreUnderstoodHoweverTheyAreSaid() {
        assertEquals("08034123456", SpokenInput.digits("zero eight zero three four one two three four five six"))
        assertEquals("08034123456", SpokenInput.digits("oh eight oh three four one two three four five six"))
        assertEquals("0805512", SpokenInput.digits("zero eight zero double five one two"))
        assertEquals("0805551", SpokenInput.digits("0 8 0 5 5 5 1"))
        assertEquals("08034123456", SpokenInput.digits("08034123456"))
        assertEquals("+2348034", SpokenInput.digits("plus two three four eight zero three four"))
        assertEquals("0802224", SpokenInput.digits("zero eight zero triple two four"))
        assertEquals("0824", SpokenInput.digits("zero eight to for")) // heard as "to" and "for"
    }

    @Test fun numbersAreReadBackOneDigitAtATime() {
        assertEquals("0 8 0 3", SpokenInput.spaced("0803"))
        assertEquals("plus 2 3 4", SpokenInput.spaced("+234"))
    }

    @Test fun namesCanBeSpelled() {
        assertEquals("Mary", SpokenInput.spelled("m a r y"))
        assertEquals("Mary", SpokenInput.spelled("M. A. R. Y."))
        assertEquals("Tunde", SpokenInput.spelled("tee you en dee ee"))
        assertEquals("Wale", SpokenInput.spelled("double u a l e"))
        assertNull(SpokenInput.spelled("Mary"))              // a name said, not spelled
        assertNull(SpokenInput.spelled("my sister"))
    }

    @Test fun spokenNamesAreCleanedUp() {
        assertEquals("Mary Okon", SpokenInput.cleanName("it is mary okon"))
        assertEquals("Tunde", SpokenInput.cleanName("his name is tunde"))
        assertEquals("Mama", SpokenInput.cleanName("Mama"))
    }

    @Test fun sayingDoneIsRecognised() {
        assertTrue(SpokenInput.saysDone("done"))
        assertTrue(SpokenInput.saysDone("that's all"))
        assertTrue(SpokenInput.saysDone("okay I'm done"))
        assertFalse(SpokenInput.saysDone("zero eight zero three"))
    }

    @Test fun shortAnswers() {
        assertTrue(SpokenInput.isYes("yes that is right"))
        assertTrue(SpokenInput.isNo("no that's wrong"))
        assertTrue(SpokenInput.isSave("save"))
        assertTrue(SpokenInput.isSave("yes save it"))
        assertTrue(SpokenInput.isCancel("cancel"))
        assertTrue(SpokenInput.wantsNumberChange("change the number"))
        assertTrue(SpokenInput.wantsNameChange("the name is wrong"))
        assertFalse(SpokenInput.isCancel("save"))
    }

    @Test fun placesAreSaidInWordsNeverAsAPlusCode() {
        assertNull(HumanPlace.clean("XPXF+9Q"))
        assertEquals("Uyo", HumanPlace.clean("XPXF+9Q Uyo"))
        assertEquals("Uyo", HumanPlace.clean("XPXF+9Q, Uyo"))
        assertNull(HumanPlace.clean("12"))
        assertEquals("Ikot Ekpene Road, Uyo, Akwa Ibom State", HumanPlace.compose("Ikot Ekpene Road", null, "Uyo", "Akwa Ibom State"))
        assertEquals("Uyo, Akwa Ibom", HumanPlace.compose("XPXF+9Q", null, "Uyo", "Akwa Ibom"))
        assertEquals("Uyo", HumanPlace.compose(null, "Uyo", "Uyo", null)) // no repeating
        assertNull(HumanPlace.compose("XPXF+9Q", null, null, null))
    }
}
