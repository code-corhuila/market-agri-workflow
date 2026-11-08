package co.edu.corhuila.marketagri.workflow.adapter.in.http;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * The single place where an exception becomes an HTTP response (Anexo C, api-contract §2).
 * Spring's own exceptions (404 route, 405, malformed JSON, type mismatch, missing header…)
 * arrive through {@link #handleExceptionInternal}; anything else, through
 * {@link #handleUnexpected}. Domain errors will be mapped here too, never in a controller.
 */
@RestControllerAdvice
public class ErrorHandler extends ResponseEntityExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(ErrorHandler.class);

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException exception, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        List<ApiError.FieldDetail> details = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> new ApiError.FieldDetail(error.getField(), error.getDefaultMessage()))
                .toList();
        return body(ErrorCode.VALIDATION_ERROR, details, headers, request);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception exception, Object body, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        ErrorCode code = ErrorCode.fromStatus(status.value());
        if (code == ErrorCode.INTERNAL_ERROR) {
            LOG.error("Unhandled framework error", exception);
        }
        return body(code, detailsOf(exception, code), headers, request);
    }

    /** Last resort: neutral message to the client, full detail only in the log (NFR-039). */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handleUnexpected(Exception exception, WebRequest request) {
        LOG.error("Unhandled exception", exception);
        return body(ErrorCode.INTERNAL_ERROR, List.of(), HttpHeaders.EMPTY, request);
    }

    private static List<ApiError.FieldDetail> detailsOf(Exception exception, ErrorCode code) {
        if (code != ErrorCode.VALIDATION_ERROR) {
            return List.of();
        }
        if (exception instanceof MissingRequestHeaderException missing) {
            return List.of(new ApiError.FieldDetail(missing.getHeaderName(), "Encabezado obligatorio."));
        }
        if (exception instanceof MissingServletRequestParameterException missing) {
            return List.of(new ApiError.FieldDetail(missing.getParameterName(), "Parámetro obligatorio."));
        }
        if (exception instanceof MethodArgumentTypeMismatchException mismatch) {
            return List.of(new ApiError.FieldDetail(mismatch.getName(), "Formato inválido."));
        }
        if (exception instanceof TypeMismatchException mismatch && mismatch.getPropertyName() != null) {
            return List.of(new ApiError.FieldDetail(mismatch.getPropertyName(), "Formato inválido."));
        }
        return List.of();
    }

    private static ResponseEntity<Object> body(
            ErrorCode code, List<ApiError.FieldDetail> details, HttpHeaders headers, WebRequest request) {
        ApiError error = new ApiError(code.name(), code.message(), details, traceId(request));
        // Keeps the headers Spring prepared, such as Allow on a 405.
        return ResponseEntity.status(code.status()).headers(headers).body(error);
    }

    private static String traceId(WebRequest request) {
        Object fromFilter = request.getAttribute(CorrelationFilter.HEADER, RequestAttributes.SCOPE_REQUEST);
        return fromFilter != null ? fromFilter.toString() : MDC.get(CorrelationFilter.MDC_KEY);
    }
}
