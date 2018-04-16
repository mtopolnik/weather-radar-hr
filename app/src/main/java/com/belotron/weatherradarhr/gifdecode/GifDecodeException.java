package com.belotron.weatherradarhr.gifdecode;

public class GifDecodeException extends RuntimeException {
    public GifDecodeException(String message) {
        super(message);
    }

    public GifDecodeException(String message, Throwable cause) {
        super(message, cause);
    }

    public GifDecodeException(Throwable cause) {
        super(cause);
    }
}
