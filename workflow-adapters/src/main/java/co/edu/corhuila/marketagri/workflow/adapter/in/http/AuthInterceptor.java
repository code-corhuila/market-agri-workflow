package co.edu.corhuila.marketagri.workflow.adapter.in.http;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Arrays;
import org.springframework.http.HttpHeaders;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Applies the token rules to every route that has a handler: a route marked
 * {@link PublicEndpoint} ignores tokens; any other needs a valid one (401). A service token is
 * accepted only on {@code /internal/**} and a user token only outside it (403, D-C27), and a
 * route marked {@link RequiredRole} only takes those roles (403). The caller is left in the
 * request under {@link Caller#ATTRIBUTE}.
 *
 * <p>An interceptor, not a servlet filter: it runs after the route is resolved, so an unknown
 * route is still {@code 404} and a wrong method {@code 405}, and before the body is read, so the
 * token is checked before validation (Anexo C 5.3.12).
 */
public final class AuthInterceptor implements HandlerInterceptor {

    private static final String BEARER = "Bearer ";
    private static final String INTERNAL_PREFIX = "/internal/";

    private final Rs256Verifier verifier;

    public AuthInterceptor(Rs256Verifier verifier) {
        this.verifier = verifier;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod route) || isPublic(route)) {
            return true;
        }
        Caller caller = verify(request.getHeader(HttpHeaders.AUTHORIZATION));
        boolean internal = request.getRequestURI().startsWith(INTERNAL_PREFIX);
        if (internal != caller.isService()) {
            throw new ForbiddenException(internal ? "user token on an internal route" : "service token on a public route");
        }
        RequiredRole required = route.getMethodAnnotation(RequiredRole.class);
        if (required != null && !Arrays.asList(required.value()).contains(caller.role())) {
            throw new ForbiddenException("role " + caller.role() + " may not use this route");
        }
        request.setAttribute(Caller.ATTRIBUTE, caller);
        return true;
    }

    private Caller verify(String authorization) {
        if (authorization == null || !authorization.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
            throw new UnauthorizedException("no bearer token");
        }
        try {
            return verifier.verify(authorization.substring(BEARER.length()).trim());
        } catch (InvalidTokenException invalid) {
            throw new UnauthorizedException(invalid.getMessage());
        }
    }

    private static boolean isPublic(HandlerMethod route) {
        return route.hasMethodAnnotation(PublicEndpoint.class)
                || route.getBeanType().isAnnotationPresent(PublicEndpoint.class);
    }
}
