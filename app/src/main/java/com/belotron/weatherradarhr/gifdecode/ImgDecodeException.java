package com.belotron.weatherradarhr.gifdecode;

public class ImgDecodeException extends RuntimeException {
    ImgDecodeException(String message) {
        super(message);
    }

    ImgDecodeException(String message, Throwable cause) {
        super(message, cause);
    }

    ImgDecodeException(Throwable cause) {
        super(cause);
    }
}
