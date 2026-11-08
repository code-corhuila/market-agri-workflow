package co.edu.corhuila.marketagri.workflow.app;

import co.edu.corhuila.marketagri.workflow.adapter.in.http.AuthInterceptor;
import co.edu.corhuila.marketagri.workflow.adapter.in.http.Rs256Verifier;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Every route goes through the token rules of {@link AuthInterceptor}. */
@Configuration
public class WorkflowWebConfiguration implements WebMvcConfigurer {

    private final Rs256Verifier verifier;

    public WorkflowWebConfiguration(Rs256Verifier verifier) {
        this.verifier = verifier;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // /error is the container's own dispatch after a failure: it must never ask for a token.
        registry.addInterceptor(new AuthInterceptor(verifier)).excludePathPatterns("/error");
    }
}
