import crypto from 'node:crypto'

export const MAGIC = Buffer.from('DSHP')
export const VERSION = 1
export const HEADER_LEN = 19
export const MAX_FRAME = 8 * 1024 * 1024

export function b64u(buf) {
  return Buffer.from(buf).toString('base64url')
}
export function fromB64u(s) {
  return Buffer.from(s, 'base64url')
}
export function sha256(buf) {
  return crypto.createHash('sha256').update(buf).digest()
}
export function hmac(key, data) {
  return crypto.createHmac('sha256', key).update(data).digest()
}
export function safeEqual(a, b) {
  return a.length === b.length && crypto.timingSafeEqual(a, b)
}

export function genCode() {
  return crypto.randomInt(0, 100000000).toString().padStart(8, '0')
}

export function randomB64u(bytes = 16) {
  return b64u(crypto.randomBytes(bytes))
}

export function makeHeader({ type = 1, flags = 0, seq = 0n, length = 0 }) {
  const h = Buffer.alloc(HEADER_LEN)
  MAGIC.copy(h, 0)
  h[4] = VERSION
  h[5] = type
  h[6] = flags
  h.writeBigUInt64BE(BigInt(seq), 7)
  h.writeUInt32BE(length, 15)
  return h
}

export function encodeFrame({ type = 1, flags = 0, seq = 0n, payload }) {
  const body = Buffer.isBuffer(payload) ? payload : Buffer.from(JSON.stringify(payload))
  return Buffer.concat([makeHeader({ type, flags, seq, length: body.length }), body])
}

export class FrameParser {
  constructor(onFrame) {
    this.onFrame = onFrame
    this.buf = Buffer.alloc(0)
    this.dead = false
  }
  push(chunk) {
    if (this.dead) return
    this.buf = Buffer.concat([this.buf, chunk])
    while (!this.dead && this.buf.length >= HEADER_LEN) {
      if (!this.buf.subarray(0, 4).equals(MAGIC)) {
        this.dead = true
        this.onFrame(null, new Error('bad magic'))
        return
      }
      if (this.buf[4] !== VERSION) {
        this.dead = true
        this.onFrame(null, new Error('bad version'))
        return
      }
      const type = this.buf[5]
      const flags = this.buf[6]
      const seq = this.buf.readBigUInt64BE(7)
      const length = this.buf.readUInt32BE(15)
      if (length > MAX_FRAME) {
        this.dead = true
        this.onFrame(null, new Error('frame too large'))
        return
      }
      if (this.buf.length < HEADER_LEN + length) return
      const header = this.buf.subarray(0, HEADER_LEN)
      const payload = this.buf.subarray(HEADER_LEN, HEADER_LEN + length)
      this.buf = this.buf.subarray(HEADER_LEN + length)
      this.onFrame({ type, flags, seq, payload, header })
    }
  }
}

export function deriveSession({ mode, secret, deviceId, salt, nonceC, nonceS, peerPub, ecdh }) {
  const shared = ecdh.computeSecret(peerPub)
  const psk =
    mode === 'key'
      ? crypto.pbkdf2Sync(String(secret), fromB64u(salt), 200000, 32, 'sha256')
      : sha256(fromB64u(secret))
  const salt2 = sha256(Buffer.concat([fromB64u(salt), fromB64u(nonceC), fromB64u(nonceS)]))
  const sessionKey = Buffer.from(
    crypto.hkdfSync('sha256', Buffer.concat([shared, psk]), salt2, Buffer.from('dsh-phone-v1'), 32),
  )
  const transcript = ['dsh-phone-v1', deviceId, nonceC, nonceS, mode].join('|')
  return {
    sessionKey,
    transcript,
    proofC: hmac(sessionKey, transcript + '|C'),
    proofS: hmac(sessionKey, transcript + '|S'),
  }
}

function nonceFor(seq) {
  const n = Buffer.alloc(12)
  n.writeBigUInt64BE(BigInt(seq), 4)
  return n
}

export function sealFrame({ type = 1, seq, key, payload }) {
  const body = Buffer.isBuffer(payload) ? payload : Buffer.from(JSON.stringify(payload))
  const header = makeHeader({ type, flags: 1, seq, length: body.length + 16 })
  const cipher = crypto.createCipheriv('aes-256-gcm', key, nonceFor(seq))
  cipher.setAAD(header)
  const enc = Buffer.concat([cipher.update(body), cipher.final()])
  return Buffer.concat([header, enc, cipher.getAuthTag()])
}

export function openFrame(frame, key) {
  const ct = frame.payload.subarray(0, frame.payload.length - 16)
  const tag = frame.payload.subarray(frame.payload.length - 16)
  const decipher = crypto.createDecipheriv('aes-256-gcm', key, nonceFor(frame.seq))
  decipher.setAAD(frame.header)
  decipher.setAuthTag(tag)
  return Buffer.concat([decipher.update(ct), decipher.final()])
}

export function makeEcdh() {
  const ecdh = crypto.createECDH('prime256v1')
  ecdh.generateKeys()
  return ecdh
}
