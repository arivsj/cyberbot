package com.cyberbot.mobile

import com.cyberbot.mobile.core.model.PairingPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingPayloadTest {

    // Payload real gerado pelo desktop em /api/m/admin/pair/qr
    private val payload =
        "cyberbot://pair?v=1&c=809245&n=pop-os" +
            "&t=endpointacekpmqe4yiwqdpfpoohwbbxvna5rylf2dlpia56b253tfdeqn4yaayaenuhi5dqom5c6l3vonstcljrfzzgk3dbpexg4mbonfzg62bonruw42zof4aqbpkvlgd47dqcaeambkbk2hhy4aq" +
            "&i=88a7b204e611680de57b9c7b0437ab41d8e165d0d6f403be0ebbb99464837980" +
            "&d=http%3A%2F%2F192.168.42.209%3A5055"

    @Test
    fun parseExtraiCodigoTicketEEnderecos() {
        val parsed = PairingPayload.parse(payload)!!
        assertEquals("809245", parsed.code)
        assertEquals("pop-os", parsed.pcName)
        assertEquals(listOf("http://192.168.42.209:5055"), parsed.directUrls)
        assertEquals("88a7b204e611680de57b9c7b0437ab41d8e165d0d6f403be0ebbb99464837980", parsed.irohEndpointId)
        assertTrue(parsed.irohTicket!!.startsWith("endpointacekpmqe4yiwqdpfpoohwbbxvna5rylf2dl"))
        assertTrue(parsed.hasIroh)
    }

    @Test
    fun parseAceitaVariosEnderecos() {
        val parsed = PairingPayload.parse(
            "cyberbot://pair?c=123456&n=pc&d=http%3A%2F%2F10.0.0.2%3A5055&d=http%3A%2F%2F192.168.0.10%3A5055",
        )!!
        assertEquals(listOf("http://10.0.0.2:5055", "http://192.168.0.10:5055"), parsed.directUrls)
    }

    @Test
    fun parseRecusaPayloadInvalido() {
        assertNull(PairingPayload.parse(null))
        assertNull(PairingPayload.parse(""))
        assertNull(PairingPayload.parse("https://exemplo.com/pair?c=123456"))
        assertNull(PairingPayload.parse("cyberbot://outro?c=123456"))
        assertNull(PairingPayload.parse("cyberbot://pair?c=123"))
        assertNull(PairingPayload.parse("cyberbot://pair?c=abcdef"))
        assertNull(PairingPayload.parse("cyberbot://pair?n=pc"))
    }

    @Test
    fun parseSemIrohMarcaHasIrohFalso() {
        val parsed = PairingPayload.parse("cyberbot://pair?c=654321&n=pc&d=http%3A%2F%2F10.0.0.2%3A5055")!!
        assertTrue(!parsed.hasIroh)
        assertNull(parsed.irohTicket)
    }
}
