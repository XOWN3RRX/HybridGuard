package com.hybridguard.obd

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ReadOnlyPolicyTest {

    /** Services CLAUDE.md names explicitly as never to be sent. */
    private val explicitlyForbidden = listOf(
        0x04, // clear DTCs
        0x10, // diagnostic session control
        0x11, // ECU reset
        0x14, // clear diagnostic information
        0x27, // security access
        0x28, // communication control
        0x2E, // write data by identifier
        0x2F, // input/output control
        0x30, // input/output control (local id)
        0x31, // routine control
        0x3B, // write data by local identifier
        0x85, // control DTC setting
    )

    @Test
    fun allowListIsExactlyTheReadServices() {
        assertEquals(setOf(0x01, 0x09, 0x21, 0x22, 0x3E), ReadOnlyPolicy.ALLOWED_SERVICES)
    }

    @Test
    fun explicitlyForbiddenServicesThrow() {
        for (service in explicitlyForbidden) {
            val e = assertFailsWith<ForbiddenServiceException>("service 0x%02X".format(service)) {
                ReadOnlyPolicy.requireAllowed(service)
            }
            assertEquals(service, e.service)
        }
    }

    @Test
    fun everyByteOutsideTheAllowListThrows() {
        for (service in 0x00..0xFF) {
            if (service in ReadOnlyPolicy.ALLOWED_SERVICES) {
                ReadOnlyPolicy.requireAllowed(service) // must not throw
            } else {
                assertFailsWith<ForbiddenServiceException>("service 0x%02X".format(service)) {
                    ReadOnlyPolicy.requireAllowed(service)
                }
            }
        }
    }

    @Test
    fun forbiddenRequestCannotBeConstructed() {
        for (service in explicitlyForbidden) {
            assertFailsWith<ForbiddenServiceException> {
                ObdRequest(header = 0x7E0, service = service, pid = byteArrayOf(0x01), periodMs = 1000, multiFrame = false)
            }
        }
    }

    @Test
    fun allowedRequestsCanBeConstructed() {
        val request = ObdRequest(
            header = 0x7D2,
            service = 0x22,
            pid = byteArrayOf(0x1F, 0x9A.toByte()),
            periodMs = 250,
            multiFrame = false,
        )
        assertEquals("221F9A", request.payloadHex)
        assertEquals("7D2", request.headerHex)
    }

    @Test
    fun payloadCheckRejectsForbiddenHex() {
        listOf("2E1F9A00", "10 03", "3101FF00", "04", "1101", "27 01", "851234").forEach { payload ->
            assertFailsWith<ForbiddenServiceException>(payload) {
                ReadOnlyPolicy.requireAllowedPayload(payload)
            }
        }
    }

    @Test
    fun payloadCheckAcceptsReadHex() {
        listOf("010C", "01 0C 0D 05", "0902", "2101", "221F9A", "3E00").forEach { payload ->
            ReadOnlyPolicy.requireAllowedPayload(payload)
        }
    }

    @Test
    fun payloadCheckRejectsGarbage() {
        listOf("", " ", "Z1", "G0").forEach { payload ->
            assertFailsWith<IllegalArgumentException>(payload) {
                ReadOnlyPolicy.requireAllowedPayload(payload)
            }
        }
    }
}
