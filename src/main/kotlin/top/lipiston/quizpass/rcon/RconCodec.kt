package top.lipiston.quizpass.rcon

import top.lipiston.quizpass.data.RconData
import top.lipiston.quizpass.data.type.ClientToServerRequestType
import top.lipiston.quizpass.data.type.ServerToClientRequestType
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

/**
 * Encoder/decoder for the RCON frame format carried inside binary WebSocket frames.
 *
 * Wire layout — every integer is **little-endian**:
 *
 * ```
 * size(4) | requestId(4) | requestType(4) | body(size - 10) | padding(2)
 * ```
 *
 * `size` counts requestId + requestType + body + the two trailing padding bytes, i.e. everything
 * except the `size` field itself. Therefore the body length is `size - 4 - 4 - 2` and the body
 * always starts at byte offset 12.
 *
 * Both operations are pure functions so they can be unit-tested without a socket.
 */
object RconCodec {

    /** Bytes preceding the body: the `size`, `requestId` and `requestType` fields. */
    const val HEADER_SIZE = 12

    /** The frame always ends with two unused padding bytes. */
    const val PADDING_SIZE = 2

    /** Smallest legal `size` value: requestId + requestType + padding, with an empty body. */
    private const val MIN_SIZE = 4 + 4 + PADDING_SIZE

    fun encode(requestId: Int, requestType: ClientToServerRequestType, body: String): ByteArray {
        val bodyBytes = body.toByteArray(StandardCharsets.UTF_8)
        val size = 4 + 4 + bodyBytes.size + PADDING_SIZE

        return ByteBuffer.allocate(4 + size)
            .order(ByteOrder.LITTLE_ENDIAN)
            .apply {
                putInt(size)
                putInt(requestId)
                putInt(requestType.type)
                put(bodyBytes)
                put(0.toByte())
                put(0.toByte())
            }
            .array()
    }

    /**
     * Parses one complete frame. Returns `null` when the bytes cannot be a valid frame instead of
     * throwing, so a single bad frame cannot tear down the connection handler.
     */
    fun decode(data: ByteArray): RconData? {
        if (data.size < HEADER_SIZE) return null

        // Encoding is little-endian, so decoding must be too.
        val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val size = buffer.getInt()
        val requestId = buffer.getInt()
        val requestType = buffer.getInt()

        if (size < MIN_SIZE) return null

        // The declared body length must fit inside the bytes we actually received.
        val bodyLength = size - 4 - 4 - PADDING_SIZE
        if (HEADER_SIZE + bodyLength + PADDING_SIZE > data.size) return null

        val type = try {
            ServerToClientRequestType.of(requestType)
        } catch (_: IllegalArgumentException) {
            return null
        }

        val payload = if (bodyLength > 0) {
            String(data, HEADER_SIZE, bodyLength, StandardCharsets.UTF_8)
        } else {
            ""
        }

        return RconData(requestId, type, payload)
    }
}
