package com.belotron.weatherradarhr.gifdecode;

import androidx.annotation.ColorInt;
import androidx.annotation.IntDef;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.nio.ByteBuffer;

/**
 * Inner model class housing metadata for each frame.
 *
 * @see <a href="https://www.w3.org/Graphics/GIF/spec-gif89a.txt">GIF 89a Specification</a>
 */
public class GifFrame {
    /**
     * GIF Disposal Method meaning take no action.
     * <p><b>GIF89a</b>: <i>No disposal specified.
     * The decoder is not required to take any action.</i></p>
     */
    static final int DISPOSAL_UNSPECIFIED = 0;
    /**
     * GIF Disposal Method meaning leave canvas from previous frame.
     * <p><b>GIF89a</b>: <i>Do not dispose.
     * The graphic is to be left in place.</i></p>
     */
    static final int DISPOSAL_NONE = 1;
    /**
     * GIF Disposal Method meaning clear canvas to background color.
     * <p><b>GIF89a</b>: <i>Restore to background color.
     * The area used by the graphic must be restored to the background color.</i></p>
     */
    static final int DISPOSAL_BACKGROUND = 2;
    /**
     * GIF Disposal Method meaning clear canvas to frame before last.
     * <p><b>GIF89a</b>: <i>Restore to previous.
     * The decoder is required to restore the area overwritten by the graphic
     * with what was there prior to rendering the graphic.</i></p>
     */
    static final int DISPOSAL_PREVIOUS = 3;

    GifFrame(int index) {
        this.index = index;
    }

    /**
     * <p><b>GIF89a</b>:
     * <i>Indicates the way in which the graphic is to be treated after being displayed.</i></p>
     * Disposal methods 0-3 are defined, 4-7 are reserved for future use.
     *
     * @see #DISPOSAL_UNSPECIFIED
     * @see #DISPOSAL_NONE
     * @see #DISPOSAL_BACKGROUND
     * @see #DISPOSAL_PREVIOUS
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(value = {DISPOSAL_UNSPECIFIED, DISPOSAL_NONE, DISPOSAL_BACKGROUND, DISPOSAL_PREVIOUS})
    private @interface GifDisposalMethod {
    }

    /**
     * The index of the frame in the animated GIF.
     */
    int index;

    /** WeatherRadar-specific: OCR-ed timestamp of the image. */
    public long timestamp;

    int ix, iy, iw, ih;

    /** Control Flag. */
    boolean interlace;

    /** Control Flag. */
    boolean transparency;

    /** Disposal Method. */
    @GifDisposalMethod
    int dispose;

    /** Transparency Index. */
    int transIndex;

    /** Delay, in milliseconds, to next frame. */
    int delay;

    /** Buffer with the LZW stream data. */
    ByteBuffer frameData;

    /** Local Color Table. */
    @ColorInt
    int[] lct;
}
