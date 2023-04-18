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

import android.graphics.Bitmap;
import androidx.annotation.NonNull;

/**
 * An interface that can be used to provide reused {@link android.graphics.Bitmap}s to avoid GCs
 * from constantly allocating {@link android.graphics.Bitmap}s for every frame.
 */
public interface Allocator {
    /**
     * Returns an {@link Bitmap} with exactly the given dimensions and config.
     *
     * @param width  The width in pixels of the desired {@link Bitmap}.
     * @param height The height in pixels of the desired {@link Bitmap}.
     * @param config The {@link Bitmap.Config} of the desired {@link
     *               Bitmap}.
     */
    @NonNull
    Bitmap obtain(int width, int height, @NonNull Bitmap.Config config);

    /**
     * Releases the given Bitmap back to the pool.
     */
    void release(@NonNull Bitmap bitmap);

    /**
     * Returns a byte array used for decoding and generating the frame bitmap.
     *
     * @param size the size of the byte array to obtain
     */
    @NonNull
    byte[] obtainByteArray(int size);

    /**
     * Releases the given byte array back to the pool.
     */
    void release(@NonNull byte[] bytes);

    /**
     * Returns an int array used for decoding/generating the frame bitmaps.
     */
    @NonNull
    int[] obtainIntArray(int size);

    /**
     * Release the given array back to the pool.
     */
    void release(@NonNull int[] array);

    void dispose();
}
