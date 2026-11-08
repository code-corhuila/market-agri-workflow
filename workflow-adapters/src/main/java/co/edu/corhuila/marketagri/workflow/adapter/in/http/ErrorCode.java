package co.edu.corhuila.marketagri.workflow.adapter.in.http;

import org.springframework.http.HttpStatus;

/** Codes shared by every service (api-contract §2.1), with their status and Spanish message. */
public enum ErrorCode {
    VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "La solicitud tiene datos inválidos."),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "Debe iniciar sesión para continuar."),
    FORBIDDEN(HttpStatus.FORBIDDEN, "No tiene permisos para realizar esta acción."),
    NOT_FOUND(HttpStatus.NOT_FOUND, "El recurso solicitado no existe."),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "Método no permitido."),
    SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "El servicio no está disponible en este momento."),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Ocurrió un error inesperado.");

    private final HttpStatus status;
    private final String message;

    ErrorCode(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }

    public HttpStatus status() {
        return status;
    }

    public String message() {
        return message;
    }

    /**
     * Status that Spring raised by itself → contract code. Any other 4xx (415, 406, a missing
     * parameter…) is a malformed request, so it becomes 400 {@code VALIDATION_ERROR}; any other
     * 5xx becomes 500 {@code INTERNAL_ERROR}.
     */
    static ErrorCode fromStatus(int status) {
        return switch (status) {
            case 401 -> UNAUTHORIZED;
            case 403 -> FORBIDDEN;
            case 404 -> NOT_FOUND;
            case 405 -> METHOD_NOT_ALLOWED;
            case 503 -> SERVICE_UNAVAILABLE;
            default -> status >= 500 ? INTERNAL_ERROR : VALIDATION_ERROR;
        };
    }
}
