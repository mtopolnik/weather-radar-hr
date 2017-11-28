package com.belotron.weatherradarhr

import java.nio.ByteBuffer
import java.nio.ByteOrder.LITTLE_ENDIAN
import java.nio.charset.Charset
import java.util.ArrayDeque
import java.util.Deque

const val ANIMATION_COVERS_MINUTES = 100
private const val LOOP_COUNT = 50
private const val ANIMATION_DURATION = 250

fun editGif(buf: ByteBuffer, delayTime: Int, framesToKeep: Int) {
    GifEditor(buf, delayTime, framesToKeep).go()
}

fun checkGif(buf: ByteBuffer) {
    GifEditor(buf, 0, 1).checkGif()
}

private const val BLOCK_TYPE_EXTENSION = 0x21
private const val BLOCK_TYPE_IMAGE = 0x2c
private const val BLOCK_TYPE_TRAILER = 0x3b
private const val EXT_TYPE_GRAPHIC_CONTROL = 0xf9
private const val EXT_TYPE_APPLICATION = 0xff

private class GifEditor
constructor(
        private val buf: ByteBuffer,
        private val delayTime: Int,
        private val framesToKeep: Int
) {

    fun go() {
        buf.order(LITTLE_ENDIAN)
        val frameList = parseGif()
        rewriteFramesInBuffer(frameList)
        buf.rewind()
    }

    fun checkGif() {
        parseGif()
    }

    private fun parseGif(): FrameList {
        val gifVersion = getString(6)
        if (gifVersion != "GIF87a" && gifVersion != "GIF89a") {
            throw AssertionError("Not a GIF file")
        }
        skipLogicalScreenDescriptorAndGlobalColorTable()
        val frameList = FrameList(framesToKeep)
        readingLoop@ while (buf.hasRemaining()) {
            frameList.acceptBlockStart()
            val blockPos = buf.position()
            val blockType = nextByte()
            when (blockType) {
                BLOCK_TYPE_EXTENSION -> {
                    val extensionLabel = nextByte()
                    when (extensionLabel) {
                        EXT_TYPE_APPLICATION -> {
                            MyLog.d("Application extension at %d", blockPos)
                            parseApplicationExtension()
                        }
                        EXT_TYPE_GRAPHIC_CONTROL -> {
                            MyLog.d("Graphic control ext at %d", blockPos)
                            frameList.acceptGraphicControlExt()
                        }
                        else -> {
                            MyLog.d("Ext 0x%02x at %d", extensionLabel, blockPos)
                            logSubBlocks()
                        }
                    }
                }
                BLOCK_TYPE_IMAGE -> {
                    MyLog.d("Image desc at %d", blockPos)
                    frameList.acceptImageDescriptor()
                }
                BLOCK_TYPE_TRAILER -> {
                    MyLog.d("Trailer at %d", blockPos)
                    buf.limit(buf.position())
                    break@readingLoop
                }
                else -> throw AssertionError(String.format("Unrecognized block type 0x%02x at %d",
                        blockType, blockPos))
            }
        }
        return frameList
    }

    private fun rewriteFramesInBuffer(frameList: FrameList) {
        val sourceBuf = buf.duplicate()
        sourceBuf.order(LITTLE_ENDIAN)
        val destBuf = buf.duplicate()
        destBuf.order(LITTLE_ENDIAN)
        destBuf.position(frameList.firstFrameOffset())
        writeLoopingExtension(destBuf)
        while (true) {
            val frame = frameList.popNextFrame() ?: break
            val onLastFrame = frameList.peekLastFrame() == null
            frame.writeGraphicControlExt(destBuf, if (onLastFrame) frameList.lastFrameDelay() else delayTime)
            copy(sourceBuf, frame.imageDescStart, frame.end, destBuf)
        }
        destBuf.put(BLOCK_TYPE_TRAILER.toByte())
        buf.limit(destBuf.position())
    }

    private fun parseApplicationExtension() {
        val blockSize = nextByte()
        if (blockSize != 11) throw AssertionError("Invalid Application Extension block size: " + blockSize)
        val appId = getString(11)
        when (appId) {
            "NETSCAPE2.0" -> {
                val len = nextByte()
                val subBlockId = nextByte()
                if (subBlockId == 1) {
                    if (len != 3) throw AssertionError("Invalid Netscape Looping Extension block size: " + len)
                    val loopCount = buf.char.toInt()
                    MyLog.d("Netscape Extension Loop Count %d", loopCount)
                    val terminatorByte = nextByte()
                    if (terminatorByte != 0) throw AssertionError("Invalid terminator byte " + terminatorByte)
                }
                return
            }
            else -> {
                MyLog.d("Application Identifier %d", appId)
                skipSubBlocks()
            }
        }
    }

    private fun skipLogicalScreenDescriptorAndGlobalColorTable() {
        skip(4) // canvas width and height
        val packedFields = nextByte()
        skip(2) // Background Color Index and Pixel Aspect Ratio
        skipColorTable(packedFields)
    }

    private fun skipColorTable(packedFields: Int) {
        val colorTablePresent = packedFields and 128 != 0
        if (!colorTablePresent) {
            return
        }
        val colorTableSize = 1 shl (packedFields and 7) + 1
        skip(3 * colorTableSize)
    }

    private fun skipSubBlocks() {
        while (true) {
            val len = nextByte();
            if (len == 0) break
            skip(len)
        }
    }

    private fun logSubBlocks() {
        while (true) {
            val len = nextByte();
            if (len == 0) break
            MyLog.d("sub-block ", getString(len))
        }
    }

    private fun skip(len: Int) {
        buf.position(buf.position() + len)
    }

    private fun nextByte(): Int {
        return toUnsignedInt(buf.get().toInt())
    }

    private fun getString(len: Int): String {
        try {
            val slice = buf.slice()
            slice.limit(len)
            return Charset.forName("US-ASCII").decode(slice).toString()
        } finally {
            skip(len)
        }
    }

    private fun copy(src: ByteBuffer, srcPos: Int, srcLimit: Int, dest: ByteBuffer) {
        if (srcPos == dest.position()) {
            return
        }
        val len = srcLimit - srcPos
        System.arraycopy(src.array(), srcPos, dest.array(), dest.position(), len)
        dest.position(dest.position() + len)
    }

    private fun toUnsignedInt(b: Int): Int {
        return b and 0xff
    }

    private fun writeLoopingExtension(buf: ByteBuffer) {
        buf.put(BLOCK_TYPE_EXTENSION.toByte())
        buf.put(EXT_TYPE_APPLICATION.toByte())
        buf.put(11.toByte()) // Application Extension block size
        buf.put("NETSCAPE2.0".toByteArray(Charset.forName("US-ASCII")))
        buf.put(3.toByte()) // Netscape Looping Extension block size
        buf.put(1.toByte()) // Looping Sub-Block ID
        buf.putChar(LOOP_COUNT.toChar())
        buf.put(0.toByte()) // Terminator byte
    }

    private inner class FrameList constructor(private val frameCountToKeep: Int) {
        private val framesToKeep: Deque<FrameDescriptor>
        private var firstFrameOffset = -1
        private var frameInProgress = false
        private var distinctFrameCount: Int = 0
        private var keptFrameCount: Int = 0
        private var currFrame: FrameDescriptor? = null

        init {
            this.framesToKeep = ArrayDeque(frameCountToKeep)
        }

        fun acceptBlockStart() {
            val offset = buf.position()
            if (firstFrameOffset == -1) {
                firstFrameOffset = offset
            }
            if (!frameInProgress) {
                currFrame = FrameDescriptor(offset)
            }
            val blockType = toUnsignedInt(buf.get(offset).toInt())
            frameInProgress = blockType != BLOCK_TYPE_IMAGE
        }

        fun acceptGraphicControlExt() {
            val blockSize = nextByte()
            if (blockSize != 4) {
                throw AssertionError("Invalid Graphic Control Extension block size: " + blockSize)
            }
            currFrame!!.gcExtPackedFields = buf.get()
            skip(2) // delayTime
            currFrame!!.gcExtTransparentColorIndex = buf.get()
            val blockTerminator = nextByte()
            if (blockTerminator != 0) {
                throw AssertionError(String.format("Invalid block terminator byte: 0x%02x", blockTerminator))
            }
        }

        fun acceptImageDescriptor() {
            currFrame!!.imageDescStart = buf.position() - 1
            skip(8) // image position and size
            val packedFields = nextByte()
            skipColorTable(packedFields)
            skip(1) // LZW Minimum Code Size
            currFrame!!.imageDataStart = buf.position()
            skipSubBlocks()
            currFrame!!.end = buf.position()
            if (currFrameDifferentFromPrev()) {
                addFrame()
                distinctFrameCount++
            } else {
                MyLog.d("Dropping identical frame")
            }
            currFrame = null
        }

        fun popNextFrame(): FrameDescriptor? = framesToKeep.poll()

        fun peekLastFrame(): FrameDescriptor? = framesToKeep.peekLast()

        private fun addFrame() {
            if (framesToKeep.size == frameCountToKeep) {
                framesToKeep.remove()
            } else {
                keptFrameCount++
            }
            framesToKeep.add(currFrame)
        }

        private fun currFrameDifferentFromPrev(): Boolean {
            val prevFrame = peekLastFrame()
            return prevFrame == null
                    || prevFrame.imageDataLength() != currFrame!!.imageDataLength()
                    || !equalBlocks(buf, prevFrame.imageDataStart, currFrame!!.imageDataStart,
                    currFrame!!.imageDataLength())
        }

        private fun equalBlocks(buf: ByteBuffer, start1: Int, start2: Int, count: Int): Boolean {
            val block1 = buf.duplicate()
            val block2 = buf.duplicate()
            block1.position(start1)
            block2.position(start2)
            block1.limit(count)
            block2.limit(count)
            return block1 == block2
        }

        fun firstFrameOffset() = firstFrameOffset

        fun lastFrameDelay() = delayTime + ANIMATION_DURATION - keptFrameCount * delayTime
    }

    private class FrameDescriptor constructor(internal val start: Int) {
        var gcExtPackedFields: Byte = 0
        var gcExtTransparentColorIndex: Byte = 0
        var imageDescStart: Int = 0
        var imageDataStart: Int = 0
        var end: Int = 0

        fun imageDataLength() = end - imageDataStart

        fun writeGraphicControlExt(buf: ByteBuffer, delayTime: Int) {
            buf.put(BLOCK_TYPE_EXTENSION.toByte())
            buf.put(EXT_TYPE_GRAPHIC_CONTROL.toByte())
            buf.put(4.toByte()) // Graphic Control Extension block size
            buf.put(gcExtPackedFields)
            buf.putChar(delayTime.toChar())
            buf.put(gcExtTransparentColorIndex)
            buf.put(0.toByte()) // Block Terminator
        }
    }
}


private fun copy(src: ByteBuffer, srcPos: Int, srcLimit: Int, dest: ByteBuffer) {
    if (srcPos == dest.position()) {
        return
    }
    val len = srcLimit - srcPos
    System.arraycopy(src.array(), srcPos, dest.array(), dest.position(), len)
    dest.position(dest.position() + len)
}

private fun toUnsignedInt(b: Int): Int {
    return b and 0xff
}

private fun writeLoopingExtension(buf: ByteBuffer) {
    buf.put(BLOCK_TYPE_EXTENSION.toByte())
    buf.put(EXT_TYPE_APPLICATION.toByte())
    buf.put(11.toByte()) // Application Extension block size
    buf.put("NETSCAPE2.0".toByteArray(Charset.forName("US-ASCII")))
    buf.put(3.toByte()) // Netscape Looping Extension block size
    buf.put(1.toByte()) // Looping Sub-Block ID
    buf.putChar(LOOP_COUNT.toChar())
    buf.put(0.toByte()) // Terminator byte
}
