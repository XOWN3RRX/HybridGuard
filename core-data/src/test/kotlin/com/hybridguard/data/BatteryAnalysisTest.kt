package com.hybridguard.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryAnalysisTest {

    private fun dash(
        cells: List<Double?> = emptyList(),
        blocks: List<Double?> = emptyList(),
        resistances: List<Double?> = emptyList(),
        temps: List<Double?> = emptyList(),
        amps: Double? = 1.0,
        intake: Double? = null,
    ) = DashboardState(
        hvVolts = 203.0,
        hvAmps = amps,
        socPct = 55.0,
        values = listOfNotNull(intake?.let { Role.HV_INTAKE_TEMP to it }).toMap(),
        lists = mapOf(
            Role.HV_CELL_VOLTAGE to cells,
            Role.HV_BLOCK_VOLTAGE to blocks,
            Role.HV_CELL_RESISTANCE to resistances,
            Role.HV_BATTERY_TEMP to temps,
        ).filterValues { it.isNotEmpty() },
    )

    private fun liIonCells(n: Int = 56, base: Double = 3.615) = List(n) { base + (it % 3) * 0.002 }

    @Test
    fun `tells lithium-ion from NiMH by the cell or block voltages`() {
        assertEquals(BatteryChemistry.LITHIUM_ION, BatteryAnalysis.detect(Spread(liIonCells()), Spread(emptyList())))
        assertEquals(BatteryChemistry.NIMH, BatteryAnalysis.detect(Spread(emptyList()), Spread(List(14) { 15.2 })))
        assertEquals(BatteryChemistry.NIMH, BatteryAnalysis.detect(Spread(List(168) { 1.25 }), Spread(emptyList())))
        assertNull(BatteryAnalysis.detect(Spread(emptyList()), Spread(emptyList())))
    }

    @Test
    fun `balanced lithium-ion pack at rest is OK`() {
        val r = BatteryAnalysis.analyze(dash(cells = liIonCells()), BatteryProfile())
        assertEquals(BatteryChemistry.LITHIUM_ION, r.chemistry)
        assertEquals(ChemistrySource.READINGS, r.chemistrySource)
        assertEquals("cell", r.unitWord)
        assertTrue(r.atRest == true)
        val balance = r.findings.single { it.title.startsWith("Balanced") }
        assertEquals(FindingLevel.OK, balance.level)
    }

    @Test
    fun `a low cell is flagged at rest but only noted under load`() {
        val cells = liIonCells().toMutableList<Double?>().also { it[22] = 3.540 }
        val rest = BatteryAnalysis.analyze(dash(cells = cells), BatteryProfile())
        val f = rest.findings.first { it.title.startsWith("Imbalance") }
        assertEquals(FindingLevel.CHECK, f.level)
        assertTrue(f.detail, f.detail.startsWith("cell 23 lowest"))

        val load = BatteryAnalysis.analyze(dash(cells = cells, amps = -40.0), BatteryProfile())
        assertEquals(FindingLevel.INFO, load.findings.first { it.title.startsWith("Balance") }.level)
    }

    @Test
    fun `NiMH pack reporting blocks is judged on the blocks with the NiMH limits`() {
        val blocks = List<Double?>(14) { 15.20 }.toMutableList().also { it[5] = 14.95 }
        val r = BatteryAnalysis.analyze(dash(blocks = blocks), BatteryProfile())
        assertEquals(BatteryChemistry.NIMH, r.chemistry)
        assertEquals("block", r.unitWord)
        assertEquals(BatteryLimits.NIMH_DEFAULT, r.limits)
        // 250 mV: above the 200 mV watch limit, below the 300 mV check limit.
        val f = r.findings.first { it.title.startsWith("Imbalance") }
        assertEquals(FindingLevel.WATCH, f.level)
        assertTrue(f.detail, f.detail.startsWith("block 6 lowest"))
    }

    @Test
    fun `the owner's choice and limits win over the profile and the readings`() {
        val cells = liIonCells().toMutableList<Double?>().also { it[0] = 3.595 }
        val profile = BatteryProfile(chemistry = BatteryChemistry.NIMH)
        val fromProfile = BatteryAnalysis.analyze(dash(cells = cells), profile)
        assertEquals(ChemistrySource.PROFILE, fromProfile.chemistrySource)

        val chosen = BatteryAnalysis.analyze(dash(cells = cells), profile, BatteryChemistry.LITHIUM_ION) { _, l ->
            l.withOverrides(mapOf("spreadWarnMv" to 10.0, "spreadBadMv" to 40.0))
        }
        assertEquals(ChemistrySource.SETTINGS, chosen.chemistrySource)
        assertEquals(10.0, chosen.limits!!.spreadWarnMv, 0.0)
        assertEquals(FindingLevel.WATCH, chosen.findings.first { it.title.startsWith("Imbalance") }.level)
    }

    @Test
    fun `resistance, temperature spread and cooling air`() {
        val res = List<Double?>(56) { 3.8 }.toMutableList().also { it[40] = 6.0 }
        val r = BatteryAnalysis.analyze(
            dash(cells = liIonCells(), resistances = res, temps = listOf(20.0, 21.0, 29.5), intake = 12.0),
            BatteryProfile(),
        )
        assertEquals(FindingLevel.CHECK, r.findings.single { it.title.contains("resistance") }.level)
        assertEquals(41, r.resistances.maxIndex)
        assertEquals(FindingLevel.CHECK, r.findings.single { it.title.startsWith("Temperature spread") }.level)
        assertEquals(FindingLevel.WATCH, r.findings.single { it.title.contains("cooling air") }.level)
    }

    @Test
    fun `unknown pack type asks the owner and runs no checks`() {
        val r = BatteryAnalysis.analyze(dash(cells = List(20) { 2.0 }), BatteryProfile())
        assertNull(r.chemistry)
        assertNull(r.limits)
        assertEquals("Battery type unknown", r.findings.single().title)
    }

    @Test
    fun `bundled profile battery file parses and leaves the pack type to the readings`() {
        val p = ProfileLoader().batteryProfile("toyota-corolla-hybrid-e210")
        assertNull(p.chemistry)
        assertEquals(30.0, p.lithiumIon.spreadWarnMv, 0.0)
        assertEquals(200.0, p.nimh.spreadWarnMv, 0.0)
        // The generic profile has none: defaults.
        assertEquals(BatteryProfile(), ProfileLoader().batteryProfile("sae-j1979"))
    }

    @Test
    fun `battery roles are on demand and never main values`() {
        val catalog = ProfileLoader().load("toyota-corolla-hybrid-e210").forModelYear(2019)
        val main = catalog.pollKeys({ !it.onDemand }) { true }
        val cellKeys = catalog.pollKeys({ it == Role.HV_CELL_VOLTAGE || it == Role.HV_CELL_RESISTANCE }) { true }
        assertTrue(cellKeys.isNotEmpty())
        assertFalse(cellKeys.any { it in main })
        assertTrue(Role.BATTERY_TAB.all { it.onDemand })
    }
}
