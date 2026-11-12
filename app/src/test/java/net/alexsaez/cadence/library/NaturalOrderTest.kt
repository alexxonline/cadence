package net.alexsaez.cadence.library

import org.junit.Assert.assertEquals
import org.junit.Test

class NaturalOrderTest {
    @Test
    fun `numbers are compared by value`() {
        val names = listOf("Part 10.mp3", "part 2.mp3", "Part 1.mp3", "Part 01b.mp3", "Intro.mp3", "Part 100.mp3")
        assertEquals(
            listOf("Intro.mp3", "Part 1.mp3", "Part 01b.mp3", "part 2.mp3", "Part 10.mp3", "Part 100.mp3"),
            names.sortedWith(NaturalOrder),
        )
    }

    @Test
    fun `leading zeros and huge numbers`() {
        assertEquals(
            listOf("001.mp3", "02.mp3", "10.mp3", "99999999999999999999.mp3"),
            listOf("10.mp3", "99999999999999999999.mp3", "02.mp3", "001.mp3").sortedWith(NaturalOrder),
        )
    }
}
