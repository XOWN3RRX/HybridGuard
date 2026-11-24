package com.hybridguard.app.ui

import com.hybridguard.data.AlarmCheck
import com.hybridguard.data.AlarmLevel
import com.hybridguard.data.AlarmRules
import com.hybridguard.data.Role
import com.hybridguard.data.shortLabel

/** The alarm limits drawn on a chart of [role] (the rules the owner set). */
fun thresholdsFor(rules: AlarmRules?, role: Role): List<Threshold> =
    rules?.checks.orEmpty().mapNotNull { c ->
        val critical = c.level == AlarmLevel.STOP
        when (c) {
            is AlarmCheck.TooHot -> if (c.temp == role) Threshold(c.from, limitLabel(role, critical), critical) else null
            is AlarmCheck.TooLow -> if (c.value == role) Threshold(c.below, limitLabel(role, critical), critical) else null
            is AlarmCheck.PumpLow -> if (c.pump == role && c.below != null) Threshold(c.below!!, limitLabel(role, critical), critical) else null
            is AlarmCheck.RisingFast -> null
        }
    }

private fun limitLabel(role: Role, critical: Boolean) = role.shortLabel + if (critical) " STOP" else " caution"

/** How bad [role] is now: the worst alarm on whose check reads it. */
fun levelOf(role: Role, alarmIds: Map<String, AlarmLevel>, rules: AlarmRules?): AlarmLevel? =
    rules?.checks.orEmpty()
        .filter { it.id in alarmIds && mainRole(it) == role }
        .maxOfOrNull { alarmIds.getValue(it.id) }

/** The value a check is about (the pump, the temperature), not what gates it. */
fun mainRole(c: AlarmCheck): Role = when (c) {
    is AlarmCheck.PumpLow -> c.pump
    is AlarmCheck.TooHot -> c.temp
    is AlarmCheck.TooLow -> c.value
    is AlarmCheck.RisingFast -> c.temp
}
