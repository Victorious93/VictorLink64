package org.victoriousframework.victorlink64.apkplayer

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Thrown when an APK or its binary manifest cannot be read. The message is safe to show to a person. */
class ApkParseException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** One attribute of an element in Android's compiled (binary) XML. */
class AxmlAttribute(
    val name: String,
    val namespace: String?,
    /** `Res_value` data type: 0x01 reference, 0x03 string, 0x10 decimal int, 0x11 hex int, 0x12 boolean. */
    val type: Int,
    val data: Int,
    val rawString: String?,
) {
    val isReference get() = type == TYPE_REFERENCE
    val isInt get() = type == TYPE_INT_DEC || type == TYPE_INT_HEX
    val isString get() = type == TYPE_STRING

    /** The attribute as text when it is a literal string; null for references and numbers. */
    val stringValue: String? get() = if (isString) rawString else null

    /** The attribute as an integer when it is numeric, or a decimal string such as `"19"`. */
    val intValue: Int? get() = when {
        isInt -> data
        isString -> rawString?.trim()?.toIntOrNull()
        else -> null
    }

    companion object {
        const val TYPE_REFERENCE = 0x01
        const val TYPE_STRING = 0x03
        const val TYPE_INT_DEC = 0x10
        const val TYPE_INT_HEX = 0x11
        const val TYPE_INT_BOOLEAN = 0x12
    }
}

interface AxmlHandler {
    fun startElement(name: String, attributes: List<AxmlAttribute>)
    fun endElement(name: String)
}

/**
 * Minimal reader for the binary XML that aapt/aapt2 produce (`AndroidManifest.xml` inside an APK).
 * It only supports what is needed to read the manifest: string pool, resource map and element events.
 * Every read is bounds checked; malformed input raises [ApkParseException] and never crashes the caller.
 */
object AxmlReader {
    private const val RES_XML_TYPE = 0x0003
    private const val RES_STRING_POOL_TYPE = 0x0001
    private const val RES_XML_RESOURCE_MAP_TYPE = 0x0180
    private const val RES_XML_START_ELEMENT_TYPE = 0x0102
    private const val RES_XML_END_ELEMENT_TYPE = 0x0103
    private const val UTF8_FLAG = 1 shl 8

    /** Framework resource ids used when an obfuscated manifest strips attribute names. */
    private val KNOWN_ATTR_IDS = mapOf(
        0x01010001 to "label",
        0x01010002 to "icon",
        0x01010003 to "name",
        0x0101020c to "minSdkVersion",
        0x01010270 to "targetSdkVersion",
        0x0101021b to "versionCode",
        0x0101021c to "versionName",
        0x01010024 to "value",
        0x01010025 to "resource",
        0x01010026 to "targetActivity",
        0x01010271 to "maxSdkVersion",
    )

    fun parse(bytes: ByteArray, handler: AxmlHandler) {
        try {
            parseUnchecked(bytes, handler)
        } catch (e: ApkParseException) {
            throw e
        } catch (e: RuntimeException) {
            throw ApkParseException("The app's manifest is damaged or in an unknown format.", e)
        }
    }

    private fun parseUnchecked(bytes: ByteArray, handler: AxmlHandler) {
        if (bytes.size < 8) throw ApkParseException("The app's manifest is empty or truncated.")
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (buf.getShort(0).toInt() and 0xFFFF != RES_XML_TYPE) {
            throw ApkParseException("The app's manifest is not compiled Android XML.")
        }
        val declared = buf.getInt(4)
        if (declared < 8 || declared > bytes.size) throw ApkParseException("The app's manifest is truncated.")
        val total = declared
        var pos = buf.getShort(2).toInt() and 0xFFFF
        var strings: List<String> = emptyList()
        var resIds: IntArray = IntArray(0)
        val stack = ArrayList<String>()

        while (pos + 8 <= total) {
            val type = buf.getShort(pos).toInt() and 0xFFFF
            val headerSize = buf.getShort(pos + 2).toInt() and 0xFFFF
            val size = buf.getInt(pos + 4)
            if (size < 8 || headerSize < 8 || pos + size > total) {
                throw ApkParseException("The app's manifest is damaged (bad chunk size).")
            }
            when (type) {
                RES_STRING_POOL_TYPE -> strings = readStringPool(buf, pos, size)
                RES_XML_RESOURCE_MAP_TYPE -> {
                    val n = (size - headerSize) / 4
                    resIds = IntArray(n) { buf.getInt(pos + headerSize + it * 4) }
                }
                RES_XML_START_ELEMENT_TYPE -> {
                    val ext = pos + 16
                    val name = str(strings, buf.getInt(ext + 4)) ?: ""
                    val attrStart = buf.getShort(ext + 8).toInt() and 0xFFFF
                    val attrSize = buf.getShort(ext + 10).toInt() and 0xFFFF
                    val attrCount = buf.getShort(ext + 12).toInt() and 0xFFFF
                    if (attrSize < 20 || ext + attrStart + attrCount.toLong() * attrSize > pos + size) {
                        throw ApkParseException("The app's manifest is damaged (bad attribute table).")
                    }
                    val attrs = ArrayList<AxmlAttribute>(attrCount)
                    for (i in 0 until attrCount) {
                        val a = ext + attrStart + i * attrSize
                        val nsIdx = buf.getInt(a)
                        val nameIdx = buf.getInt(a + 4)
                        val rawIdx = buf.getInt(a + 8)
                        val dataType = buf.get(a + 15).toInt() and 0xFF
                        val data = buf.getInt(a + 16)
                        var attrName = str(strings, nameIdx).orEmpty()
                        if (attrName.isEmpty() && nameIdx in resIds.indices) {
                            attrName = KNOWN_ATTR_IDS[resIds[nameIdx]].orEmpty()
                        }
                        val raw = str(strings, rawIdx)
                        // For string typed values the data field is the pool index as well.
                        val rawString = raw ?: if (dataType == AxmlAttribute.TYPE_STRING) str(strings, data) else null
                        attrs.add(AxmlAttribute(attrName, str(strings, nsIdx), dataType, data, rawString))
                    }
                    stack.add(name)
                    handler.startElement(name, attrs)
                }
                RES_XML_END_ELEMENT_TYPE -> {
                    val name = str(strings, buf.getInt(pos + 16 + 4)) ?: ""
                    if (stack.isNotEmpty()) stack.removeAt(stack.size - 1)
                    handler.endElement(name)
                }
            }
            pos += size
        }
    }

    private fun str(pool: List<String>, index: Int): String? =
        if (index >= 0 && index < pool.size) pool[index] else null

    private fun readStringPool(buf: ByteBuffer, chunk: Int, chunkSize: Int): List<String> {
        val count = buf.getInt(chunk + 8)
        val flags = buf.getInt(chunk + 16)
        val stringsStart = buf.getInt(chunk + 20)
        val headerSize = buf.getShort(chunk + 2).toInt() and 0xFFFF
        if (count < 0 || count > 1_000_000 || chunk + headerSize + count.toLong() * 4 > chunk + chunkSize) {
            throw ApkParseException("The app's manifest is damaged (bad string table).")
        }
        val utf8 = flags and UTF8_FLAG != 0
        val limit = chunk + chunkSize
        val out = ArrayList<String>(count)
        for (i in 0 until count) {
            val off = chunk + stringsStart + buf.getInt(chunk + headerSize + i * 4)
            if (off < chunk || off >= limit) {
                out.add("")
                continue
            }
            out.add(if (utf8) readUtf8(buf, off, limit) else readUtf16(buf, off, limit))
        }
        return out
    }

    private fun readUtf16(buf: ByteBuffer, start: Int, limit: Int): String {
        var p = start
        var len = buf.getShort(p).toInt() and 0xFFFF
        p += 2
        if (len and 0x8000 != 0) {
            len = ((len and 0x7FFF) shl 16) or (buf.getShort(p).toInt() and 0xFFFF)
            p += 2
        }
        if (len < 0 || p + len.toLong() * 2 > limit) return ""
        val sb = StringBuilder(len)
        for (i in 0 until len) sb.append((buf.getShort(p + i * 2).toInt() and 0xFFFF).toChar())
        return sb.toString()
    }

    private fun readUtf8(buf: ByteBuffer, start: Int, limit: Int): String {
        var p = start
        // First length is the UTF-16 length (skipped); second is the byte length.
        var b = buf.get(p).toInt() and 0xFF
        p += if (b and 0x80 != 0) 2 else 1
        b = buf.get(p).toInt() and 0xFF
        var len = b
        p += 1
        if (b and 0x80 != 0) {
            len = ((b and 0x7F) shl 8) or (buf.get(p).toInt() and 0xFF)
            p += 1
        }
        if (len < 0 || p + len > limit) return ""
        val data = ByteArray(len)
        for (i in 0 until len) data[i] = buf.get(p + i)
        return String(data, Charsets.UTF_8)
    }
}
