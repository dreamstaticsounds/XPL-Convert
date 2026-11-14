package xplconvert;

/** A preset could not be read or converted. Carries the message shown to the user. */
public class ConversionException extends RuntimeException {
    public ConversionException(String message) {
        super(message);
    }

    public ConversionException(String message, Throwable cause) {
        super(message, cause);
    }
}
