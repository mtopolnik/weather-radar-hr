package com.belotron.weatherradarhr.gifdecode;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import com.belotron.weatherradarhr.FrameSequence;
import kotlin.jvm.functions.Function1;

import java.util.ArrayList;
import java.util.List;

/**
 * A header object containing the number of frames in an animated GIF image as well as basic
 * metadata like width and height that can be used to decode each individual frame of the GIF. Can
 * be shared by one or more {@link GifDecoder}s to play the same
 * animated GIF in multiple views.
 *
 * @see <a href="https://www.w3.org/Graphics/GIF/spec-gif89a.txt">GIF 89a Specification</a>
 */
public class GifSequence implements FrameSequence<GifFrame> {

    /** Indicates that this header has no "Netscape" loop count. */
    private static final int NETSCAPE_LOOP_COUNT_DOES_NOT_EXIST = -1;

    @NonNull
    private final List<GifFrame> frames = new ArrayList<>();

    @NonNull @Override
    public List<GifFrame> getFrames() {
        return frames;
    }

    @NonNull @Override
    public GifDecoder intoDecoder(@NonNull Allocator allocator, Function1<? super Pixels, Long> ocrTimestamp) {
        return new GifDecoder(allocator, this, ocrTimestamp);
    }

    @ColorInt
    int[] gct;

    /** Logical screen size: Full image width. */
    int width;

    /** Logical screen size: Full image height. */
    int height;

    // 1 : global color table flag.
    boolean gctFlag;

    /**
     * Size of Global Color Table.
     * The value is already computed to be a regular number, this field doesn't store the exponent.
     */
    int gctSize;

    /** Background color index into the Global/Local color table. */
    int bgIndex;

    /**
     * Pixel aspect ratio.
     * Factor used to compute an approximation of the aspect ratio of the pixel in the original image.
     */
    int pixelAspect;

    @ColorInt
    int bgColor;

    int loopCount = NETSCAPE_LOOP_COUNT_DOES_NOT_EXIST;
}
