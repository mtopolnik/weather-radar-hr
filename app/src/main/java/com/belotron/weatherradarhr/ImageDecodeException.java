package com.belotron.weatherradarhr;

public class ImageDecodeException extends RuntimeException {
    public ImageDecodeException(String message) {
        super(message);
    }

    public ImageDecodeException(String message, Throwable cause) {
        super(message, cause);
    }

    public ImageDecodeException(Throwable cause) {
        super(cause);
    }
}
