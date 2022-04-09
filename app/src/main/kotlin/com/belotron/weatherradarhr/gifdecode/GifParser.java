package com.belotron.weatherradarhr.gifdecode;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.belotron.weatherradarhr.Frame;
import com.belotron.weatherradarhr.FrameSequence;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static com.belotron.weatherradarhr.gifdecode.GifFrame.DISPOSAL_NONE;
import static com.belotron.weatherradarhr.gifdecode.GifFrame.DISPOSAL_UNSPECIFIED;

/**
 * A class responsible for creating {@link GifSequence}s from data
 * representing animated GIFs.
 *
 * @see <a href="https://www.w3.org/Graphics/GIF/spec-gif89a.txt">GIF 89a Specification</a>
 */
@SuppressWarnings("MagicNumber")
public class GifParser {
    private static final String TAG = "GifParser";

    private static final int MASK_INT_LOWEST_BYTE = 0x000000FF;

    /**
     * Identifies the beginning of an Image Descriptor.
     */
    private static final int IMAGE_SEPARATOR = 0x2C;
    /**
     * Identifies the beginning of an extension block.
     */
    private static final int EXTENSION_INTRODUCER = 0x21;
    /**
     * This block is a single-field block indicating the end of the GIF Data Stream.
     */
    private static final int TRAILER = 0x3B;
    // Possible labels that identify the current extension block.
    private static final int LABEL_GRAPHIC_CONTROL_EXTENSION = 0xF9;
    private static final int LABEL_APPLICATION_EXTENSION = 0xFF;
    private static final int LABEL_COMMENT_EXTENSION = 0xFE;
    private static final int LABEL_PLAIN_TEXT_EXTENSION = 0x01;

    // Graphic Control Extension packed field masks

    /**
     * Mask (bits 4-2) to extract Disposal Method of the current frame.
     *
     * See {@code GifFrame.GifDisposalMethod} for possible values
     */
    private static final int GCE_MASK_DISPOSAL_METHOD = 0b00011100;
    /**
     * Shift so the Disposal Method extracted from the packed value is on the least significant bit.
     */
    private static final int GCE_DISPOSAL_METHOD_SHIFT = 2;
    /**
     * Mask (bit 0) to extract Transparent Color Flag of the current frame.
     * <p><b>GIF89a</b>: <i>Indicates whether a transparency index is given
     * in the Transparent Index field.</i></p>
     * Possible values are:<ul>
     * <li>0 - Transparent Index is not given.</li>
     * <li>1 - Transparent Index is given.</li>
     * </ul>
     */
    private static final int GCE_MASK_TRANSPARENT_COLOR_FLAG = 0b00000001;

    // Image Descriptor packed field masks (describing Local Color Table)

    /**
     * Mask (bit 7) to extract Local Color Table Flag of the current image.
     * <p><b>GIF89a</b>: <i>Indicates the presence of a Local Color Table
     * immediately following this Image Descriptor.</i></p>
     */
    private static final int DESCRIPTOR_MASK_LCT_FLAG = 0b10000000;
    /**
     * Mask (bit 6) to extract Interlace Flag of the current image.
     * <p><b>GIF89a</b>: <i>Indicates if the image is interlaced.
     * An image is interlaced in a four-pass interlace pattern.</i></p>
     * Possible values are:<ul>
     * <li>0 - Image is not interlaced.</li>
     * <li>1 - Image is interlaced.</li>
     * </ul>
     */
    private static final int DESCRIPTOR_MASK_INTERLACE_FLAG = 0b01000000;
    /**
     * Mask (bits 2-0) to extract Size of the Local Color Table of the current image.
     * <p><b>GIF89a</b>: <i>If the Local Color Table Flag is set to 1, the value in this
     * field is used to calculate the number of bytes contained in the Local Color Table.
     * To determine that actual size of the color table, raise 2 to [the value of the field + 1].
     * This value should be 0 if there is no Local Color Table specified.</i></p>
     */
    private static final int DESCRIPTOR_MASK_LCT_SIZE = 0b00000111;

    // Logical Screen Descriptor packed field masks (describing Global Color Table)

    /**
     * Mask (bit 7) to extract Global Color Table Flag of the current image.
     * <p><b>GIF89a</b>: <i>Indicates the presence of a Global Color Table
     * immediately following this Image Descriptor.</i></p>
     * Possible values are:<ul>
     * <li>0 - No Global Color Table follows, the Background Color Index field is meaningless.</li>
     * <li>1 - A Global Color Table will immediately follow,
     * the Background Color Index field is meaningful.</li>
     * </ul>
     */
    private static final int LSD_MASK_GCT_FLAG = 0b10000000;
    /**
     * Mask (bits 2-0) to extract Size of the Global Color Table of the current image.
     * <p><b>GIF89a</b>: <i>If the Global Color Table Flag is set to 1, the value in this
     * field is used to calculate the number of bytes contained in the Global Color Table.
     * To determine that actual size of the color table, raise 2 to [the value of the field + 1].
     * Even if there is no Global Color Table specified, set this field according to the above
     * formula so that decoders can choose the best graphics mode to display the stream in.</i></p>
     */
    private static final int LSD_MASK_GCT_SIZE = 0b00000111;

    /**
     * The minimum frame delay in hundredths of a second.
     */
    private static final int MIN_FRAME_DELAY = 2;
    /**
     * The default frame delay in hundredths of a second.
     * This is used for GIFs with frame delays less than the minimum.
     */
    private static final int DEFAULT_FRAME_DELAY = 10;

    private static final int MAX_BLOCK_SIZE = 256;
    // Raw data read working array.
    private final byte[] block = new byte[MAX_BLOCK_SIZE];

    @NonNull
    private final ByteBuffer rawData;
    @NonNull
    private final GifSequence gifSequence = new GifSequence();
    private GifFrame currentFrame;
    private int blockSize = 0;

    private GifParser(@NonNull byte[] data) {
        rawData = ByteBuffer.wrap(data).asReadOnlyBuffer();
        rawData.position(0);
        rawData.order(ByteOrder.LITTLE_ENDIAN);
    }

    @NonNull
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static FrameSequence<Frame> parse(byte[] data) {
        GifParser parser = new GifParser(data);
        parser.readHeader();
        parser.readContents();
        GifSequence gifSequence = parser.gifSequence;
        if (gifSequence.getFrames().size() == 0) {
            throw new ImgDecodeException("The GIF contains zero images");
        }
        return (FrameSequence<Frame>) (FrameSequence) gifSequence;
    }

    /**
     * Reads GIF file header information.
     */
    private void readHeader() {
        StringBuilder id = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            id.append((char) read());
        }
        if (!id.toString().startsWith("GIF")) {
            throw new ImgDecodeException("Image data doesn't start with 'GIF'");
        }
        readLSD();
        if (gifSequence.gctFlag) {
            int[] gct = readColorTable(gifSequence.gctSize);
            if (gct == null) {
                return;
            }
            gifSequence.gct = gct;
            gifSequence.bgColor = gifSequence.gct[gifSequence.bgIndex];
        }
    }

    /**
     * Reads Logical Screen Descriptor.
     */
    private void readLSD() {
        // Logical screen size.
        gifSequence.width = readShort();
        gifSequence.height = readShort();
        /*
         * Logical Screen Descriptor packed field:
         *      7 6 5 4 3 2 1 0
         *     +---------------+
         *  4  | |     | |     |
         *
         * Global Color Table Flag     1 Bit
         * Color Resolution            3 Bits
         * Sort Flag                   1 Bit
         * Size of Global Color Table  3 Bits
         */
        int packed = read();
        gifSequence.gctFlag = (packed & LSD_MASK_GCT_FLAG) != 0;
        gifSequence.gctSize = (int) Math.pow(2, (packed & LSD_MASK_GCT_SIZE) + 1);
        // Background color index.
        gifSequence.bgIndex = read();
        // Pixel aspect ratio
        gifSequence.pixelAspect = read();
    }

    /**
     * Reads color table as 256 RGB integer values.
     *
     * @param nColors int number of colors to read.
     * @return int array containing 256 colors (packed ARGB with full alpha).
     */
    @Nullable
    private int[] readColorTable(int nColors) {
        try {
            int nBytes = 3 * nColors;
            byte[] c = new byte[nBytes];
            rawData.get(c);
            // Max size to avoid bounds checks.
            int[] tab = new int[MAX_BLOCK_SIZE];
            int i = 0;
            int j = 0;
            while (i < nColors) {
                int r = ((int) c[j++]) & MASK_INT_LOWEST_BYTE;
                int g = ((int) c[j++]) & MASK_INT_LOWEST_BYTE;
                int b = ((int) c[j++]) & MASK_INT_LOWEST_BYTE;
                tab[i++] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
            return tab;
        } catch (BufferUnderflowException e) {
            throw new ImgDecodeException("Format error while redaing color table", e);
        }
    }

    /**
     * Main file parser. Reads GIF content blocks. Stops after reading maxFrames
     */
    private void readContents() {
        // Read GIF file content blocks.
        readLoop: while (true) {
            int code = read();
            switch (code) {
                case IMAGE_SEPARATOR:
                    // The Graphic Control Extension is optional, but will always come first if it exists.
                    // If one did exist, there will be a non-null current frame which we should use.
                    // However if one did not exist, the current frame will be null
                    // and we must create it here. See issue #134.
                    if (currentFrame == null) {
                        currentFrame = new GifFrame(gifSequence.getFrames().size());
                    }
                    readBitmap();
                    break;
                case EXTENSION_INTRODUCER:
                    int extensionLabel = read();
                    switch (extensionLabel) {
                        case LABEL_GRAPHIC_CONTROL_EXTENSION:
                            // Start a new frame.
                            currentFrame = new GifFrame(gifSequence.getFrames().size());
                            readGraphicControlExt();
                            break;
                        case LABEL_APPLICATION_EXTENSION:
                            readBlock();
                            StringBuilder app = new StringBuilder();
                            for (int i = 0; i < 11; i++) {
                                app.append((char) block[i]);
                            }
                            if (app.toString().equals("NETSCAPE2.0")) {
                                readNetscapeExt();
                            } else {
                                // Don't care.
                                skip();
                            }
                            break;
                        case LABEL_COMMENT_EXTENSION:
                            skip();
                            break;
                        case LABEL_PLAIN_TEXT_EXTENSION:
                            skip();
                            break;
                        default:
                            // Uninteresting extension.
                            skip();
                    }
                    break;
                case TRAILER:
                    // This block is a single-field block indicating the end of the GIF Data Stream.
                    break readLoop;
                // Bad byte, but keep going and see what happens
                case 0x00:
                default:
                    throw new ImgDecodeException("Bad byte at " + (rawData.position() - 1) + ": " + code);
            }
        }
    }

    /**
     * Reads Graphic Control Extension values.
     */
    private void readGraphicControlExt() {
        // Block size.
        read();
        /*
         * Graphic Control Extension packed field:
         *      7 6 5 4 3 2 1 0
         *     +---------------+
         *  1  |     |     | | |
         *
         * Reserved                    3 Bits
         * Disposal Method             3 Bits
         * User Input Flag             1 Bit
         * Transparent Color Flag      1 Bit
         */
        int packed = read();
        // Disposal method.
        //noinspection WrongConstant field has to be extracted from packed value
        currentFrame.dispose = (packed & GCE_MASK_DISPOSAL_METHOD) >> GCE_DISPOSAL_METHOD_SHIFT;
        if (currentFrame.dispose == DISPOSAL_UNSPECIFIED) {
            // Elect to keep old image if discretionary.
            currentFrame.dispose = DISPOSAL_NONE;
        }
        currentFrame.transparency = (packed & GCE_MASK_TRANSPARENT_COLOR_FLAG) != 0;
        // Delay in milliseconds.
        int delayInHundredthsOfASecond = readShort();
        if (delayInHundredthsOfASecond < MIN_FRAME_DELAY) {
            delayInHundredthsOfASecond = DEFAULT_FRAME_DELAY;
        }
        currentFrame.delay = delayInHundredthsOfASecond * 10;
        // Transparent color index
        currentFrame.transIndex = read();
        // Block terminator
        read();
    }

    /**
     * Reads next frame image.
     */
    private void readBitmap() {
        // (sub)image position & size.
        currentFrame.ix = readShort();
        currentFrame.iy = readShort();
        currentFrame.iw = readShort();
        currentFrame.ih = readShort();

        /*
         * Image Descriptor packed field:
         *     7 6 5 4 3 2 1 0
         *    +---------------+
         * 9  | | | |   |     |
         *
         * Local Color Table Flag     1 Bit
         * Interlace Flag             1 Bit
         * Sort Flag                  1 Bit
         * Reserved                   2 Bits
         * Size of Local Color Table  3 Bits
         */
        int packed = read();
        int lctSize = (int) Math.pow(2, (packed & DESCRIPTOR_MASK_LCT_SIZE) + 1);
        currentFrame.interlace = (packed & DESCRIPTOR_MASK_INTERLACE_FLAG) != 0;
        if ((packed & DESCRIPTOR_MASK_LCT_FLAG) != 0) {
            currentFrame.lct = readColorTable(lctSize);
        } else {
            // No local color table.
            currentFrame.lct = null;
        }
        rawData.mark();
        skipImageData();
        int frameEndOffset = rawData.position();
        rawData.reset();
        rawData.limit(frameEndOffset);
        currentFrame.frameData = rawData.slice();
        rawData.position(frameEndOffset);
        rawData.limit(rawData.capacity());
        // Add image to frame.
        gifSequence.getFrames().add(currentFrame);
    }

    /**
     * Reads Netscape extension to obtain iteration count.
     */
    private void readNetscapeExt() {
        do {
            readBlock();
            if (block[0] == 1) {
                // Loop count sub-block.
                int b1 = ((int) block[1]) & MASK_INT_LOWEST_BYTE;
                int b2 = ((int) block[2]) & MASK_INT_LOWEST_BYTE;
                gifSequence.loopCount = (b2 << 8) | b1;
            }
        } while (blockSize > 0);
    }

    /**
     * Skips LZW image data for a single frame to advance buffer.
     */
    private void skipImageData() {
        // lzwMinCodeSize
        read();
        // data sub-blocks
        skip();
    }

    /**
     * Skips variable length blocks up to and including next zero length block.
     */
    private void skip() {
        int blockSize;
        do {
            blockSize = read();
            int newPosition = rawData.position() + blockSize;
            if (newPosition >= rawData.limit()) {
                rawData.position(rawData.limit());
                break;
            }
            rawData.position(newPosition);
        } while (blockSize > 0);
    }

    private void printAsciiData() {
        StringBuilder comment = new StringBuilder();
        int blockSize;
        boolean endReached = false;
        do {
            blockSize = read();
            if (rawData.position() + blockSize >= rawData.limit()) {
                blockSize = rawData.limit() - rawData.position();
                endReached = true;
            }
            for (int i = 0; i < blockSize; i++) {
                comment.append((char) rawData.get());
            }
        } while (blockSize > 0 || endReached);
        if (comment.length() > 0) {
            System.out.println("Comment: " + comment);
        }
    }

    /**
     * Reads next variable length block from input.
     */
    private void readBlock() {
        blockSize = read();
        if (blockSize > 0) {
            int count = 0;
            int n = 0;
            try {
                while (n < blockSize) {
                    count = blockSize - n;
                    rawData.get(block, n, count);
                    n += count;
                }
            } catch (Exception e) {
                throw new ImgDecodeException(
                        "Error reading block, n = " + n + " count = " + count + " blockSize = " + blockSize, e);
            }
        }
    }

    /**
     * Reads a single byte from the input stream.
     */
    private int read() {
        try {
            return rawData.get() & MASK_INT_LOWEST_BYTE;
        } catch (BufferUnderflowException e) {
            throw new ImgDecodeException("GIF parse error", e);
        }
    }

    /**
     * Reads next 16-bit value, LSB first.
     */
    private int readShort() {
        // Read 16-bit value.
        return rawData.getShort();
    }
}
