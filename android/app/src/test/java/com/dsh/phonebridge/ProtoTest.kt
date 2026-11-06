package com.dsh.phonebridge

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.security.interfaces.ECPublicKey

class ProtoTest {
    @Test
    fun sessionKeysMatchOnBothSides() {
        val phone = Proto.makeKeyPair()
        val server = Proto.makeKeyPair()
        val salt = Proto.randomBytes(16)
        val nonceC = Proto.b64u(Proto.randomBytes(16))
        val nonceS = Proto.b64u(Proto.randomBytes(16))
        val phoneSession = Proto.deriveSession(
            "key", "12345678", "device-1", salt, nonceC, nonceS,
            Proto.publicRaw(server.public as ECPublicKey), phone,
        )
        val serverSession = Proto.deriveSession(
            "key", "12345678", "device-1", salt, nonceC, nonceS,
            Proto.publicRaw(phone.public as ECPublicKey), server,
        )
        assertArrayEquals(phoneSession.sessionKey, serverSession.sessionKey)
        assertArrayEquals(phoneSession.proofC, serverSession.proofC)
        assertArrayEquals(phoneSession.proofS, serverSession.proofS)
        assertEquals(phoneSession.transcript, serverSession.transcript)
    }

    @Test
    fun sealedFrameRoundTripsAndTamperFails() {
        val key = Proto.randomBytes(32)
        val body = "hello 手机".toByteArray(Charsets.UTF_8)
        val wire = Proto.seal(1, 7, key, body)
        val frame = Proto.readFrame(DataInputStream(ByteArrayInputStream(wire)))
        assertEquals(7L, frame.seq)
        assertEquals(1, frame.flags)
        assertArrayEquals(body, Proto.open(frame, key))

        val tampered = wire.copyOf()
        tampered[tampered.size - 1] = (tampered[tampered.size - 1].toInt() xor 1).toByte()
        val bad = Proto.readFrame(DataInputStream(ByteArrayInputStream(tampered)))
        var failed = false
        try {
            Proto.open(bad, key)
        } catch (_: Throwable) {
            failed = true
        }
        assertEquals(true, failed)
    }

    @Test
    fun encryptedSequenceNonceIsStable() {
        val key = Proto.randomBytes(32)
        val a = Proto.seal(1, 42, key, "same".toByteArray())
        val b = Proto.seal(1, 42, key, "same".toByteArray())
        assertArrayEquals(a, b)
    }
}
