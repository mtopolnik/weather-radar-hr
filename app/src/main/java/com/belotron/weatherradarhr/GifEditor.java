package com.belotron.weatherradarhr;

import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.ArrayDeque;
import java.util.Deque;

import static com.belotron.weatherradarhr.MainActivity.ANIMATION_DURATION;
import static com.belotron.weatherradarhr.MainActivity.LOGTAG;
import static com.belotron.weatherradarhr.MainActivity.LOOP_COUNT;
import static java.nio.ByteOrder.LITTLE_ENDIAN;

@SuppressWarnings("MagicNumber")
class GifEditor {

    private static final int BLOCK_TYPE_EXTENSION = 0x21;
    private static final int BLOCK_TYPE_IMAGE = 0x2c;
    private static final int BLOCK_TYPE_TRAILER = 0x3b;
    private static final int EXT_TYPE_GRAPHIC_CONTROL = 0xf9;
    private static final int EXT_TYPE_APPLICATION = 0xff;

    private final ByteBuffer buf;
    private final int delayTime;
    private final int framesToKeep;

    private GifEditor(ByteBuffer buf, int delayTime, int framesToKeep) {
        this.buf = buf;
        this.delayTime = delayTime;
        this.framesToKeep = framesToKeep;
    }

    static void editGif(ByteBuffer buf, int delayTime, int framesToKeep) {
        new GifEditor(buf, delayTime, framesToKeep).go();
    }

    private void go() {
        buf.order(LITTLE_ENDIAN);
        FrameList frameList = parseGif();
//        Log.i(LOGTAG, "***************** rewrite buffer **************");
        rewriteFramesInBuffer(frameList);
//        buf.rewind();
//        parseGif();
        buf.rewind();
    }

    private void rewriteFramesInBuffer(FrameList frameList) {
        ByteBuffer sourceBuf = buf.duplicate();
        sourceBuf.order(LITTLE_ENDIAN);
        ByteBuffer destBuf = buf.duplicate();
        destBuf.order(LITTLE_ENDIAN);
        destBuf.position(frameList.firstFrameOffset());
        writeLoopingExtension(destBuf);
        for (FrameDescriptor frame; (frame = frameList.popNextFrame()) != null;) {
            boolean onLastFrame = frameList.peekLastFrame() == null;
            frame.writeGraphicControlExt(destBuf, (onLastFrame? frameList.lastFrameDelay() : delayTime));
            copy(sourceBuf, frame.imageDescStart, frame.end, destBuf);
        }
        destBuf.put((byte) BLOCK_TYPE_TRAILER);
        buf.limit(destBuf.position());
    }

    private static void copy(ByteBuffer src, int srcPos, int srcLimit, ByteBuffer dest) {
        if (srcPos == dest.position()) {
            return;
        }
        int len = srcLimit - srcPos;
        System.arraycopy(src.array(), srcPos, dest.array(), dest.position(), len);
        dest.position(dest.position() + len);
    }

    private FrameList parseGif() {
        String gifVersion = getString(6);
        if (!gifVersion.equals("GIF87a") && !gifVersion.equals("GIF89a")) {
            throw new AssertionError("Not a GIF file");
        }
        skipLogicalScreenDescriptorAndGlobalColorTable();
        final FrameList frameList = new FrameList(framesToKeep);
        readingLoop:
        while (buf.hasRemaining()) {
            frameList.acceptBlockStart();
            int blockPos = buf.position();
            int blockType = nextByte();
            switch (blockType) {
                case BLOCK_TYPE_EXTENSION:
                    int extensionLabel = nextByte();
                    switch (extensionLabel) {
//                        case EXT_TYPE_APPLICATION:
//                            Log.i(LOGTAG, "Application extension at " + blockPos);
//                            parseApplicationExtension();
//                            break;
                        case EXT_TYPE_GRAPHIC_CONTROL:
                            Log.i(LOGTAG, "Graphic control ext at " + blockPos);
                            frameList.acceptGraphicControlExt();
                            break;
                        default:
                            Log.i(LOGTAG, String.format("Ext 0x%02x at %d", extensionLabel, blockPos));
                            logSubBlocks();
                    }
                    break;
                case BLOCK_TYPE_IMAGE:
                    Log.i(LOGTAG, "Image desc at " + blockPos);
                    frameList.acceptImageDescriptor();
                    break;
                case BLOCK_TYPE_TRAILER:
                    Log.i(LOGTAG, "Trailer at " + blockPos);
                    buf.limit(buf.position());
                    break readingLoop;
                default:
                    throw new AssertionError(String.format("Unrecognized block type 0x%02x at %d",
                            blockType, blockPos));
            }
        }
        return frameList;
    }

    private void parseApplicationExtension() {
        int blockSize = nextByte();
        if (blockSize != 11) throw new AssertionError("Invalid Application Extension block size: " + blockSize);
        String appId = getString(11);
        switch (appId) {
            case "NETSCAPE2.0": {
                int len = nextByte();
                int subBlockId = nextByte();
                if (subBlockId == 1) {
                    if (len != 3) throw new AssertionError("Invalid Netscape Looping Extension block size: " + len);
                    int loopCount = buf.getChar();
                    Log.i(LOGTAG, "Netscape Extension Loop Count " + loopCount);
                    int terminatorByte = nextByte();
                    if (terminatorByte != 0) throw new AssertionError("Invalid terminator byte " + terminatorByte);
                }
                return;
            }
            default:
                Log.i(LOGTAG, "Application Identifier: " + appId);
                skipSubBlocks();
        }
    }

    private void skipLogicalScreenDescriptorAndGlobalColorTable() {
        skip(4); // canvas width and height
        int packedFields = nextByte();
        skip(2); // Background Color Index and Pixel Aspect Ratio
        skipColorTable(packedFields);
    }

    private void skipColorTable(int packedFields) {
        boolean colorTablePresent = (packedFields & 0b1000_0000) != 0;
        if (!colorTablePresent) {
            return;
        }
        int colorTableSize = 1 << ((packedFields & 0b111) + 1);
        skip(3 * colorTableSize);
    }

    private void skipSubBlocks() {
        for (int len; (len = nextByte()) != 0; ) {
            skip(len);
        }
    }

    private void logSubBlocks() {
        for (int len; (len = nextByte()) != 0; ) {
            Log.i(LOGTAG, "sub-block " + getString(len));
        }
    }

    private void skip(int len) {
        buf.position(buf.position() + len);
    }

    private int nextByte() {
        return toUnsignedInt(buf.get());
    }

    private static int toUnsignedInt(int b) {
        return b & 0xff;
    }

    private String getString(int len) {
        try {
            ByteBuffer slice = buf.slice();
            slice.limit(len);
            return Charset.forName("US-ASCII").decode(slice).toString();
        } finally {
            skip(len);
        }
    }

    private static void writeLoopingExtension(ByteBuffer buf) {
        buf.put((byte) BLOCK_TYPE_EXTENSION);
        buf.put((byte) EXT_TYPE_APPLICATION);
        buf.put((byte) 11); // Application Extension block size
        buf.put("NETSCAPE2.0".getBytes(Charset.forName("US-ASCII")));
        buf.put((byte) 3); // Netscape Looping Extension block size
        buf.put((byte) 1); // Looping Sub-Block ID
        buf.putChar((char) LOOP_COUNT);
        buf.put((byte) 0); // Terminator byte
    }

    private class FrameList {
        private final int frameCountToKeep;
        private final Deque<FrameDescriptor> framesToKeep;
        private int firstFrameOffset = -1;
        private boolean frameInProgress = false;
        private int distinctFrameCount;
        private int keptFrameCount;
        private FrameDescriptor currFrame;

        FrameList(int frameCountToKeep) {
            this.frameCountToKeep = frameCountToKeep;
            this.framesToKeep = new ArrayDeque<>(frameCountToKeep);
        }

        void acceptBlockStart() {
            int offset = buf.position();
            if (firstFrameOffset == -1) {
                firstFrameOffset = offset;
            }
            if (!frameInProgress) {
                currFrame = new FrameDescriptor(offset);
            }
            int blockType = toUnsignedInt(buf.get(offset));
            frameInProgress = blockType != BLOCK_TYPE_IMAGE;
        }

        void acceptGraphicControlExt() {
            int blockSize = nextByte();
            if (blockSize != 4) {
                throw new AssertionError("Invalid Graphic Control Extension block size: " + blockSize);
            }
            currFrame.gcExtPackedFields = buf.get();
            skip(2); // delayTime
            currFrame.gcExtTransparentColorIndex = buf.get();
            int blockTerminator = nextByte();
            if (blockTerminator != 0) {
                throw new AssertionError(String.format("Invalid block terminator byte: 0x%02x", blockTerminator));
            }
        }

        void acceptImageDescriptor() {
            currFrame.imageDescStart = buf.position() - 1;
            skip(8); // image position and size
            int packedFields = nextByte();
            skipColorTable(packedFields);
            skip(1); // LZW Minimum Code Size
            currFrame.imageDataStart = buf.position();
            skipSubBlocks();
            currFrame.end = buf.position();
            if (currFrameDifferentFromPrev()) {
                addFrame();
                distinctFrameCount++;
            } else {
                Log.i(LOGTAG, "Dropping identical frame");
            }
            currFrame = null;
        }

        FrameDescriptor popNextFrame() {
            return framesToKeep.poll();
        }

        FrameDescriptor peekLastFrame() {
            return framesToKeep.peekLast();
        }

        private void addFrame() {
            if (framesToKeep.size() == frameCountToKeep) {
                framesToKeep.remove();
            } else {
                keptFrameCount++;
            }
            framesToKeep.add(currFrame);
        }

        private boolean currFrameDifferentFromPrev() {
            FrameDescriptor prevFrame = peekLastFrame();
            return prevFrame == null
                    || prevFrame.imageDataLength() != currFrame.imageDataLength()
                    || !equalBlocks(buf, prevFrame.imageDataStart, currFrame.imageDataStart,
                        currFrame.imageDataLength());
        }

        private boolean equalBlocks(ByteBuffer buf, int start1, int start2, int count) {
            ByteBuffer block1 = buf.duplicate();
            ByteBuffer block2 = buf.duplicate();
            block1.position(start1);
            block2.position(start2);
            block1.limit(count);
            block2.limit(count);
            return block1.equals(block2);
        }

        int frameCount() {
            return distinctFrameCount;
        }

        int keptFrameCount() {
            return framesToKeep.size();
        }

        int firstFrameOffset() {
            return firstFrameOffset;
        }

        int lastFrameDelay() {
            return delayTime + ANIMATION_DURATION - (keptFrameCount * delayTime);
        }
    }

    private static class FrameDescriptor {
        final int start;
        byte gcExtPackedFields;
        byte gcExtTransparentColorIndex;
        int imageDescStart;
        int imageDataStart;
        int end;

        FrameDescriptor(int start) {
            this.start = start;
        }

        int imageDataLength() {
            return end - imageDataStart;
        }

        void writeGraphicControlExt(ByteBuffer buf, int delayTime) {
            buf.put((byte) BLOCK_TYPE_EXTENSION);
            buf.put((byte) EXT_TYPE_GRAPHIC_CONTROL);
            buf.put((byte) 4); // Graphic Control Extension block size
            buf.put(gcExtPackedFields);
            buf.putChar((char) delayTime);
            buf.put(gcExtTransparentColorIndex);
            buf.put((byte) 0); // Block Terminator
        }
    }
}
