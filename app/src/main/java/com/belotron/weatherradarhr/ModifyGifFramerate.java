package com.belotron.weatherradarhr;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;

import static java.nio.ByteOrder.LITTLE_ENDIAN;

@SuppressWarnings("MagicNumber")
class ModifyGifFramerate {

    static void editGif(ByteBuffer buf, int delayTime, int lastFrameHoldTime, int loopCount) {
        buf.order(LITTLE_ENDIAN);
        String gifVersion = getString(buf, 6);
        if (!gifVersion.equals("GIF87a") && !gifVersion.equals("GIF89a")) {
            throw new AssertionError("Not a GIF file");
        }
        skipLogicalScreenDescriptorAndGlobalColorTable(buf);
        while (buf.hasRemaining()) {
            int blockType = nextByte(buf);
            switch (blockType) {
                case 0x21:
                    int extensionLabel = nextByte(buf);
                    switch (extensionLabel) {
                        case 0xf9:
                            editGraphicControlExtension(buf, delayTime);
                            break;
                        case 0xfe:
                            skipSubBlocks(buf);
                            break;
                        case 0xff:
                            editApplicationExtension(buf, loopCount);
                            break;
                        default:
                            throw new AssertionError(String.format("Unrecognized extension label: 0x%02x",
                                    extensionLabel));
                    }
                    break;
                case 0x2c:
                    skipImageDescriptor(buf);
                    break;
                case 0x3b: // Trailer block, marks the end of GIF
                    buf.reset(); // restore the position of last frame's delay time
                    System.out.format("Last frame hold time %d%n", lastFrameHoldTime);
                    buf.putChar((char) lastFrameHoldTime);
                    return;
                default:
                    throw new AssertionError(String.format("Unrecognized block type: 0x%02x", blockType));
            }
        }
    }

    private static void skipLogicalScreenDescriptorAndGlobalColorTable(ByteBuffer buf) {
        skip(buf, 4); // canvas width and height
        int packedFields = nextByte(buf);
        skip(buf, 2); // Background Color Index and Pixel Aspect Ratio
        skipColorTable(buf, packedFields);
    }

    private static void skipImageDescriptor(ByteBuffer buf) {
        skip(buf, 8); // image position and size
        int packedFields = nextByte(buf);
        skipColorTable(buf, packedFields);
        skip(buf, 1); // LZW Minimum Code Size
        skipSubBlocks(buf);
    }

    private static void editApplicationExtension(ByteBuffer buf, int loopCount) {
        int blockSize = nextByte(buf);
        if (blockSize != 11) {
            throw new AssertionError("Invalid Application Extension block size: " + blockSize);
        }
        String appId = getString(buf, 11);
        if (!appId.equals("NETSCAPE2.0")) {
            skipSubBlocks(buf);
            return;
        }
        int len = nextByte(buf);
        int subBlockId = nextByte(buf);
        if (subBlockId == 1) {
            // Netscape Looping Extension
            if (len != 3) {
                throw new AssertionError("Invalid Netscape Looping Extension block size: " + len);
            }
            int currLoopCount = buf.getChar(buf.position()); // don't advance position
            System.out.format("Loop Count %d -> %d%n", currLoopCount, loopCount);
            buf.putChar((char) loopCount);
            int terminatorByte = nextByte(buf);
            if (terminatorByte != 0) {
                throw new AssertionError("Invalid terminator byte " + terminatorByte);
            }
        }
    }

    private static void editGraphicControlExtension(ByteBuffer buf, int delayTime) {
        int blockSize = nextByte(buf);
        if (blockSize != 4) {
            throw new AssertionError("Invalid Graphic Control Extension block size: " + blockSize);
        }
        skip(buf, 1); // Packed Fields
        int currDelayTime = buf.getChar(buf.position()); // don't advance position
        buf.mark(); // will be used to modify the delay time of the final frame
        System.out.format("Delay time %d -> %d%n", currDelayTime, delayTime);
        buf.putChar((char) delayTime);
        skip(buf, 1); // transparentColorIndex
        int blockTerminator = nextByte(buf);
        if (blockTerminator != 0) {
            throw new AssertionError(String.format("Invalid block terminator byte: 0x%02x", blockTerminator));
        }
    }

    private static int decodeColorTableSize(int packedFields) {
        boolean colorTablePresent = (packedFields & 0b1000_000) != 0;
        return colorTablePresent ? 1 << ((packedFields & 0b111) + 1) : 0;
    }

    private static void skipColorTable(ByteBuffer buf, int packedFields) {
        skip(buf, 3 * decodeColorTableSize(packedFields));
    }

    private static void skipSubBlocks(ByteBuffer buf) {
        for (int len; (len = nextByte(buf)) != 0; ) {
            skip(buf, len);
        }
    }

    private static void skip(ByteBuffer buf, int len) {
        buf.position(buf.position() + len);
    }

    private static int nextByte(ByteBuffer buf) {
        return ((int) buf.get()) & 0xff;
    }

    private static String getString(ByteBuffer buf, int len) {
        buf.limit(buf.position() + len);
        try {
            return Charset.forName("ISO-8859-1").decode(buf.slice()).toString();
        } finally {
            buf.position(buf.limit());
            buf.limit(buf.capacity());
        }
    }
}
