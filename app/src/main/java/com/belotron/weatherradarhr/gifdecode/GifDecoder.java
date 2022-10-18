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

import static com.belotron.weatherradarhr.gifdecode.GifFrame.DISPOSAL_BACKGROUND;
import static com.belotron.weatherradarhr.gifdecode.GifFrame.DISPOSAL_PREVIOUS;
import static com.belotron.weatherradarhr.gifdecode.GifFrame.DISPOSAL_UNSPECIFIED;

import android.graphics.Bitmap;
import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.belotron.weatherradarhr.FrameDecoder;
import kotlin.jvm.functions.Function1;

import java.nio.ByteBuffer;
import java.util.Arrays;

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
 * Original source</a>
 * @see <a href="https://www.w3.org/Graphics/GIF/spec-gif89a.txt">GIF 89a Specification</a>
 */
public class GifDecoder implements FrameDecoder<GifFrame>
{

    /**
     * Dictionary size for decoding LZW compressed data.
     */
    private static final int DICT_SIZE = 4 * 1024;

    private static final int NULL_CODE = -1;

    private static final int MASK_INT_LOWEST_BYTE = 0x000000FF;

    @ColorInt private static final int COLOR_TRANSPARENT_BLACK = 0x00000000;

    /**
     * Active color table. Maximum size is 256, see GifHeaderParser.readColorTable
     */
    @ColorInt private int[] act;

    /**
     * Private color table that can be modified if needed.
     */
    @ColorInt private final int[] pct = new int[256];

    private final Allocator allocator;

    // LZW decoder working arrays.
    private final byte[] block = new byte[256];
    private final short[] prefix = new short[DICT_SIZE];
    private final byte[] suffix = new byte[DICT_SIZE];
    private final byte[] pixelStack = new byte[DICT_SIZE + 1];

    private final byte[] pixelCodes;
    @ColorInt private final int[] outPixels;
    @Nullable private Boolean isFirstFrameTransparent;

    private GifSequence gifSequence;
    private final Function1<? super Pixels, Long> ocrTimestamp;

    public GifDecoder(
        @NonNull Allocator allocator,
        @NonNull GifSequence gifSequence,
        @NonNull Function1<? super Pixels, Long> ocrTimestamp
    ) {
        this.allocator = allocator;
        this.gifSequence = gifSequence;
        this.ocrTimestamp = ocrTimestamp;
        pixelCodes = allocator.obtainByteArray(gifSequence.width * gifSequence.height);
        outPixels = allocator.obtainIntArray(gifSequence.width * gifSequence.height);
    }

    @NonNull @Override public GifSequence getSequence() {
        return gifSequence;
    }

    @Override public void dispose() {
        gifSequence = null;
        allocator.dispose();
        isFirstFrameTransparent = null;
    }

    @Override public void release(@NonNull Bitmap bitmap) {
        allocator.release(bitmap);
    }

    public Bitmap decodeFrame(int frameIndex) {
        GifDecoder gifDecoder = gotoAndDecode(frameIndex);
        Bitmap result = gifDecoder.obtainBitmap();
        result.setPixels(gifDecoder.outPixels, 0, gifDecoder.gifSequence.width, 0, 0, gifDecoder.gifSequence.width,
            gifDecoder.gifSequence.height);
        return result;
    }

    @Override
    public void assignTimestamp(int frameIndex) {
        gotoAndDecode(frameIndex);
        gifSequence.getFrames()
                   .get(frameIndex)
                   .setTimestamp(ocrTimestamp.invoke(new IntArrayPixels(outPixels, gifSequence.width)));
    }

    private GifDecoder gotoAndDecode(int frameIndex) {
        if (frameIndex < 0) {
            throw new ImgDecodeException("Asked to decode frame " + frameIndex);
        }
        if (frameIndex >= gifSequence.getFrames().size()) {
            throw new ImgDecodeException("Asked to decode frame " + frameIndex + ", but frame count is " +
                gifSequence.getFrames().size());
        }
        try {
            GifFrame currentFrame = gifSequence.getFrames().get(frameIndex);
            int previousIndex = frameIndex - 1;
            GifFrame previousFrame = previousIndex >= 0 ? gifSequence.getFrames().get(previousIndex) : null;

            // Set the appropriate color table.
            act = currentFrame.lct != null ? currentFrame.lct : gifSequence.gct;
            if (act == null) {
                // No color table defined.
                throw new ImgDecodeException("No valid color table found for frame #" + frameIndex);
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
        } catch (ImgDecodeException e) {
            throw e;
        } catch (Exception e) {
            throw new ImgDecodeException(e);
        }
    }

    /**
     * Creates new frame image from current data (and previous frames as specified by their
     * disposal codes).
     */
    private void setPixels(@NonNull GifFrame currentFrame, @Nullable GifFrame previousFrame) {
        if (previousFrame != null && previousFrame.dispose == DISPOSAL_PREVIOUS) {
            throw new ImgDecodeException("The animated GIF contains a frame with the Restore To Previous frame " +
                "disposal method" + " (GIF89a standard, 23.c.iv.3, but this decoder doesn't support it");
        }

        // fill in starting image contents based on last image's dispose code
        if (previousFrame != null && previousFrame.dispose > DISPOSAL_UNSPECIFIED) {
            // We don't need to do anything for DISPOSAL_NONE, if it has the correct pixels so will our
            // mainScratch and therefore so will our pixels array.
            if (previousFrame.dispose == DISPOSAL_BACKGROUND) {
                // Start with a canvas filled with the background color
                @ColorInt int c = COLOR_TRANSPARENT_BLACK;
                if (!currentFrame.transparency) {
                    c = gifSequence.bgColor;
                    if (currentFrame.lct != null && gifSequence.bgIndex == currentFrame.transIndex) {
                        c = COLOR_TRANSPARENT_BLACK;
                    }
                } else if (currentFrame.index == 0) {
                    isFirstFrameTransparent = true;
                }
                // The area used by the graphic must be restored to the background color.
                int downsampledIW = previousFrame.iw;
                int gifWidth = gifSequence.width;
                int topLeft = previousFrame.iy * gifWidth + previousFrame.ix;
                int bottomLeft = topLeft + previousFrame.ih * gifWidth;
                // Final location of blended pixels.
                int[] outPixels = this.outPixels;
                for (int left = topLeft; left < bottomLeft; left += gifWidth) {
                    int right = left + downsampledIW;
                    for (int pointer = left; pointer < right; pointer++) {
                        outPixels[pointer] = c;
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
        int ix = frame.ix;
        int iy = frame.iy;
        int ih = frame.ih;
        int iw = frame.iw;
        // Copy each source line to the appropriate place in the destination.
        boolean isFirstFrame = frame.index == 0;
        int width = this.gifSequence.width;
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
                byte byteCurrentColorIndex = this.pixelCodes[sx];
                int currentColorIndex = toUnsignedInt(byteCurrentColorIndex);
                if (currentColorIndex != transparentColorIndex) {
                    int color = act[currentColorIndex];
                    if (color != COLOR_TRANSPARENT_BLACK) {
                        outPixels[dx] = color;
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
        // Copy each source line to the appropriate place in the destination.
        int pass = 1;
        int inc = 8;
        int iline = 0;
        boolean isFirstFrame = frame.index == 0;
        int[] act = this.act;
        @Nullable Boolean isFirstFrameTransparent = this.isFirstFrameTransparent;
        int ix = frame.ix;
        int iy = frame.iy;
        int ih = frame.ih;
        int iw = frame.iw;
        int gifHeight = gifSequence.height;
        int gifWidth = gifSequence.width;
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
                    int currentColorIndex = toUnsignedInt(this.pixelCodes[sx]);
                    int averageColor = act[currentColorIndex];
                    if (averageColor != COLOR_TRANSPARENT_BLACK) {
                        outPixels[dx] = averageColor;
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
        for (pi = 0; pi < npix; ) {
            while (top > 0) {
                // Pop a pixel off the pixel stack.
                pixelCodes[pi++] = pixelStack[--top];
                if (pi == npix) {
                    break decoderLoop;
                }
            }
            while (bits < codeSize) {
                // Read a new data block.
                if (count == 0) {
                    count = readBlock(frameData);
                    if (count <= 0) {
                        // End of data before emitting all the pixels
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
                pixelCodes[pi++] = suffix[code];
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
            pixelCodes[pi++] = (byte) first;
            if (available < DICT_SIZE) {
                // Add a new string to the string table.
                prefix[available] = (short) oldCode;
                suffix[available] = (byte) first;
                ++available;
                if (((available & codeMask) == 0) && (available < DICT_SIZE)) {
                    ++codeSize;
                    codeMask += available;
                }
            }
            oldCode = inCode;
        }

        // Clear missing pixels.
        Arrays.fill(pixelCodes, pi, npix, (byte) COLOR_TRANSPARENT_BLACK);
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
        Bitmap result = allocator.obtain(gifSequence.width, gifSequence.height, Bitmap.Config.ARGB_8888);
        result.setHasAlpha(true);
        return result;
    }

    private static int toUnsignedInt(byte b) {
        return b & MASK_INT_LOWEST_BYTE;
    }
}
