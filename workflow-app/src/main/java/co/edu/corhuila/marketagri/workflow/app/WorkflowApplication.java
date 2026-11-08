package co.edu.corhuila.marketagri.workflow.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Composition root. Scans the app and the adapters; the core carries no Spring annotation,
 * so its types are instantiated explicitly in {@link WorkflowConfiguration}.
 */
@SpringBootApplication(scanBasePackages = {
        "co.edu.corhuila.marketagri.workflow.app",
        "co.edu.corhuila.marketagri.workflow.adapter"})
public class WorkflowApplication {

    public static void main(String[] args) {
        SpringApplication.run(WorkflowApplication.class, args);
    }
}
