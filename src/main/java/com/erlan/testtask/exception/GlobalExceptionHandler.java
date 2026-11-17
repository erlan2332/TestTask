package com.erlan.testtask.exception;

import com.erlan.testtask.dto.response.ApiErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(TaskNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleTaskNotFound(TaskNotFoundException ex, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(error(HttpStatus.NOT_FOUND, ex.getMessage(), request.getRequestURI()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnexpected(Exception ex, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(error(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error", request.getRequestURI()));
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex, Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        String path = ((ServletWebRequest) request).getRequest().getRequestURI();
        return new ResponseEntity<>(error(statusCode, message(statusCode), path), headers, statusCode);
    }

    private String message(HttpStatusCode status) {
        return switch (status.value()) {
            case 400 -> "Invalid request";
            case 404 -> "Resource not found";
            case 405 -> "HTTP method not supported";
            case 415 -> "Unsupported media type";
            default -> status.is5xxServerError() ? "Internal server error" : "Request could not be processed";
        };
    }

    private ApiErrorResponse error(HttpStatusCode status, String message, String path) {
        HttpStatus httpStatus = HttpStatus.resolve(status.value());
        String reason = httpStatus != null ? httpStatus.getReasonPhrase() : "HTTP Error";
        return new ApiErrorResponse(Instant.now(), status.value(), reason, message, path);
    }
}
