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
    public GifDecoder intoDecoder(@NonNull Allocator allocator) {
        return new GifDecoder(allocator, this, null);
    }

    @NonNull
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
