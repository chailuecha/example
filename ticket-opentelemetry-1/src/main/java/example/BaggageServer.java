package example;

import com.sun.net.httpserver.HttpServer;
import io.opentelemetry.api.baggage.Baggage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class BaggageServer {
    private static final Logger logger = LoggerFactory.getLogger(BaggageServer.class);

    public static void main(String[] args) throws Exception {
        HttpServer server = HttpServer.create(
                new InetSocketAddress("127.0.0.1", Integer.getInteger("server.port", 8080)), 0);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        server.setExecutor(executor);
        server.createContext("/hello", exchange -> {
            MDC.put("requestId", "request-456");
            try {
                // Only the upstream agent extracts the HTTP baggage header and manages context.
                logger.info("handling-request");
                byte[] response = ("sessionId=" + Baggage.current().getEntryValue("sessionId") + "\n")
                        .getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            } finally {
                MDC.remove("requestId");
                exchange.close();
            }
        });
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.stop(0);
            executor.shutdown();
        }));
        server.start();
        System.out.println("LISTENING " + server.getAddress().getPort());
    }
}
