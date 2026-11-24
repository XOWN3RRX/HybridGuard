package com.hybridguard.data

import kotlin.test.Test
import kotlin.test.assertEquals

/** Table ported from OBDb/.schemas python/can/test_signals.py. */
class CommandFilterTest {

    private data class Case(val filter: CommandFilter, val year: Int?, val expected: Boolean)

    private val cases = listOf(
        Case(CommandFilter(from = 2000, to = 2010), 2005, true),
        Case(CommandFilter(from = 2000, to = 2010), 2000, true),
        Case(CommandFilter(from = 2000, to = 2010), 2010, true),
        Case(CommandFilter(from = 2000, to = 2010), 1999, false),
        Case(CommandFilter(from = 2000, to = 2010), 2011, false),
        Case(CommandFilter(from = 2010, to = 2000), 2015, true),
        Case(CommandFilter(from = 2010, to = 2000), 2010, true),
        Case(CommandFilter(from = 2010, to = 2000), 1995, true),
        Case(CommandFilter(from = 2010, to = 2000), 2000, true),
        Case(CommandFilter(from = 2010, to = 2000), 2005, false),
        Case(CommandFilter(from = 2000), 2005, true),
        Case(CommandFilter(from = 2000), 2000, true),
        Case(CommandFilter(from = 2000), 1999, false),
        Case(CommandFilter(to = 2010), 2005, true),
        Case(CommandFilter(to = 2010), 2010, true),
        Case(CommandFilter(to = 2010), 2011, false),
        Case(CommandFilter(years = setOf(2000, 2005, 2010)), 2005, true),
        Case(CommandFilter(years = setOf(2000, 2005, 2010)), 2003, false),
        Case(CommandFilter(years = emptySet()), 2005, false),
        Case(CommandFilter(from = 2000, to = 2010, years = setOf(2015)), 2005, true),
        Case(CommandFilter(from = 2000, to = 2010, years = setOf(2015)), 2015, true),
        Case(CommandFilter(from = 2000, to = 2010, years = setOf(2005)), 2005, true),
        Case(CommandFilter(from = 2000, to = 2010, years = setOf(2015)), 2012, false),
        Case(CommandFilter(), 2005, false),
        Case(CommandFilter(), null, false),
        Case(CommandFilter(from = 2000), null, false),
        Case(CommandFilter(from = 2000, years = setOf(2005)), 2001, true),
    )

    @Test
    fun matchesLikeTheReference() {
        for (c in cases) assertEquals(c.expected, c.filter.matches(c.year), "$c")
    }

    @Test
    fun unfilteredCommandAppliesToEveryYear() {
        val command = SignalCommand(0x7E0, 0x7E8, 0x01, "0D", 250, emptyList())
        assertEquals(true, command.appliesTo(null))
        assertEquals(true, command.appliesTo(2019))
        assertEquals(false, command.copy(filter = CommandFilter(from = 2023)).appliesTo(2019))
    }
}
