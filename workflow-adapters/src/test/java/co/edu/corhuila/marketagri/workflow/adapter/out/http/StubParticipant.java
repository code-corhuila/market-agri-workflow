package co.edu.corhuila.marketagri.workflow.adapter.out.http;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

/** A participant on a free local port: answers the scripted responses in order and records each request. */
final class StubParticipant implements AutoCloseable {

    record Received(String method, String path, String authorization, String correlationId, String idempotencyKey,
                    String contentType, String body) {
    }

    record Scripted(int status, String body, long delayMillis) {
    }

    final List<Received> received = new CopyOnWriteArrayList<>();
    private final Deque<Scripted> script = new ArrayDeque<>();
    private final HttpServer server;

    StubParticipant() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            var headers = exchange.getRequestHeaders();
            received.add(new Received(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                    headers.getFirst("Authorization"), headers.getFirst("X-Correlation-Id"),
                    headers.getFirst("Idempotency-Key"), headers.getFirst("Content-Type"),
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            Scripted next;
            synchronized (script) {
                next = script.size() > 1 ? script.poll() : script.peek();
            }
            try {
                Thread.sleep(next.delayMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] body = next.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(next.status(), body.length == 0 ? -1 : body.length);
            if (body.length > 0) {
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
    }

    /** The last scripted response repeats for any further request. */
    StubParticipant answer(int status, String body) {
        return answerAfter(status, body, 0);
    }

    StubParticipant answerAfter(int status, String body, long delayMillis) {
        synchronized (script) {
            script.add(new Scripted(status, body, delayMillis));
        }
        return this;
    }

    URI url() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
