package com.dsh.phonebridge

import java.io.InputStream
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.SignatureException
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object Proto {
    const val MAGIC = "DSHP"
    const val VERSION = 1
    const val HEADER_LEN = 19
    const val MAX_FRAME = 8 * 1024 * 1024

    data class Frame(val type: Int, val flags: Int, val seq: Long, val header: ByteArray, val payload: ByteArray)

    private val random = SecureRandom()
    private val urlEncoder: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
    private val urlDecoder: Base64.Decoder = Base64.getUrlDecoder()

    fun randomBytes(size: Int): ByteArray = ByteArray(size).also(random::nextBytes)

    fun b64u(bytes: ByteArray): String = urlEncoder.encodeToString(bytes)

    fun fromB64u(text: String): ByteArray = urlDecoder.decode(text)

    fun sha256(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(data)

    fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    fun pbkdf2(secret: String, salt: ByteArray): ByteArray {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(secret.toCharArray(), salt, 200_000, 256)
        return factory.generateSecret(spec).encoded
    }

    fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val prk = hmac(salt, ikm)
        var previous = ByteArray(0)
        var result = ByteArray(0)
        var counter = 1
        while (result.size < length) {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(prk, "HmacSHA256"))
            mac.update(previous)
            mac.update(info)
            mac.update(counter.toByte())
            previous = mac.doFinal()
            result += previous
            counter++
        }
        return result.copyOf(length)
    }

    private val ecParams: ECParameterSpec by lazy {
        val parameters = java.security.AlgorithmParameters.getInstance("EC")
        parameters.init(ECGenParameterSpec("secp256r1"))
        parameters.getParameterSpec(ECParameterSpec::class.java)
    }

    fun makeKeyPair(): KeyPair {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"), random)
        return generator.generateKeyPair()
    }

    private fun fixed32(value: BigInteger): ByteArray {
        val raw = value.toByteArray()
        return when {
            raw.size == 32 -> raw
            raw.size > 32 -> raw.copyOfRange(raw.size - 32, raw.size)
            else -> ByteArray(32 - raw.size) + raw
        }
    }

    fun publicRaw(publicKey: ECPublicKey): ByteArray {
        val point = publicKey.w
        return byteArrayOf(4) + fixed32(point.affineX) + fixed32(point.affineY)
    }

    fun parsePublic(raw: ByteArray): ECPublicKey {
        if (raw.size != 65 || raw[0].toInt() != 4) throw IllegalArgumentException("bad public key")
        val x = BigInteger(1, raw.copyOfRange(1, 33))
        val y = BigInteger(1, raw.copyOfRange(33, 65))
        val keyFactory = KeyFactory.getInstance("EC")
        return keyFactory.generatePublic(ECPublicKeySpec(ECPoint(x, y), ecParams)) as ECPublicKey
    }

    fun sharedSecret(keyPair: KeyPair, peerPublicRaw: ByteArray): ByteArray {
        val agreement = KeyAgreement.getInstance("ECDH")
        agreement.init(keyPair.private)
        agreement.doPhase(parsePublic(peerPublicRaw), true)
        return agreement.generateSecret()
    }

    data class Session(val sessionKey: ByteArray, val transcript: String, val proofC: ByteArray, val proofS: ByteArray)

    fun deriveSession(
        mode: String,
        secret: String,
        deviceId: String,
        salt: ByteArray,
        nonceC: String,
        nonceS: String,
        peerPublicRaw: ByteArray,
        keyPair: KeyPair,
    ): Session {
        val shared = sharedSecret(keyPair, peerPublicRaw)
        val psk = if (mode == "key") pbkdf2(secret, salt) else sha256(fromB64u(secret))
        val salt2 = sha256(salt + fromB64u(nonceC) + fromB64u(nonceS))
        val sessionKey = hkdf(shared + psk, salt2, "dsh-phone-v1".toByteArray(Charsets.UTF_8), 32)
        val transcript = listOf("dsh-phone-v1", deviceId, nonceC, nonceS, mode).joinToString("|")
        return Session(
            sessionKey = sessionKey,
            transcript = transcript,
            proofC = hmac(sessionKey, (transcript + "|C").toByteArray(Charsets.UTF_8)),
            proofS = hmac(sessionKey, (transcript + "|S").toByteArray(Charsets.UTF_8)),
        )
    }

    private fun nonceFor(seq: Long): ByteArray {
        val nonce = ByteArray(12)
        for (index in 0 until 8) {
            nonce[11 - index] = (seq ushr (8 * index)).toByte()
        }
        return nonce
    }

    fun frame(type: Int, flags: Int, seq: Long, body: ByteArray): ByteArray {
        val header = ByteArray(HEADER_LEN)
        MAGIC.toByteArray(Charsets.US_ASCII).copyInto(header, 0)
        header[4] = VERSION.toByte()
        header[5] = type.toByte()
        header[6] = flags.toByte()
        for (index in 0 until 8) {
            header[7 + index] = (seq ushr (8 * (7 - index))).toByte()
        }
        val length = body.size
        for (index in 0 until 4) {
            header[15 + index] = (length ushr (8 * (3 - index))).toByte()
        }
        return header + body
    }

    fun seal(type: Int, seq: Long, key: ByteArray, body: ByteArray): ByteArray {
        val header = ByteArray(HEADER_LEN)
        MAGIC.toByteArray(Charsets.US_ASCII).copyInto(header, 0)
        header[4] = VERSION.toByte()
        header[5] = type.toByte()
        header[6] = 1
        for (index in 0 until 8) {
            header[7 + index] = (seq ushr (8 * (7 - index))).toByte()
        }
        val total = body.size + 16
        for (index in 0 until 4) {
            header[15 + index] = (total ushr (8 * (3 - index))).toByte()
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonceFor(seq)))
        cipher.updateAAD(header)
        return header + cipher.doFinal(body)
    }

    fun open(frame: Frame, key: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonceFor(frame.seq)))
        cipher.updateAAD(frame.header)
        return cipher.doFinal(frame.payload)
    }

    fun readFrame(input: InputStream): Frame {
        val header = ByteArray(HEADER_LEN)
        readFully(input, header)
        if (String(header, 0, 4, Charsets.US_ASCII) != MAGIC) throw SignatureException("bad magic")
        if (header[4].toInt() and 0xff != VERSION) throw SignatureException("bad version")
        val type = header[5].toInt() and 0xff
        val flags = header[6].toInt() and 0xff
        var seq = 0L
        for (index in 0 until 8) {
            seq = (seq shl 8) or (header[7 + index].toLong() and 0xffL)
        }
        var length = 0
        for (index in 0 until 4) {
            length = (length shl 8) or (header[15 + index].toInt() and 0xff)
        }
        if (length < 0 || length > MAX_FRAME) throw SignatureException("bad frame length")
        val payload = ByteArray(length)
        readFully(input, payload)
        return Frame(type, flags, seq, header, payload)
    }

    private fun readFully(input: InputStream, target: ByteArray) {
        var offset = 0
        while (offset < target.size) {
            val count = input.read(target, offset, target.size - offset)
            if (count < 0) throw java.io.EOFException("connection closed")
            offset += count
        }
    }
}
