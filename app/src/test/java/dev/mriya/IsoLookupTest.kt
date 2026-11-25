package dev.mriya

import dev.mriya.data.PassportRepository
import org.junit.Assert.assertEquals
import org.junit.Test

class IsoLookupTest {
    @Test
    fun `localized and variant names resolve to one ISO code`() {
        assertEquals("UA", PassportRepository.isoFor("Ukraine"))
        assertEquals("UA", PassportRepository.isoFor("Україна"))
        assertEquals("DE", PassportRepository.isoFor("Deutschland"))
        assertEquals("US", PassportRepository.isoFor("United States of America"))
        assertEquals("US", PassportRepository.isoFor("United States"))
    }
}
