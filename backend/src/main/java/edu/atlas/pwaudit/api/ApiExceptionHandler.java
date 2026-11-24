package edu.atlas.pwaudit.api;

import edu.atlas.pwaudit.service.InvalidInputException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

/** Turns input problems into {"error": ..., "message": ...} responses instead of stack traces. */
@RestControllerAdvice
public class ApiExceptionHandler {

    public record ApiError(String error, String message) {}

    @ExceptionHandler(InvalidInputException.class)
    public ResponseEntity<ApiError> invalidInput(InvalidInputException e) {
        return error(HttpStatus.BAD_REQUEST, "invalid_input", e.getMessage());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> unreadable(HttpMessageNotReadableException e) {
        return error(HttpStatus.BAD_REQUEST, "invalid_input", "Request body must be valid JSON, e.g. {\"password\": \"...\"} or {\"text\": \"...\"}.");
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> badParameter(Exception e) {
        return error(HttpStatus.BAD_REQUEST, "invalid_input", "A parameter has the wrong type; budgetSeconds must be a whole number.");
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiError> tooLarge(MaxUploadSizeExceededException e) {
        return error(HttpStatus.PAYLOAD_TOO_LARGE, "file_too_large", "File is larger than 1 MB.");
    }

    @ExceptionHandler({MissingServletRequestPartException.class, MultipartException.class})
    public ResponseEntity<ApiError> missingFile(Exception e) {
        return error(HttpStatus.BAD_REQUEST, "invalid_input", "Upload the file in the expected form field ('file' for a batch audit, 'users' for a simulation).");
    }

    private static ResponseEntity<ApiError> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(new ApiError(code, message));
    }
}
