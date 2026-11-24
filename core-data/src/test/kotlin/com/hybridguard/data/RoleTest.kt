package com.hybridguard.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RoleTest {

    @Test
    fun keysAreUniqueAndRoundTrip() {
        assertEquals(Role.entries.size, Role.entries.map { it.key }.toSet().size)
        Role.entries.forEach { assertEquals(it, Role.fromKey(it.key)) }
    }

    @Test
    fun unknownKeyIsNull() {
        assertNull(Role.fromKey("COROLLAHYBRID_HVBAT_V"))
    }
}
