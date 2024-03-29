/*
 * Copyright (C) 2018-2023 Marko Topolnik
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.belotron.weatherradarhr.gifdecode;

import androidx.annotation.ColorInt;
import androidx.annotation.IntDef;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.belotron.weatherradarhr.Frame;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.nio.ByteBuffer;

/**
 * Inner model class housing metadata for each frame.
 *
 * @see <a href="https://www.w3.org/Graphics/GIF/spec-gif89a.txt">GIF 89a Specification</a>
 */
public class GifFrame implements Frame {
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

    @Override
    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
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
    final int index;

    /** WeatherRadar-specific: OCR-ed timestamp of the image. */
    private Long timestamp;

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
