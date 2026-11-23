package org.jetbrains.kotlinconf.bazel.metrocompat

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream

private const val SHIM_PKG = "org/jetbrains/kotlinconf/bazel/metrocompat/shims"

private val DIAGNOSTIC_UTF8_MAP = mapOf(
    "org/jetbrains/kotlin/diagnostics/DiagnosticFactory0DelegateProvider" to "$SHIM_PKG/DiagnosticFactory0Shim",
    "Lorg/jetbrains/kotlin/diagnostics/DiagnosticFactory0DelegateProvider;" to "L$SHIM_PKG/DiagnosticFactory0Shim;",
    "org/jetbrains/kotlin/diagnostics/DiagnosticFactory1DelegateProvider" to "$SHIM_PKG/DiagnosticFactory1Shim",
    "Lorg/jetbrains/kotlin/diagnostics/DiagnosticFactory1DelegateProvider;" to "L$SHIM_PKG/DiagnosticFactory1Shim;",
    "org/jetbrains/kotlin/diagnostics/DiagnosticFactory2DelegateProvider" to "$SHIM_PKG/DiagnosticFactory2Shim",
    "Lorg/jetbrains/kotlin/diagnostics/DiagnosticFactory2DelegateProvider;" to "L$SHIM_PKG/DiagnosticFactory2Shim;",
)

private val IR_INTERPRETER_UTF8_MAP = mapOf(
    "org/jetbrains/kotlin/ir/interpreter/UtilsKt" to "org/jetbrains/kotlin/ir/util/IrUtilsKt",
)

private const val OLD_LOOKUP_TAG = "org/jetbrains/kotlin/fir/symbols/ConeTypeParameterLookupTag"
private const val NEW_LOOKUP_TAG = "org/jetbrains/kotlin/fir/types/ConeTypeParameterLookupTag"
private const val CONE_SHIM = "$SHIM_PKG/ConeTypeParameterShim"
private const val TYPE_PARAM_SYMBOL_DESC = "()Lorg/jetbrains/kotlin/fir/symbols/impl/FirTypeParameterSymbol;"
private const val SHIM_TYPE_PARAM_SYMBOL_DESC =
    "(L$NEW_LOOKUP_TAG;)Lorg/jetbrains/kotlin/fir/symbols/impl/FirTypeParameterSymbol;"

private const val DOS_EPOCH_MS = 315532800000L // 1980-01-01T00:00:00Z, reproducible jar entry mtime

/**
 * Copies [inputJar] to [outputJar], rewriting the four Metro 1.6.0 class files whose FIR/IR targets
 * moved in Kotlin 2.5.20-dev-5696 and appending the shim classes from [shimsJar].
 */
fun adaptMetroJar(inputJar: File, shimsJar: File, outputJar: File) {
    val rewritten = mutableSetOf<String>()
    outputJar.parentFile?.mkdirs()
    JarFile(inputJar).use { src ->
        JarFile(shimsJar).use { shims ->
            JarOutputStream(outputJar.outputStream().buffered()).use { out ->
                val seen = HashSet<String>()
                for (entry in src.entries()) {
                    if (isSignatureFile(entry.name)) continue
                    seen.add(entry.name)
                    val raw = src.getInputStream(entry).use { it.readBytes() }
                    val transformed = when (entry.name) {
                        "dev/zacsweers/metro/compiler/fir/MetroDiagnostics.class",
                        "dev/zacsweers/metro/compiler/circuit/CircuitDiagnostics.class",
                        -> {
                            rewritten.add(entry.name)
                            rewriteConstantPoolUtf8(raw, DIAGNOSTIC_UTF8_MAP)
                        }
                        "dev/zacsweers/metro/compiler/ir/graph/SyntheticGraphGenerator.class" -> {
                            rewritten.add(entry.name)
                            rewriteConstantPoolUtf8(raw, IR_INTERPRETER_UTF8_MAP)
                        }
                        "dev/zacsweers/metro/compiler/fir/FirTypeReader.class" -> {
                            rewritten.add(entry.name)
                            rewriteFirTypeReader(raw)
                        }
                        else -> raw
                    }
                    writeJarEntry(out, entry.name, transformed, entry.isDirectory)
                }
                for (entry in shims.entries()) {
                    if (entry.isDirectory || !entry.name.startsWith("$SHIM_PKG/") || !seen.add(entry.name)) continue
                    val bytes = shims.getInputStream(entry).use { it.readBytes() }
                    writeJarEntry(out, entry.name, bytes, isDirectory = false)
                }
            }
        }
    }
    val expected = setOf(
        "dev/zacsweers/metro/compiler/fir/MetroDiagnostics.class",
        "dev/zacsweers/metro/compiler/circuit/CircuitDiagnostics.class",
        "dev/zacsweers/metro/compiler/ir/graph/SyntheticGraphGenerator.class",
        "dev/zacsweers/metro/compiler/fir/FirTypeReader.class",
    )
    require(rewritten == expected) {
        "expected to rewrite $expected in ${inputJar.name}, actually rewrote $rewritten"
    }
}

private fun isSignatureFile(name: String): Boolean =
    name.startsWith("META-INF/") &&
        (name.endsWith(".SF") || name.endsWith(".DSA") || name.endsWith(".RSA"))

private fun writeJarEntry(out: JarOutputStream, name: String, bytes: ByteArray, isDirectory: Boolean) {
    val newEntry = JarEntry(name).apply { time = DOS_EPOCH_MS }
    out.putNextEntry(newEntry)
    if (!isDirectory) {
        out.write(bytes)
    }
    out.closeEntry()
}

private sealed interface CpEntry {
    data class Utf8(var value: String) : CpEntry
    data class SingleIndex(val tag: Int, var index: Int) : CpEntry
    data class PairIndex(val tag: Int, var index1: Int, var index2: Int) : CpEntry
    data class Raw(val tag: Int, val bytes: ByteArray, val wide: Boolean = false) : CpEntry
}

private class ParsedClass(
    val minor: Int,
    val major: Int,
    val cp: MutableList<CpEntry?>, // 1-indexed; slot 0 and wide trailing slots are null
    val tail: ByteArray,
)

private fun parseClass(bytes: ByteArray): ParsedClass {
    val dis = DataInputStream(ByteArrayInputStream(bytes))
    val magic = dis.readInt()
    require(magic == 0xCAFEBABE.toInt()) { "invalid class file magic: 0x${magic.toUInt().toString(16)}" }
    val minor = dis.readUnsignedShort()
    val major = dis.readUnsignedShort()
    val cpCount = dis.readUnsignedShort()
    val cp = MutableList<CpEntry?>(cpCount) { null }
    var i = 1
    while (i < cpCount) {
        when (val tag = dis.readUnsignedByte()) {
            1 -> cp[i] = CpEntry.Utf8(dis.readUTF())
            7, 8, 16, 19, 20 -> cp[i] = CpEntry.SingleIndex(tag, dis.readUnsignedShort())
            9, 10, 11, 12, 17, 18 -> cp[i] = CpEntry.PairIndex(tag, dis.readUnsignedShort(), dis.readUnsignedShort())
            3, 4 -> cp[i] = CpEntry.Raw(tag, ByteArray(4).also(dis::readFully))
            5, 6 -> {
                cp[i] = CpEntry.Raw(tag, ByteArray(8).also(dis::readFully), wide = true)
                i++
            }
            15 -> cp[i] = CpEntry.Raw(tag, ByteArray(3).also(dis::readFully))
            else -> error("unknown constant pool tag $tag at index $i")
        }
        i++
    }
    val tail = dis.readBytes()
    return ParsedClass(minor, major, cp, tail)
}

private fun serializeClass(parsed: ParsedClass, tail: ByteArray = parsed.tail): ByteArray {
    val baos = ByteArrayOutputStream(tail.size + parsed.cp.size * 16)
    val dos = DataOutputStream(baos)
    dos.writeInt(0xCAFEBABE.toInt())
    dos.writeShort(parsed.minor)
    dos.writeShort(parsed.major)
    dos.writeShort(parsed.cp.size)
    var i = 1
    while (i < parsed.cp.size) {
        when (val entry = parsed.cp[i]!!) {
            is CpEntry.Utf8 -> {
                dos.writeByte(1)
                dos.writeUTF(entry.value)
            }
            is CpEntry.SingleIndex -> {
                dos.writeByte(entry.tag)
                dos.writeShort(entry.index)
            }
            is CpEntry.PairIndex -> {
                dos.writeByte(entry.tag)
                dos.writeShort(entry.index1)
                dos.writeShort(entry.index2)
            }
            is CpEntry.Raw -> {
                dos.writeByte(entry.tag)
                dos.write(entry.bytes)
                if (entry.wide) i++
            }
        }
        i++
    }
    dos.write(tail)
    dos.flush()
    return baos.toByteArray()
}

/** Replaces exact UTF-8 constant-pool entries according to [replacements]. */
fun rewriteConstantPoolUtf8(classBytes: ByteArray, replacements: Map<String, String>): ByteArray {
    val parsed = parseClass(classBytes)
    var changed = 0
    for (entry in parsed.cp) {
        if (entry is CpEntry.Utf8) {
            val replacement = replacements[entry.value]
            if (replacement != null) {
                entry.value = replacement
                changed++
            }
        }
    }
    require(changed > 0) { "no constant-pool UTF-8 entry matched ${replacements.keys}" }
    return serializeClass(parsed)
}

/**
 * Rewrites `ConeTypeParameterType.getLookupTag().getTypeParameterSymbol()` in `FirTypeReader.class`
 * to call `ConeTypeParameterShim.getTypeParameterSymbol(type.getLookupTag())` against the new
 * `org.jetbrains.kotlin.fir.types.ConeTypeParameterLookupTag` package.
 */
fun rewriteFirTypeReader(classBytes: ByteArray): ByteArray {
    val parsed = parseClass(classBytes)
    fun utf8(index: Int): String = (parsed.cp[index] as CpEntry.Utf8).value

    var updatedReturnDesc = false
    for (entry in parsed.cp) {
        if (entry is CpEntry.Utf8 && entry.value == "()L$OLD_LOOKUP_TAG;") {
            entry.value = "()L$NEW_LOOKUP_TAG;"
            updatedReturnDesc = true
        }
    }
    require(updatedReturnDesc) { "FirTypeReader.class has no ()L$OLD_LOOKUP_TAG; descriptor" }

    var targetMethodRef = -1
    for (idx in 1 until parsed.cp.size) {
        val entry = parsed.cp[idx]
        if (entry is CpEntry.PairIndex && entry.tag == 10) {
            val cls = parsed.cp[entry.index1] as CpEntry.SingleIndex
            val nt = parsed.cp[entry.index2] as CpEntry.PairIndex
            if (utf8(cls.index) == OLD_LOOKUP_TAG &&
                utf8(nt.index1) == "getTypeParameterSymbol" &&
                utf8(nt.index2) == TYPE_PARAM_SYMBOL_DESC
            ) {
                (parsed.cp[cls.index] as CpEntry.Utf8).value = CONE_SHIM
                val newDescIdx = parsed.cp.size
                parsed.cp.add(CpEntry.Utf8(SHIM_TYPE_PARAM_SYMBOL_DESC))
                val newNtIdx = parsed.cp.size
                parsed.cp.add(CpEntry.PairIndex(12, nt.index1, newDescIdx))
                entry.index2 = newNtIdx
                targetMethodRef = idx
                break
            }
        }
    }
    require(targetMethodRef > 0) { "FirTypeReader.class has no ConeTypeParameterLookupTag.getTypeParameterSymbol Methodref" }

    val newTail = rewriteInvokeVirtualToStaticInMethods(parsed, targetMethodRef)
    return serializeClass(parsed, newTail)
}

private fun rewriteInvokeVirtualToStaticInMethods(parsed: ParsedClass, methodRefIndex: Int): ByteArray {
    val tail = parsed.tail.copyOf()
    val dis = DataInputStream(ByteArrayInputStream(tail))
    var pos = 0
    fun skipBytes(n: Int) {
        var remaining = n
        while (remaining > 0) {
            val skipped = dis.skipBytes(remaining)
            require(skipped > 0) { "unexpected EOF skipping $n bytes" }
            remaining -= skipped
        }
        pos += n
    }
    fun readU2(): Int {
        pos += 2
        return dis.readUnsignedShort()
    }
    fun readI4(): Int {
        pos += 4
        return dis.readInt()
    }
    // access_flags, this_class, super_class
    skipBytes(6)
    val interfacesCount = readU2()
    skipBytes(interfacesCount * 2)
    // fields
    val fieldsCount = readU2()
    repeat(fieldsCount) {
        skipBytes(6)
        val attrCount = readU2()
        repeat(attrCount) {
            skipBytes(2)
            skipBytes(readI4())
        }
    }
    // methods
    val methodsCount = readU2()
    var patchedCalls = 0
    val hi = (methodRefIndex ushr 8) and 0xFF
    val lo = methodRefIndex and 0xFF
    repeat(methodsCount) {
        skipBytes(6)
        val attrCount = readU2()
        repeat(attrCount) {
            val attrNameIdx = readU2()
            val attrLen = readI4()
            val attrName = (parsed.cp[attrNameIdx] as? CpEntry.Utf8)?.value
            if (attrName == "Code") {
                val attrStart = pos
                skipBytes(4) // max_stack, max_locals
                val codeLen = readI4()
                val codeStart = pos
                for (offset in 0 until codeLen - 2) {
                    if ((tail[codeStart + offset].toInt() and 0xFF) == 0xB6 &&
                        (tail[codeStart + offset + 1].toInt() and 0xFF) == hi &&
                        (tail[codeStart + offset + 2].toInt() and 0xFF) == lo
                    ) {
                        tail[codeStart + offset] = 0xB8.toByte() // invokestatic
                        patchedCalls++
                    }
                }
                skipBytes(attrLen - (pos - attrStart))
            } else {
                skipBytes(attrLen)
            }
        }
    }
    require(patchedCalls == 1) { "expected 1 invokevirtual #$methodRefIndex in FirTypeReader, found $patchedCalls" }
    return tail
}
