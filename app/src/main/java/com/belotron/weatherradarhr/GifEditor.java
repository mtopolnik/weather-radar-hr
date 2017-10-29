package com.belotron.weatherradarhr;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.ArrayDeque;
import java.util.Queue;

import static java.nio.ByteOrder.LITTLE_ENDIAN;

@SuppressWarnings("MagicNumber")
class GifEditor {

    private static final int LAST_FRAME_HOLD_TIME = 125;
    private static final int LOOP_COUNT = 200;
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
        String gifVersion = getString(6);
        if (!gifVersion.equals("GIF87a") && !gifVersion.equals("GIF89a")) {
            throw new AssertionError("Not a GIF file");
        }
        FrameIndex frameIndex = traverseAndEdit(delayTime, framesToKeep);
        deleteExcessLeadingFrames(frameIndex);
    }

    private void deleteExcessLeadingFrames(FrameIndex frameIndex) {
        int srcOffset = frameIndex.offsetOfFirstFrameToKeep();
        int destOffset = frameIndex.firstFrameOffset();
        if (srcOffset == destOffset) {
            return;
        }
        byte[] bytes = buf.array();
        System.arraycopy(bytes, srcOffset, bytes, destOffset, bytes.length - srcOffset);
        buf.limit(bytes.length - (srcOffset - destOffset));
    }

    private FrameIndex traverseAndEdit(int delayTime, int framesToKeep) {
        skipLogicalScreenDescriptorAndGlobalColorTable();
        final FrameIndex frameIndex = new FrameIndex(framesToKeep);
        boolean loopingExtSeen = false;
        readingLoop:
        while (buf.hasRemaining()) {
            if (loopingExtSeen) {
                frameIndex.acceptBlock(buf);
            }
            int blockType = nextByte();
            switch (blockType) {
                case BLOCK_TYPE_EXTENSION:
                    int extensionLabel = nextByte();
                    switch (extensionLabel) {
                        case EXT_TYPE_GRAPHIC_CONTROL:
                            editGraphicControlExtension(delayTime);
                            break;
                        case EXT_TYPE_APPLICATION:
                            loopingExtSeen |= editIfLoopingExtension(LOOP_COUNT);
                            break;
                        default:
                            skipSubBlocks();
                    }
                    break;
                case BLOCK_TYPE_IMAGE:
                    skipImageDescriptor();
                    break;
                case BLOCK_TYPE_TRAILER:
                    buf.limit(buf.position());
                    buf.reset(); // restore the position of last frame's delay time
                    buf.putChar((char) (LAST_FRAME_HOLD_TIME));
                    break readingLoop;
                default:
                    throw new AssertionError(String.format("Unrecognized block type: 0x%02x", blockType));
            }
        }
        buf.rewind();
        return frameIndex;
    }

    private boolean editIfLoopingExtension(int loopCount) {
        int blockSize = nextByte();
        if (blockSize != 11) {
            throw new AssertionError("Invalid Application Extension block size: " + blockSize);
        }
        String appId = getString(11);
        if (!appId.equals("NETSCAPE2.0")) {
            skipSubBlocks();
            return false;
        }
        int len = nextByte();
        int subBlockId = nextByte();
        if (subBlockId != 1) {
            return false;
        }
        // Netscape Looping Extension
        if (len != 3) {
            throw new AssertionError("Invalid Netscape Looping Extension block size: " + len);
        }
        buf.putChar((char) loopCount);
        int terminatorByte = nextByte();
        if (terminatorByte != 0) {
            throw new AssertionError("Invalid terminator byte " + terminatorByte);
        }
        return true;
    }

    private void editGraphicControlExtension(int delayTime) {
        int blockSize = nextByte();
        if (blockSize != 4) {
            throw new AssertionError("Invalid Graphic Control Extension block size: " + blockSize);
        }
        skip(1); // Packed Fields
        buf.mark(); // will be used to modify the delay time of the final frame
        buf.putChar((char) delayTime);
        skip(1); // transparentColorIndex
        int blockTerminator = nextByte();
        if (blockTerminator != 0) {
            throw new AssertionError(String.format("Invalid block terminator byte: 0x%02x", blockTerminator));
        }
    }

    private void skipLogicalScreenDescriptorAndGlobalColorTable() {
        skip(4); // canvas width and height
        int packedFields = nextByte();
        skip(2); // Background Color Index and Pixel Aspect Ratio
        skipColorTable(packedFields);
    }

    private void skipImageDescriptor() {
        skip(8); // image position and size
        int packedFields = nextByte();
        skipColorTable(packedFields);
        skip(1); // LZW Minimum Code Size
        skipSubBlocks();
    }

    private void skipColorTable(int packedFields) {
        boolean colorTablePresent = (packedFields & 0b1000_000) != 0;
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
        buf.limit(buf.position() + len);
        try {
            return Charset.forName("ISO-8859-1").decode(buf.slice()).toString();
        } finally {
            buf.position(buf.limit());
            buf.limit(buf.capacity());
        }
    }

    private static class FrameIndex {
        private final int trailingFrameCount;
        private final Queue<Integer> trailingFrameOffsets;
        private int firstFrameOffset = -1;
        private boolean frameInProgress = false;

        FrameIndex(int trailingFrameCount) {
            this.trailingFrameCount = trailingFrameCount;
            this.trailingFrameOffsets = new ArrayDeque<>(trailingFrameCount);
        }

        void acceptBlock(ByteBuffer buf) {
            int offset = buf.position();
            int blockType = toUnsignedInt(buf.get(offset));
            if (!frameInProgress) {
                addFrame(offset);
            }
            frameInProgress = blockType != BLOCK_TYPE_IMAGE;
        }

        private void addFrame(int offset) {
            if (firstFrameOffset == -1) {
                firstFrameOffset = offset;
            }
            if (trailingFrameOffsets.size() == trailingFrameCount) {
                trailingFrameOffsets.remove();
            }
            trailingFrameOffsets.add(offset);
        }

        int firstFrameOffset() {
            return firstFrameOffset;
        }

        int offsetOfFirstFrameToKeep() {
            return trailingFrameOffsets.peek();
        }
    }
}
