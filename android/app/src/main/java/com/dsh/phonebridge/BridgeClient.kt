package com.dsh.phonebridge

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.interfaces.ECPublicKey
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class BridgeClient(
    private val host: String,
    private val port: Int,
    private val code: String?,
    private val token: String?,
    private val deviceId: String,
    private val listener: Listener,
    private val wsUrl: String? = null,
) {
    interface Listener {
        fun onState(state: String, detail: String)
        fun onCommand(id: Long, command: String, args: JSONObject)
        fun onToken(token: String)
    }

    private val thread = Thread({ run() }, "dsh-phone-bridge")
    @Volatile private var running = true
    @Volatile private var socket: Socket? = null
    @Volatile private var webSocket: WebSocket? = null
    private var output: OutputStream? = null
    private val writeLock = Any()
    private var sessionKey: ByteArray? = null
    private var outSeq = 0L
    private var inSeq = 0L

    fun start() {
        thread.isDaemon = true
        thread.start()
    }

    fun stop() {
        running = false
        try {
            webSocket?.cancel()
        } catch (_: Throwable) {
        }
        try {
            webSocket?.close(1000, "client stop")
        } catch (_: Throwable) {
        }
        try {
            socket?.close()
        } catch (_: Throwable) {
        }
    }

    private fun run() {
        if (!wsUrl.isNullOrEmpty()) {
            runWebSocket(wsUrl)
            return
        }
        try {
            val target = Socket()
            target.connect(InetSocketAddress(host, port), 10_000)
            target.tcpNoDelay = true
            target.soTimeout = 15_000
            socket = target
            val input = DataInputStream(BufferedInputStream(target.getInputStream()))
            val out = BufferedOutputStream(target.getOutputStream())
            output = out
            handshake(input, out)
            target.soTimeout = 0
            listener.onState("connected", "已连接 $host:$port")
            readLoop(input)
        } catch (error: Throwable) {
            if (running) listener.onState("disconnected", error.message ?: error.javaClass.simpleName)
        } finally {
            running = false
            try {
                webSocket?.close(1000, "client done")
            } catch (_: Throwable) {
            }
            try {
                socket?.close()
            } catch (_: Throwable) {
            }
            socket = null
            webSocket = null
            output = null
        }
    }

    private fun runWebSocket(url: String) {
        val queue = LinkedBlockingQueue<ByteArray>()
        val opened = CountDownLatch(1)
        val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
        val request = Request.Builder().url(url).build()
        val ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                opened.countDown()
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                queue.offer(bytes.toByteArray())
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                queue.offer(EOF)
                opened.countDown()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                queue.offer(EOF)
                opened.countDown()
            }
        })
        webSocket = ws
        try {
            if (!opened.await(15, TimeUnit.SECONDS)) throw IllegalStateException("WebSocket 连接超时")
            val input = QueueInputStream(queue)
            val out = WsOutputStream(ws)
            output = out
            handshake(input, out)
            listener.onState("connected", "已连接 $url")
            readLoop(input)
        } catch (error: Throwable) {
            if (running) listener.onState("disconnected", error.message ?: error.javaClass.simpleName)
        } finally {
            running = false
            try {
                ws.close(1000, "client done")
            } catch (_: Throwable) {
            }
            webSocket = null
            output = null
        }
    }

    private fun handshake(input: InputStream, out: OutputStream) {
        val nonceC = Proto.b64u(Proto.randomBytes(16))
        val hello = JSONObject()
            .put("type", "HELLO")
            .put("deviceId", deviceId)
            .put("nonceC", nonceC)
        if (!code.isNullOrEmpty()) {
            hello.put("key", code)
        } else if (!token.isNullOrEmpty()) {
            hello.put("token", token)
        } else {
            throw IllegalStateException("没有配对码，也没有已保存的登录凭证")
        }
        writeRaw(out, Proto.frame(1, 0, 0, hello.toString().toByteArray(Charsets.UTF_8)))

        val challenge = parse(Proto.readFrame(input), decrypt = false)
        if (challenge.optString("type") == "ERR" || challenge.optBoolean("ok", true) == false) {
            val detail = challenge.optJSONObject("error")?.optString("message").orEmpty()
            val code = challenge.optJSONObject("error")?.optString("code").orEmpty()
            throw IllegalStateException(if (code.isNotEmpty()) "$code: $detail" else detail.ifEmpty { "握手被拒绝" })
        }
        if (challenge.optString("type") != "CHALLENGE") throw IllegalStateException("手机桥返回了意外的握手响应")
        val mode = challenge.getString("mode")
        val salt = Proto.fromB64u(challenge.getString("salt"))
        val nonceS = challenge.getString("nonceS")
        val keyPair = Proto.makeKeyPair()
        val derived = Proto.deriveSession(
            mode = mode,
            secret = if (mode == "key") code ?: "" else token ?: "",
            deviceId = deviceId,
            salt = salt,
            nonceC = nonceC,
            nonceS = nonceS,
            peerPublicRaw = Proto.fromB64u(challenge.getString("serverPub")),
            keyPair = keyPair,
        )
        val auth = JSONObject()
            .put("type", "AUTH")
            .put("clientPub", Proto.b64u(Proto.publicRaw(keyPair.public as ECPublicKey)))
            .put("proofC", Proto.b64u(derived.proofC))
        writeRaw(out, Proto.frame(1, 0, 0, auth.toString().toByteArray(Charsets.UTF_8)))

        sessionKey = derived.sessionKey
        val okFrame = Proto.readFrame(input)
        val ok = parse(okFrame, decrypt = true)
        if (ok.optString("type") != "OK") {
            throw IllegalStateException(ok.optJSONObject("error")?.optString("message") ?: "配对失败")
        }
        val proofS = Proto.fromB64u(ok.getString("proofS"))
        if (!MessageDigest.isEqual(proofS, derived.proofS)) throw IllegalStateException("服务端身份校验失败")
        val issued = ok.optString("token")
        if (issued.isNotEmpty()) listener.onToken(issued)
    }

    private fun readLoop(input: InputStream) {
        while (running) {
            val frame = Proto.readFrame(input)
            val message = parse(frame, decrypt = true)
            when (message.optString("type")) {
                "CMD" -> listener.onCommand(
                    message.optLong("id"),
                    message.optString("cmd"),
                    message.optJSONObject("args") ?: JSONObject(),
                )
                "PING" -> sendJson(JSONObject().put("type", "PONG"))
                "RESULT" -> Unit
            }
        }
    }

    private fun parse(frame: Proto.Frame, decrypt: Boolean): JSONObject {
        val bytes = if (decrypt && (frame.flags and 1) != 0) {
            if (frame.seq <= inSeq) throw IllegalStateException("收到重放帧")
            inSeq = frame.seq
            Proto.open(frame, sessionKey ?: throw IllegalStateException("加密帧早于会话密钥"))
        } else {
            frame.payload
        }
        return JSONObject(String(bytes, Charsets.UTF_8))
    }

    fun sendResult(id: Long, ok: Boolean, result: JSONObject?, errorCode: String?, errorMessage: String?) {
        val message = JSONObject().put("type", "RESULT").put("id", id).put("ok", ok)
        if (ok) {
            message.put("result", result ?: JSONObject())
        } else {
            message.put(
                "error",
                JSONObject()
                    .put("code", errorCode ?: "PHONE_ERROR")
                    .put("message", errorMessage ?: "手机执行失败"),
            )
        }
        sendJson(message)
    }

    private fun sendJson(message: JSONObject) {
        val key = sessionKey ?: throw IllegalStateException("尚未完成握手")
        val out = output ?: throw IllegalStateException("连接已关闭")
        synchronized(writeLock) {
            outSeq += 1
            val bytes = Proto.seal(1, outSeq, key, message.toString().toByteArray(Charsets.UTF_8))
            writeRaw(out, bytes)
        }
    }

    private fun writeRaw(out: OutputStream, bytes: ByteArray) {
        synchronized(writeLock) {
            out.write(bytes)
            out.flush()
        }
    }

    private class QueueInputStream(
        private val queue: LinkedBlockingQueue<ByteArray>,
    ) : InputStream() {
        private var current: ByteArray? = null
        private var position = 0

        override fun read(): Int {
            val one = ByteArray(1)
            val count = read(one, 0, 1)
            return if (count <= 0) -1 else one[0].toInt() and 0xff
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            if (current == null) {
                val next = queue.take()
                if (next === EOF) return -1
                current = next
                position = 0
            }
            val chunk = current ?: return -1
            val count = minOf(length, chunk.size - position)
            System.arraycopy(chunk, position, buffer, offset, count)
            position += count
            if (position >= chunk.size) current = null
            return count
        }
    }

    private class WsOutputStream(private val ws: WebSocket) : OutputStream() {
        override fun write(b: Int) {
            write(byteArrayOf(b.toByte()), 0, 1)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (len <= 0) return
            val copy = b.copyOfRange(off, off + len)
            ws.send(copy.toByteString())
        }

        override fun flush() {}
    }

    private companion object {
        private val EOF = ByteArray(0)
    }
}
