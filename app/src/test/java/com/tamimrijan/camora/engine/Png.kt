package com.tamimrijan.camora.engine

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream

/** Tiny PNG encoder for test renders (javax.imageio is not on the Android unit-test classpath). */
object Png {
    fun encode(img: RgbImage): ByteArray {
        val raw = ByteArrayOutputStream()
        DeflaterOutputStream(raw).use { z ->
            val row = ByteArray(1 + img.width * 3)
            for (y in 0 until img.height) {
                row[0] = 0
                for (x in 0 until img.width) {
                    val p = img.pixels[y * img.width + x]
                    row[1 + x * 3] = (p shr 16).toByte()
                    row[2 + x * 3] = (p shr 8).toByte()
                    row[3 + x * 3] = p.toByte()
                }
                z.write(row)
            }
        }
        val out = ByteArrayOutputStream()
        val d = DataOutputStream(out)
        d.write(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10))
        fun chunk(type: String, data: ByteArray) {
            d.writeInt(data.size)
            val t = type.toByteArray(Charsets.US_ASCII)
            d.write(t)
            d.write(data)
            val crc = CRC32()
            crc.update(t)
            crc.update(data)
            d.writeInt(crc.value.toInt())
        }
        val ihdr = ByteArrayOutputStream()
        DataOutputStream(ihdr).apply {
            writeInt(img.width); writeInt(img.height)
            writeByte(8); writeByte(2); writeByte(0); writeByte(0); writeByte(0)
        }
        chunk("IHDR", ihdr.toByteArray())
        chunk("IDAT", raw.toByteArray())
        chunk("IEND", ByteArray(0))
        return out.toByteArray()
    }
}
