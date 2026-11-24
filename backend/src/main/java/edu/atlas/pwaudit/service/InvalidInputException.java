package edu.atlas.pwaudit.service;

/** User input that cannot be audited; mapped to HTTP 400. */
public class InvalidInputException extends RuntimeException {
    public InvalidInputException(String message) {
        super(message);
    }
}
