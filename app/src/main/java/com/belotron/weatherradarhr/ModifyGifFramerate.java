package com.belotron.weatherradarhr;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;

import static java.nio.ByteOrder.LITTLE_ENDIAN;

@SuppressWarnings("MagicNumber")
class ModifyGifFramerate {

    private final ByteBuffer buf;

    private ModifyGifFramerate(ByteBuffer buf) {
        this.buf = buf;
    }

    static void modifyGifFramerate(byte[] bytes, int delayTime, int lastFrameHoldTime, int loopCount) {
        new ModifyGifFramerate(ByteBuffer.wrap(bytes)).go(delayTime, lastFrameHoldTime, loopCount);
    }

    private void go(int delayTime, int lastFrameHoldTime, int loopCount) {
        buf.order(LITTLE_ENDIAN);
        String gifVersion = getString(6);
        if (!gifVersion.equals("GIF87a") && !gifVersion.equals("GIF89a")) {
            throw new AssertionError("Not a GIF file");
        }
        skipLogicalScreenDescriptorAndGlobalColorTable();
        while (buf.hasRemaining()) {
            int blockType = nextByte();
            switch (blockType) {
                case 0x21:
                    int extensionLabel = nextByte();
                    switch (extensionLabel) {
                        case 0xf9:
                            editGraphicControlExtension(delayTime);
                            break;
                        case 0xff:
                            editApplicationExtension(loopCount);
                            break;
                        default:
                            skipSubBlocks();
                    }
                    break;
                case 0x2c:
                    skipImageDescriptor();
                    break;
                case 0x3b: // Trailer block, marks the end of GIF
                    buf.reset(); // restore the position of last frame's delay time
                    buf.putChar((char) lastFrameHoldTime);
                    return;
                default:
                    throw new AssertionError(String.format("Unrecognized block type: 0x%02x", blockType));
            }
        }
    }

    private void editApplicationExtension(int loopCount) {
        int blockSize = nextByte();
        if (blockSize != 11) {
            throw new AssertionError("Invalid Application Extension block size: " + blockSize);
        }
        String appId = getString(11);
        if (!appId.equals("NETSCAPE2.0")) {
            skipSubBlocks();
            return;
        }
        int len = nextByte();
        int subBlockId = nextByte();
        if (subBlockId == 1) {
            // Netscape Looping Extension
            if (len != 3) {
                throw new AssertionError("Invalid Netscape Looping Extension block size: " + len);
            }
            buf.putChar((char) loopCount);
            int terminatorByte = nextByte();
            if (terminatorByte != 0) {
                throw new AssertionError("Invalid terminator byte " + terminatorByte);
            }
        }
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
        return ((int) buf.get()) & 0xff;
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
}
