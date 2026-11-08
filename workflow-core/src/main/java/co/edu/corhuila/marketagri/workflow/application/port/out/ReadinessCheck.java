package co.edu.corhuila.marketagri.workflow.application.port.out;

/**
 * A dependency the service needs to answer requests (E-61). The HTTP adapter reports each one by
 * {@link #name()}; the outbound adapters implement it. Plain Java: no framework here.
 */
public interface ReadinessCheck {

    /** The key in the readiness body, as the contract names it ({@code database}). */
    String name();

    /** Whether the dependency answers now. Never throws: a failure is simply {@code false}. */
    boolean isUp();
}
