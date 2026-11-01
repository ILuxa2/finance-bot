package financeos.vk;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** Local-only VK callback receiver. An optional inbox persists events before ACK. */
public final class LocalVkWebhookServer implements AutoCloseable {
    private final HttpServer server;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final VkEventParser parser;
    private final String confirmationCode;
    private final Consumer<VkEvent> process;
    private final DurableEventInbox inbox;

    public LocalVkWebhookServer(int port, VkEventParser parser, String confirmationCode,
                                Consumer<VkEvent> process) throws IOException {
        this(port, parser, confirmationCode, process, null);
    }

    public LocalVkWebhookServer(int port, VkEventParser parser, String confirmationCode,
                                Consumer<VkEvent> process, Path inboxDirectory) throws IOException {
        this.parser = parser;
        this.confirmationCode = confirmationCode;
        this.process = process;
        this.inbox = inboxDirectory == null ? null : new DurableEventInbox(inboxDirectory);
        this.server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
        this.server.createContext("/vk", this::handle);
    }

    public void start() {
        server.start();
        if (inbox != null) worker.submit(this::drainInbox);
    }
    public int port() { return server.getAddress().getPort(); }

    private void handle(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestMethod().equals("POST")) {
            reply(exchange, 405, "method not allowed");
            return;
        }
        byte[] body = exchange.getRequestBody().readNBytes(65_537);
        if (body.length > 65_536) {
            reply(exchange, 413, "event too large");
            return;
        }
        VkEvent event;
        try {
            event = parser.parse(body);
        } catch (SecurityException invalidSecret) {
            reply(exchange, 403, "forbidden");
            return;
        } catch (IOException invalidBody) {
            reply(exchange, 400, "bad request");
            return;
        }
        if (event.type().equals("confirmation")) {
            reply(exchange, 200, confirmationCode);
        } else {
            if (inbox != null && (event.type().equals("message_new")
                    || event.type().equals("message_event"))) {
                try {
                    inbox.enqueue(event);
                } catch (IOException storageFailure) {
                    reply(exchange, 503, "event storage unavailable");
                    return;
                }
            }
            reply(exchange, 200, "ok");
            if (event.type().equals("message_new") || event.type().equals("message_event")) {
                if (inbox == null) worker.submit(() -> process.accept(event));
                else worker.submit(this::drainInbox);
            }
        }
    }

    private void drainInbox() {
        try {
            inbox.drain(process);
        } catch (IOException | RuntimeException failure) {
            System.err.println("VK event remains queued after a processing failure ("
                    + failure.getClass().getSimpleName() + ").");
        }
    }

    private void reply(HttpExchange exchange, int status, String message) throws IOException {
        byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) { output.write(bytes); }
    }

    @Override public void close() {
        server.stop(0);
        worker.shutdownNow();
    }
}
