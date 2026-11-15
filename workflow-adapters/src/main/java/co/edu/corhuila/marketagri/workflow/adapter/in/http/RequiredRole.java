package co.edu.corhuila.marketagri.workflow.adapter.in.http;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The roles the contract card of a route lists under <b>Auth</b> (api-contract §4). A valid token
 * with another role is {@code 403 FORBIDDEN}; {@link AuthInterceptor} checks it.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface RequiredRole {

    String[] value();
}
