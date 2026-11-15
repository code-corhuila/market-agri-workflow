package co.edu.corhuila.marketagri.workflow.adapter.in.http;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a route the contract card declares <b>Anonymous</b>. The token is then neither required
 * nor validated (api-contract §4). Every other route needs a valid token.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface PublicEndpoint {
}
