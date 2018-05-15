package com.belotron.weatherradarhr.gifdecode;

public class GifDecodeException extends RuntimeException {
    GifDecodeException(String message) {
        super(message);
    }

    GifDecodeException(String message, Throwable cause) {
        super(message, cause);
    }

    GifDecodeException(Throwable cause) {
        super(cause);
    }
}
