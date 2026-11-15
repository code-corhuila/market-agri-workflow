package co.edu.corhuila.marketagri.workflow.adapter.out.http;

/** What one call got: a response with its status, or nothing (network error or timeout). */
public sealed interface Answer {

    record Response(int status, String body) implements Answer {
    }

    record Unreachable(String reason) implements Answer {
    }
}
