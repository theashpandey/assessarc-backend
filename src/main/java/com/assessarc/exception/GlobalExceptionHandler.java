package com.assessarc.exception;

import com.assessarc.dto.Dto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Dto.ApiError> handleRuntime(RuntimeException ex) {
        String safeMessage = sanitize(ex.getMessage());
        log.error("Runtime error: {}", safeMessage);
        HttpStatus status = resolveStatus(safeMessage);
        return ResponseEntity.status(status).body(Dto.ApiError.builder()
                .status(status.value())
                .error(status.getReasonPhrase())
                .message(safeMessage)
                .timestamp(System.currentTimeMillis())
                .build());
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Dto.ApiError> handleMaxUpload(MaxUploadSizeExceededException ex) {
        return ResponseEntity.status(413).body(Dto.ApiError.builder()
                .status(413)
                .error("Payload Too Large")
                .message("File too large. Maximum resume size is 5MB.")
                .timestamp(System.currentTimeMillis())
                .build());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Dto.ApiError> handleGeneral(Exception ex) {
        log.error("Unexpected error: {}", sanitize(ex.getMessage()), ex);
        return ResponseEntity.status(500).body(Dto.ApiError.builder()
                .status(500)
                .error("Internal Server Error")
                .message("An unexpected error occurred. Please try again.")
                .timestamp(System.currentTimeMillis())
                .build());
    }

    private HttpStatus resolveStatus(String msg) {
        if (msg == null) return HttpStatus.INTERNAL_SERVER_ERROR;
        String lower = msg.toLowerCase();
        if (lower.contains("unauthorized") || lower.contains("does not belong")) return HttpStatus.FORBIDDEN;
        if (lower.contains("not found")) return HttpStatus.NOT_FOUND;
        if (lower.contains("insufficient") || lower.contains("please upload")
                || lower.contains("invalid") || lower.contains("empty")
                || lower.contains("duration") || lower.contains("too large")
                || lower.contains("required") || lower.contains("mismatch")
                || lower.contains("already processed") || lower.contains("already been used")
                || lower.contains("must be between") || lower.contains("unsupported")) {
            return HttpStatus.BAD_REQUEST;
        }
        return HttpStatus.INTERNAL_SERVER_ERROR;
    }

    private String sanitize(String message) {
        if (message == null || message.isBlank()) return "An unexpected error occurred. Please try again.";
        return message.replaceAll("(?i)([?&]key=)[^\\s&]+", "$1[REDACTED]")
                .replaceAll("AIza[0-9A-Za-z_-]{20,}", "[REDACTED_API_KEY]");
    }
}
