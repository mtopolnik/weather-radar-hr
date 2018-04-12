package com.belotron.weatherradarhr.gifdecode;

/*
 * Copyright (c) 2013 Xcellent Creations, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining
 * a copy of this software and associated documentation files (the
 * "Software"), to deal in the Software without restriction, including
 * without limitation the rights to use, copy, modify, merge, publish,
 * distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to
 * the following conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE
 * LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION
 * OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION
 * WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

import android.graphics.Bitmap;
import android.graphics.Bitmap.Config;
import android.support.annotation.ColorInt;
import android.support.annotation.IntDef;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.Log;
import com.belotron.weatherradarhr.LogKt;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.nio.ByteBuffer;
import java.util.Arrays;

import static com.belotron.weatherradarhr.gifdecode.GifFrame.*;

/**
 * Reads frame data from a GIF image source and decodes it into individual
 * frames for animation purposes.  Image data can be read from either and
 * InputStream source or a byte[].
 * <p>
 * This class is optimized for running animations with the frames. It lowers
 * its memory footprint by only housing the minimum data necessary to decode
 * the next frame in the animation sequence.
 * <p>
 * Implementation adapted from sample code published in Lyon. (2004). <em>Java for
 * Programmers</em>, republished under the MIT Open Source License.
 *
 * @see <a href="http://show.docjava.com/book/cgij/exportToHTML/ip/gif/stills/GifDecoder.java.html">
 *     Original source</a>
 * @see <a href="https://www.w3.org/Graphics/GIF/spec-gif89a.txt">GIF 89a Specification</a>
 */
public class GifDecoder {

    /** File read status: No errors. */
    static final int STATUS_OK = 0;

    /** File read status: Error decoding file (may be partially decoded). */
    static final int STATUS_FORMAT_ERROR = 1;

    /** File read status: Unable to open source. */
    static final int STATUS_OPEN_ERROR = 2;

    /** Unable to fully decode the current frame. */
    static final int STATUS_PARTIAL_DECODE = 3;

    /**
     * Android Lint annotation for status codes that can be used with a GIF decoder.
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(value = {STATUS_OK, STATUS_FORMAT_ERROR, STATUS_OPEN_ERROR, STATUS_PARTIAL_DECODE})
    @interface GifDecodeStatus {
    }

    private static final String TAG = LogKt.LOGTAG;

    /**
     * Maximum pixel stack size for decoding LZW compressed data.
     */
    private static final int MAX_STACK_SIZE = 4 * 1024;

    private static final int NULL_CODE = -1;

    private static final int BYTES_PER_INTEGER = Integer.SIZE / 8;

    private static final int MASK_INT_LOWEST_BYTE = 0x000000FF;

    @ColorInt
    private static final int COLOR_TRANSPARENT_BLACK = 0x00000000;
    private static final int BUFSIZ = 16 * 1024;

    // Global File Header values and parsing flags.
    /**
     * Active color table.
     * Maximum size is 256, see GifHeaderParser.readColorTable
     */
    @ColorInt
    private int[] act;

    /** Private color table that can be modified if needed. */
    @ColorInt
    private final int[] pct = new int[256];

    private final Allocator allocator;

    // LZW decoder working arrays.
    private final byte[] block = new byte[256];
    private final short[] prefix = new short[MAX_STACK_SIZE];
    private final byte[] suffix = new byte[MAX_STACK_SIZE];
    private final byte[] pixelStack = new byte[MAX_STACK_SIZE + 1];

    private final byte[] pixelCodes;

    @ColorInt
    private final int[] outPixels;

    private ParsedGif parsedGif;

    @GifDecodeStatus
    private int status;

    @Nullable
    private Boolean isFirstFrameTransparent;

    @NonNull
    private Bitmap.Config bitmapConfig = Config.ARGB_8888;

    public GifDecoder(@NonNull Allocator allocator, @NonNull ParsedGif parsedGif) {
        this.allocator = allocator;
        this.parsedGif = parsedGif;
        status = STATUS_OK;
        pixelCodes = allocator.obtainByteArray(parsedGif.width * parsedGif.height);
        outPixels = allocator.obtainIntArray(parsedGif.width * parsedGif.height);
    }

    public int getStatus() {
        return status;
    }

    public int getFrameCount() {
        return parsedGif.frames.size();
    }

    public void clear() {
        parsedGif = null;
        allocator.release(pixelCodes);
        allocator.release(outPixels);
        isFirstFrameTransparent = null;
    }

    public void setDefaultBitmapConfig(@NonNull Bitmap.Config config) {
        if (config != Bitmap.Config.ARGB_8888 && config != Bitmap.Config.RGB_565) {
            throw new IllegalArgumentException("Unsupported format: " + config
                    + ", must be one of " + Bitmap.Config.ARGB_8888 + " or " + Bitmap.Config.RGB_565);
        }

        bitmapConfig = config;
    }

    public Bitmap toBitmap() {
        // Set pixels for current image.
        Bitmap result = obtainBitmap();
        result.setPixels(outPixels, 0, parsedGif.width, 0, 0, parsedGif.width, parsedGif.height);
        return result;
    }

    public Pixels toPixels() {
        return new IntArrayPixels(outPixels.clone(), parsedGif.width);
    }

    public Pixels asPixels() {
        return new IntArrayPixels(outPixels, parsedGif.width);
    }

    public GifDecoder decodeFrame(int index) {
        if (parsedGif.getFrameCount() == 0 || index < 0) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "Unable to decode frame, frameCount=" + parsedGif.getFrameCount() + ", index=" + index);
            }
            status = STATUS_FORMAT_ERROR;
        }
        if (status == STATUS_FORMAT_ERROR || status == STATUS_OPEN_ERROR) {
            throw new RuntimeException("Unable to decode frame, status=" + status);
        }
        status = STATUS_OK;

        GifFrame currentFrame = parsedGif.frames.get(index);
        int previousIndex = index - 1;
        GifFrame previousFrame = previousIndex >= 0 ? parsedGif.frames.get(previousIndex) : null;

        // Set the appropriate color table.
        act = currentFrame.lct != null ? currentFrame.lct : parsedGif.gct;
        if (act == null) {
            // No color table defined.
            status = STATUS_FORMAT_ERROR;
            throw new RuntimeException("No valid color table found for frame #" + index);
        }

        // Reset the transparent pixel in the color table
        if (currentFrame.transparency) {
            // Prepare local copy of color table ("pct = act"), see #1068
            System.arraycopy(act, 0, pct, 0, act.length);
            // Forget about act reference from shared header object, use copied version
            act = pct;
            // Set transparent color if specified.
            act[currentFrame.transIndex] = COLOR_TRANSPARENT_BLACK;
        }
        setPixels(currentFrame, previousFrame);
        return this;
    }

    /**
     * Creates new frame image from current data (and previous frames as specified by their
     * disposal codes).
     */
    private void setPixels(@NonNull GifFrame currentFrame, @Nullable GifFrame previousFrame) {
        // Final location of blended pixels.
        final int[] pixels = this.outPixels;

        if (previousFrame != null && previousFrame.dispose == DISPOSAL_PREVIOUS) {
            throw new RuntimeException(
                    "The animated GIF contains a frame with the Restore To Previous frame disposal method" +
                            " (GIF89a standard, 23.c.iv.3, but this decoder doesn't support it");
        }

        // fill in starting image contents based on last image's dispose code
        if (previousFrame != null && previousFrame.dispose > DISPOSAL_UNSPECIFIED) {
            // We don't need to do anything for DISPOSAL_NONE, if it has the correct pixels so will our
            // mainScratch and therefore so will our pixels array.
            if (previousFrame.dispose == DISPOSAL_BACKGROUND) {
                // Start with a canvas filled with the background color
                @ColorInt int c = COLOR_TRANSPARENT_BLACK;
                if (!currentFrame.transparency) {
                    c = parsedGif.bgColor;
                    if (currentFrame.lct != null && parsedGif.bgIndex == currentFrame.transIndex) {
                        c = COLOR_TRANSPARENT_BLACK;
                    }
                } else if (currentFrame.index == 0) {
                    isFirstFrameTransparent = true;
                }
                // The area used by the graphic must be restored to the background color.
                int downsampledIW = previousFrame.iw;
                int gifWidth = parsedGif.width;
                int topLeft = previousFrame.iy * gifWidth + previousFrame.ix;
                int bottomLeft = topLeft + previousFrame.ih * gifWidth;
                for (int left = topLeft; left < bottomLeft; left += gifWidth) {
                    int right = left + downsampledIW;
                    for (int pointer = left; pointer < right; pointer++) {
                        pixels[pointer] = c;
                    }
                }
            }
        }

        // Decode pixels for this frame into mainPixels.
        decodeBitmapData(currentFrame);

        if (currentFrame.interlace) {
            copyCopyIntoScratchRobust(currentFrame);
        } else {
            copyIntoScratchFast(currentFrame);
        }
    }

    private void copyIntoScratchFast(@NonNull GifFrame frame) {
        int[] dest = outPixels;
        int ix = frame.ix;
        int iy = frame.iy;
        int ih = frame.ih;
        int iw = frame.iw;
        // Copy each source line to the appropriate place in the destination.
        boolean isFirstFrame = frame.index == 0;
        int width = this.parsedGif.width;
        byte[] mainPixels = this.pixelCodes;
        int[] act = this.act;
        byte transparentColorIndex = -1;
        for (int i = 0; i < ih; i++) {
            int line = i + iy;
            int k = line * width;
            // Start of line in dest.
            int dx = k + ix;
            // End of dest line.
            int dlim = dx + iw;
            if (k + width < dlim) {
                // Past dest edge.
                dlim = k + width;
            }
            // Start of line in source.
            int sx = i * frame.iw;

            while (dx < dlim) {
                byte byteCurrentColorIndex = mainPixels[sx];
                int currentColorIndex = toUnsignedInt(byteCurrentColorIndex);
                if (currentColorIndex != transparentColorIndex) {
                    int color = act[currentColorIndex];
                    if (color != COLOR_TRANSPARENT_BLACK) {
                        dest[dx] = color;
                    } else {
                        transparentColorIndex = byteCurrentColorIndex;
                    }
                }
                ++sx;
                ++dx;
            }
        }

        isFirstFrameTransparent = isFirstFrameTransparent == null && isFirstFrame && transparentColorIndex != -1;
    }

    private void copyCopyIntoScratchRobust(@NonNull GifFrame frame) {
        int[] dest = outPixels;
        // Copy each source line to the appropriate place in the destination.
        int pass = 1;
        int inc = 8;
        int iline = 0;
        boolean isFirstFrame = frame.index == 0;
        byte[] mainPixels = this.pixelCodes;
        int[] act = this.act;
        @Nullable
        Boolean isFirstFrameTransparent = this.isFirstFrameTransparent;
        int ix = frame.ix;
        int iy = frame.iy;
        int ih = frame.ih;
        int iw = frame.iw;
        int gifHeight = parsedGif.height;
        int gifWidth = parsedGif.width;
        for (int i = 0; i < ih; i++) {
            int line = i;
            if (frame.interlace) {
                if (iline >= ih) {
                    pass++;
                    switch (pass) {
                        case 2:
                            iline = 4;
                            break;
                        case 3:
                            iline = 2;
                            inc = 4;
                            break;
                        case 4:
                            iline = 1;
                            inc = 2;
                            break;
                        default:
                            break;
                    }
                }
                line = iline;
                iline += inc;
            }
            line += iy;
            if (line < gifHeight) {
                int k = line * gifWidth;
                // Start of line in dest.
                int dx = k + ix;
                // End of dest line.
                int dlim = dx + iw;
                if (k + gifWidth < dlim) {
                    // Past dest edge.
                    dlim = k + gifWidth;
                }
                // Start of line in source.
                int sx = i * iw;
                while (dx < dlim) {
                    int currentColorIndex = toUnsignedInt(mainPixels[sx]);
                    int averageColor = act[currentColorIndex];
                    if (averageColor != COLOR_TRANSPARENT_BLACK) {
                        dest[dx] = averageColor;
                    } else if (isFirstFrame && isFirstFrameTransparent == null) {
                        isFirstFrameTransparent = true;
                    }
                    sx++;
                    dx++;
                }
            }
        }

        if (this.isFirstFrameTransparent == null) {
            this.isFirstFrameTransparent = isFirstFrameTransparent != null;
        }
    }

    /**
     * Decodes LZW image data into pixel array. Adapted from John Cristy's BitmapMagick.
     */
    private void decodeBitmapData(@NonNull GifFrame frame) {
        ByteBuffer frameData = frame.frameData.duplicate();
        final byte[] mainPixels = this.pixelCodes;
        final short[] prefix = this.prefix;
        final byte[] suffix = this.suffix;
        final byte[] pixelStack = this.pixelStack;

        // Initialize GIF data stream decoder.
        final int dataSize = readByte(frameData);
        final int clear = 1 << dataSize;
        int codeSize = dataSize + 1;
        int codeMask = (1 << codeSize) - 1;
        for (int code = 0; code < clear; code++) {
            prefix[code] = 0;
            suffix[code] = (byte) code;
        }
        final byte[] block = this.block;
        // Decode GIF pixel stream.
        int pi, bi = 0, top = 0, first = 0, datum = 0, count = 0, bits = 0;
        int oldCode = NULL_CODE;
        int available = clear + 2;
        final int endOfInformation = clear + 1;
        final int npix = frame.iw * frame.ih;
        decoderLoop:
        for (pi = 0; pi < npix;) {
            while (top > 0) {
                // Pop a pixel off the pixel stack.
                mainPixels[pi++] = pixelStack[--top];
                if (pi == npix) {
                    break decoderLoop;
                }
            }
            while (bits < codeSize) {
                // Read a new data block.
                if (count == 0) {
                    count = readBlock(frameData);
                    if (count <= 0) {
                        status = STATUS_PARTIAL_DECODE;
                        break;
                    }
                    bi = 0;
                }
                datum += toUnsignedInt(block[bi]) << bits;
                bits += 8;
                ++bi;
                --count;
            }

            // Get the next code.
            int code = datum & codeMask;
            datum >>= codeSize;
            bits -= codeSize;

            // Interpret the code.
            if (code > available || code == endOfInformation) {
                break;
            }
            if (code == clear) {
                // Reset decoder.
                codeSize = dataSize + 1;
                codeMask = (1 << codeSize) - 1;
                available = clear + 2;
                oldCode = NULL_CODE;
                continue;
            }
            if (oldCode == NULL_CODE) {
                mainPixels[pi++] = suffix[code];
                oldCode = code;
                first = code;
                continue;
            }
            final int inCode = code;
            if (code == available) {
                pixelStack[top++] = (byte) first;
                code = oldCode;
            }
            while (code > clear) {
                pixelStack[top++] = suffix[code];
                code = prefix[code];
            }
            first = toUnsignedInt(suffix[code]);

            // Add a new string to the string table

            if (available >= MAX_STACK_SIZE) {
                // We ran out of dictionary space
                break;
            }
            mainPixels[pi++] = (byte) first;
            prefix[available] = (short) oldCode;
            suffix[available] = (byte) first;
            ++available;
            if (((available & codeMask) == 0) && (available < MAX_STACK_SIZE)) {
                ++codeSize;
                codeMask += available;
            }
            oldCode = inCode;
        }

        // Clear missing pixels.
        Arrays.fill(mainPixels, pi, npix, (byte) COLOR_TRANSPARENT_BLACK);
    }

    /**
     * Reads a single byte from the input stream.
     */
    private static int readByte(ByteBuffer buf) {
        return toUnsignedInt(buf.get());
    }

    /**
     * Reads next variable length block from input.
     *
     * @return number of bytes stored in "buffer".
     */
    private int readBlock(ByteBuffer buf) {
        int blockSize = readByte(buf);
        if (blockSize <= 0) {
            return blockSize;
        }
        buf.get(block, 0, Math.min(blockSize, buf.remaining()));
        return blockSize;
    }

    private Bitmap obtainBitmap() {
        Bitmap.Config config = isFirstFrameTransparent == null || isFirstFrameTransparent
                ? Bitmap.Config.ARGB_8888 : bitmapConfig;
        Bitmap result = allocator.obtain(parsedGif.width, parsedGif.height, config);
        result.setHasAlpha(true);
        return result;
    }

    private static int toUnsignedInt(byte b) {
        return b & MASK_INT_LOWEST_BYTE;
    }
}
