package com.belotron.weatherradarhr

import android.util.Log
import com.belotron.weatherradarhr.MainActivity.Companion.ANIMATION_DURATION
import com.belotron.weatherradarhr.MainActivity.Companion.LOGTAG
import com.belotron.weatherradarhr.MainActivity.Companion.LOOP_COUNT
import java.nio.ByteBuffer
import java.nio.ByteOrder.LITTLE_ENDIAN
import java.nio.charset.Charset
import java.util.*

internal class GifEditor
private constructor(private val buf: ByteBuffer, private val delayTime: Int, private val framesToKeep: Int) {

    private fun go() {
        buf.order(LITTLE_ENDIAN)
        val frameList = parseGif()
//        Log.i(LOGTAG, "***************** rewrite buffer **************")
        rewriteFramesInBuffer(frameList)
//        buf.rewind();
//        parseGif();
        buf.rewind()
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
                            Log.i(LOGTAG, "Application extension at " + blockPos)
                            parseApplicationExtension()
                        }
                        EXT_TYPE_GRAPHIC_CONTROL -> {
                            Log.i(LOGTAG, "Graphic control ext at " + blockPos)
                            frameList.acceptGraphicControlExt()
                        }
                        else -> {
                            Log.i(LOGTAG, String.format("Ext 0x%02x at %d", extensionLabel, blockPos))
                            logSubBlocks()
                        }
                    }
                }
                BLOCK_TYPE_IMAGE -> {
                    Log.i(LOGTAG, "Image desc at " + blockPos)
                    frameList.acceptImageDescriptor()
                }
                BLOCK_TYPE_TRAILER -> {
                    Log.i(LOGTAG, "Trailer at " + blockPos)
                    buf.limit(buf.position())
                    break@readingLoop
                }
                else -> throw AssertionError(String.format("Unrecognized block type 0x%02x at %d",
                        blockType, blockPos))
            }
        }
        return frameList
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
                    Log.i(LOGTAG, "Netscape Extension Loop Count " + loopCount)
                    val terminatorByte = nextByte()
                    if (terminatorByte != 0) throw AssertionError("Invalid terminator byte " + terminatorByte)
                }
                return
            }
            else -> {
                Log.i(LOGTAG, "Application Identifier: " + appId)
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
            Log.i(LOGTAG, "sub-block " + getString(len))
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

    private inner class FrameList internal constructor(private val frameCountToKeep: Int) {
        private val framesToKeep: Deque<FrameDescriptor>
        private var firstFrameOffset = -1
        private var frameInProgress = false
        private var distinctFrameCount: Int = 0
        private var keptFrameCount: Int = 0
        private var currFrame: FrameDescriptor? = null

        init {
            this.framesToKeep = ArrayDeque(frameCountToKeep)
        }

        internal fun acceptBlockStart() {
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

        internal fun acceptGraphicControlExt() {
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

        internal fun acceptImageDescriptor() {
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
                Log.i(LOGTAG, "Dropping identical frame")
            }
            currFrame = null
        }

        internal fun popNextFrame(): FrameDescriptor? {
            return framesToKeep.poll()
        }

        internal fun peekLastFrame(): FrameDescriptor? {
            return framesToKeep.peekLast()
        }

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

        internal fun frameCount(): Int {
            return distinctFrameCount
        }

        internal fun keptFrameCount(): Int {
            return framesToKeep.size
        }

        internal fun firstFrameOffset(): Int {
            return firstFrameOffset
        }

        internal fun lastFrameDelay(): Int {
            return delayTime + ANIMATION_DURATION - keptFrameCount * delayTime
        }
    }

    private class FrameDescriptor internal constructor(internal val start: Int) {
        internal var gcExtPackedFields: Byte = 0
        internal var gcExtTransparentColorIndex: Byte = 0
        internal var imageDescStart: Int = 0
        internal var imageDataStart: Int = 0
        internal var end: Int = 0

        internal fun imageDataLength(): Int {
            return end - imageDataStart
        }

        internal fun writeGraphicControlExt(buf: ByteBuffer, delayTime: Int) {
            buf.put(BLOCK_TYPE_EXTENSION.toByte())
            buf.put(EXT_TYPE_GRAPHIC_CONTROL.toByte())
            buf.put(4.toByte()) // Graphic Control Extension block size
            buf.put(gcExtPackedFields)
            buf.putChar(delayTime.toChar())
            buf.put(gcExtTransparentColorIndex)
            buf.put(0.toByte()) // Block Terminator
        }
    }

    companion object {

        private val BLOCK_TYPE_EXTENSION = 0x21
        private val BLOCK_TYPE_IMAGE = 0x2c
        private val BLOCK_TYPE_TRAILER = 0x3b
        private val EXT_TYPE_GRAPHIC_CONTROL = 0xf9
        private val EXT_TYPE_APPLICATION = 0xff

        fun editGif(buf: ByteBuffer, delayTime: Int, framesToKeep: Int) {
            GifEditor(buf, delayTime, framesToKeep).go()
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
    }
}
