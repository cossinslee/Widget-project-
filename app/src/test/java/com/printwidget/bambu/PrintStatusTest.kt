package com.printwidget.bambu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrintStatusTest {
    private val printer = Printer("01S00A000000001", "My P1S", "P1S", online = true)
    private val start = PrintStatus.offline(printer, 0).copy(state = "NO_DATA")

    @Test fun fullReportIsParsed() {
        val payload = """{"print":{"command":"push_status","gcode_state":"RUNNING","mc_percent":42,
            "mc_remaining_time":95,"subtask_name":"Benchy.3mf","layer_num":80,"total_layer_num":200}}"""
        val s = PrintStatus.merge(start, payload, now = 1_000)!!
        assertEquals("RUNNING", s.state)
        assertEquals(42, s.percent)
        assertEquals(95, s.remainingMinutes)
        assertEquals("Benchy", s.jobName)
        assertEquals(80, s.layer)
        assertEquals(200, s.totalLayers)
        assertTrue(s.isActive)
        assertEquals(1_000 + 95 * 60_000L, s.finishAt)
    }

    @Test fun partialReportKeepsEarlierFields() {
        val full = PrintStatus.merge(start, """{"print":{"gcode_state":"RUNNING","mc_percent":10,"mc_remaining_time":120,"subtask_name":"Vase"}}""", 0)!!
        val partial = PrintStatus.merge(full, """{"print":{"mc_remaining_time":118}}""", 60_000)!!
        assertEquals("RUNNING", partial.state)
        assertEquals("Vase", partial.jobName)
        assertEquals(10, partial.percent)
        assertEquals(118, partial.remainingMinutes)
    }

    @Test fun partialReportWithoutStateDoesNotClaimNoData() {
        val s = PrintStatus.merge(start, """{"print":{"mc_percent":5}}""", 0)!!
        assertEquals("IDLE", s.state)
    }

    @Test fun nonPrintMessagesAreIgnored() {
        assertNull(PrintStatus.merge(start, """{"info":{"command":"get_version"}}""", 0))
        assertNull(PrintStatus.merge(start, """{"print":{"command":"extrusion_cali_get"}}""", 0))
        assertNull(PrintStatus.merge(start, "not json", 0))
    }

    @Test fun jsonRoundTrip() {
        val s = PrintStatus("a", "b", "PAUSE", "c", 1, 2, 3, 4, 5)
        assertEquals(s, PrintStatus.fromJson(s.toJson()))
    }

    @Test fun autoPicksActivePrintFinishingSoonest() {
        val idle = PrintStatus("1", "A", "IDLE", "", 0, 0, 0, 0, 0)
        val long = PrintStatus("2", "B", "RUNNING", "x", 10, 300, 0, 0, 0)
        val short = PrintStatus("3", "C", "RUNNING", "y", 90, 12, 0, 0, 0)
        assertEquals("3", Store.pickStatus(listOf(idle, long, short), "")!!.serial)
        assertEquals("1", Store.pickStatus(listOf(idle, long, short), "1")!!.serial)
        assertEquals("1", Store.pickStatus(listOf(idle), "")!!.serial)
        // A chosen printer that no longer exists falls back to automatic.
        assertEquals("3", Store.pickStatus(listOf(idle, long, short), "gone")!!.serial)
    }
}
