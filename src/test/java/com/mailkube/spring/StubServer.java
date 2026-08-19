package com.mailkube.spring;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A real HTTP server on a loopback port, standing in for the API.
 *
 * <p>This is the seam the contract's "exercise the real SDK over a stub transport" rule gets in
 * Java, and it is a server rather than a fake client for a concrete reason: the SDK's
 * {@code transport(...)} builder methods are <b>package-private</b> (their Javadoc says so: the
 * transport type lives in a package the module does not export). From
 * {@code com.mailkube.spring} they are unreachable, and widening the SDK's public
 * surface to provide a test seam would be a worse trade than binding to a port.
 *
 * <p>So "no network access" means loopback here. Nothing leaves the machine, and the real SDK does
 * its real config resolution, request building, JSON encoding and response parsing on every test —
 * which is the whole point: a hand-rolled fake would keep passing after the SDK renamed a field.
 *
 * <p>Port 0, so the OS picks a free one and parallel test runs cannot collide.
 */
final class StubServer implements AutoCloseable {

    private final HttpServer server;
    private final List<Request> received = new CopyOnWriteArrayList<>();
    private volatile int status = 200;
    private volatile String body = "{\"id\":\"email_123\"}";
    private volatile Map.Entry<String, String> extraHeader;

    /** One request as the server saw it. */
    record Request(String method, String path, Map<String, List<String>> headers, String body) {

        /**
         * Look a header up case-insensitively, because the JDK's server rewrites the casing.
         *
         * @param name the header name
         * @return the first value, or null
         */
        String header(String name) {
            for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
                if (entry.getKey().equalsIgnoreCase(name)) {
                    List<String> values = entry.getValue();
                    return values == null || values.isEmpty() ? null : values.getFirst();
                }
            }
            return null;
        }
    }

    StubServer() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException("could not start the stub server", e);
        }
        server.createContext("/", this::handle);
        server.start();
    }

    /**
     * The base URL to point a client at.
     *
     * @return the loopback base URL
     */
    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/";
    }

    /**
     * Answer the next requests with this status and body.
     *
     * @param newStatus the status to return
     * @param newBody the body to return
     */
    void reply(int newStatus, String newBody) {
        this.status = newStatus;
        this.body = newBody;
    }

    /**
     * Answer the next requests with this status, body and one extra response header.
     *
     * @param newStatus the status to return
     * @param newBody the body to return
     * @param name the header name
     * @param value the header value
     */
    void replyWithHeader(int newStatus, String newBody, String name, String value) {
        reply(newStatus, newBody);
        this.extraHeader = Map.entry(name, value);
    }

    /**
     * Every request received, in order.
     *
     * @return the requests
     */
    List<Request> received() {
        return received;
    }

    /**
     * The single request received.
     *
     * @return the first request
     */
    Request only() {
        return received.getFirst();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (InputStream in = exchange.getRequestBody()) {
            String requestBody = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            received.add(new Request(
                    exchange.getRequestMethod(),
                    exchange.getRequestURI().getPath(),
                    Map.copyOf(exchange.getRequestHeaders()),
                    requestBody));
        }
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        Map.Entry<String, String> extra = extraHeader;
        if (extra != null) {
            exchange.getResponseHeaders().add(extra.getKey(), extra.getValue());
        }
        exchange.sendResponseHeaders(status, payload.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(payload);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
