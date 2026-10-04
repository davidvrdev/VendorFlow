package com.vendorflow.shared.error;

import com.vendorflow.shared.web.RequestIdFilter;
import com.vendorflow.shared.web.SensitivePaths;
import jakarta.validation.ConstraintViolationException;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Single place that turns exceptions into RFC 9457 problem responses. Nothing from an exception message or stack
 * trace reaches a response body except our own NotFoundException message and validation messages.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<FieldViolation> errors = new ArrayList<>();
        for (FieldError fe : ex.getBindingResult().getFieldErrors()) {
            errors.add(new FieldViolation(fe.getField(), fe.getDefaultMessage()));
        }
        for (ObjectError oe : ex.getBindingResult().getGlobalErrors()) {
            errors.add(new FieldViolation(oe.getObjectName(), oe.getDefaultMessage()));
        }
        return validationResponse(ex, errors, headers, request);
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<FieldViolation> errors = new ArrayList<>();
        ex.getParameterValidationResults().forEach(result -> {
            String param = result.getMethodParameter().getParameterName();
            for (MessageSourceResolvable error : result.getResolvableErrors()) {
                String field = error instanceof FieldError fe ? fe.getField() : param;
                errors.add(new FieldViolation(field, error.getDefaultMessage()));
            }
        });
        return validationResponse(ex, errors, headers, request);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<Object> handleConstraintViolation(ConstraintViolationException ex, WebRequest request) {
        List<FieldViolation> errors = ex.getConstraintViolations().stream()
                .map(v -> new FieldViolation(leafName(v.getPropertyPath().toString()), v.getMessage()))
                .toList();
        return validationResponse(ex, errors, new HttpHeaders(), request);
    }

    @ExceptionHandler(ApiException.class)
    ResponseEntity<Object> handleApi(ApiException ex, WebRequest request) {
        ProblemDetail pd = Problems.of(ex.status(), ex.slug(), ex.title(), ex.getMessage());
        HttpHeaders headers = new HttpHeaders();
        if (ex.retryAfterSeconds() != null) {
            headers.set(HttpHeaders.RETRY_AFTER, Long.toString(ex.retryAfterSeconds()));
        }
        return handleExceptionInternal(ex, pd, headers, ex.status(), request);
    }

    @ExceptionHandler(RequestValidationException.class)
    ResponseEntity<Object> handleRequestValidation(RequestValidationException ex, WebRequest request) {
        return validationResponse(ex, ex.violations(), new HttpHeaders(), request);
    }

    @ExceptionHandler(NotFoundException.class)
    ResponseEntity<Object> handleNotFound(NotFoundException ex, WebRequest request) {
        ProblemDetail pd = Problems.of(HttpStatus.NOT_FOUND, "not-found", "Resource not found", ex.getMessage());
        return handleExceptionInternal(ex, pd, new HttpHeaders(), HttpStatus.NOT_FOUND, request);
    }

    // Without these two, the catch-all below would turn security exceptions thrown from controllers/services
    // (e.g. method security) into 500s instead of 401/403.
    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<Object> handleAccessDenied(AccessDeniedException ex, WebRequest request) {
        return handleExceptionInternal(ex, Problems.forbidden(), new HttpHeaders(), HttpStatus.FORBIDDEN, request);
    }

    @ExceptionHandler(AuthenticationException.class)
    ResponseEntity<Object> handleAuthentication(AuthenticationException ex, WebRequest request) {
        return handleExceptionInternal(ex, Problems.unauthorized(), new HttpHeaders(), HttpStatus.UNAUTHORIZED,
                request);
    }

    /** Container-level multipart limit (slightly above app.documents.max-size): same 413 as our own size check. */
    @Override
    protected ResponseEntity<Object> handleMaxUploadSizeExceededException(MaxUploadSizeExceededException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail pd = Problems.of(HttpStatus.PAYLOAD_TOO_LARGE, "file-too-large", "File too large",
                "The upload exceeds the maximum allowed size.");
        return handleExceptionInternal(ex, pd, headers, HttpStatus.PAYLOAD_TOO_LARGE, request);
    }

    /** Broken or unreadable multipart body (not a size problem): 400, never the catch-all 500. */
    @ExceptionHandler(MultipartException.class)
    ResponseEntity<Object> handleMultipart(MultipartException ex, WebRequest request) {
        ProblemDetail pd = Problems.of(HttpStatus.BAD_REQUEST, "invalid-upload", "Invalid upload",
                "The upload request could not be read.");
        return handleExceptionInternal(ex, pd, new HttpHeaders(), HttpStatus.BAD_REQUEST, request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> handleUnexpected(Exception ex, WebRequest request) {
        log.error("Unhandled exception", ex);
        ProblemDetail pd = Problems.of(HttpStatus.INTERNAL_SERVER_ERROR, "internal", "Internal server error",
                "An unexpected error occurred.");
        return handleExceptionInternal(ex, pd, new HttpHeaders(), HttpStatus.INTERNAL_SERVER_ERROR, request);
    }

    /** Framework-generated problems (405, 415, malformed JSON, ...) get the requestId too. */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
            HttpStatusCode statusCode, WebRequest request) {
        if (body instanceof ProblemDetail pd
                && (pd.getProperties() == null || !pd.getProperties().containsKey(RequestIdFilter.MDC_KEY))) {
            pd.setProperty(RequestIdFilter.MDC_KEY, MDC.get(RequestIdFilter.MDC_KEY));
        }
        if (body instanceof ProblemDetail pd && pd.getInstance() == null
                && request instanceof org.springframework.web.context.request.ServletWebRequest swr) {
            // Spring would fill "instance" with the request path; a secret path segment must not be echoed.
            String path = swr.getRequest().getRequestURI();
            if (!java.util.Objects.equals(path, SensitivePaths.mask(path))) {
                pd.setInstance(java.net.URI.create(SensitivePaths.mask(path).replace("{", "%7B").replace("}", "%7D")));
            }
        }
        return super.handleExceptionInternal(ex, body, headers, statusCode, request);
    }

    /** "createVendor.request.email" -> "request.email": drop the method name that method validation prepends. */
    private static String leafName(String path) {
        int dot = path.indexOf('.');
        return dot < 0 ? path : path.substring(dot + 1);
    }

    private ResponseEntity<Object> validationResponse(Exception ex, List<FieldViolation> errors, HttpHeaders headers,
            WebRequest request) {
        ProblemDetail pd = Problems.of(HttpStatus.BAD_REQUEST, "validation", "Validation failed",
                "One or more fields are invalid.");
        pd.setProperty("errors", errors);
        return handleExceptionInternal(ex, pd, headers, HttpStatus.BAD_REQUEST, request);
    }
}
