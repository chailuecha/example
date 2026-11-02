package example;

import com.sun.net.httpserver.HttpServer;
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest;
import io.opentelemetry.proto.logs.v1.LogRecord;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class BaggageExportTest {
    @Test
    void httpBaggageShouldBeIncludedInExportedLogAttributes() throws Exception {
        Path output = Path.of(System.getProperty("reproducer.outputDir"));
        Files.createDirectories(output);
        Path applicationOutput = output.resolve("application.log");
        List<LogRecord> records = new CopyOnWriteArrayList<>();
        List<Exception> receiverErrors = new CopyOnWriteArrayList<>();
        HttpServer receiver = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        receiver.createContext("/v1/logs", exchange -> {
            try {
                ExportLogsServiceRequest request = ExportLogsServiceRequest.parseFrom(exchange.getRequestBody());
                request.getResourceLogsList().forEach(resource -> resource.getScopeLogsList()
                        .forEach(scope -> records.addAll(scope.getLogRecordsList())));
                exchange.sendResponseHeaders(200, -1);
            } catch (Exception error) {
                receiverErrors.add(error);
                exchange.sendResponseHeaders(400, -1);
            } finally {
                exchange.close();
            }
        });
        receiver.start();
        Process application = null;
        try {
            application = new ProcessBuilder(
                    System.getProperty("reproducer.java"),
                    "-javaagent:" + System.getProperty("reproducer.agent"),
                    "-Dserver.port=0",
                    "-Dotel.service.name=ticket-opentelemetry-1",
                    "-Dotel.logs.exporter=otlp",
                    "-Dotel.exporter.otlp.endpoint=http://127.0.0.1:" + receiver.getAddress().getPort(),
                    "-Dotel.exporter.otlp.protocol=http/protobuf",
                    "-Dotel.exporter.otlp.compression=none",
                    "-Dotel.metrics.exporter=none",
                    "-Dotel.traces.exporter=none",
                    "-Dotel.propagators=tracecontext,baggage",
                    "-Dotel.blrp.schedule.delay=100",
                    "-Dotel.instrumentation.logback-mdc.add-baggage=true",
                    "-Dotel.instrumentation.logback-appender.experimental.capture-mdc-attributes=*",
                    "-cp", System.getProperty("reproducer.classpath"), BaggageServer.class.getName())
                    .redirectErrorStream(true).redirectOutput(applicationOutput.toFile()).start();

            URI endpoint = awaitEndpoint(application, applicationOutput);
            HttpResponse<String> response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(5))
                            .header("baggage", "sessionId=session-123").GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            assertEquals("sessionId=session-123\n", response.body(), "HTTP baggage extraction must work first");

            application.destroy();
            assertTrue(application.waitFor(15, TimeUnit.SECONDS), "Application did not stop");
            assertTrue(receiverErrors.isEmpty(), () -> "Receiver errors: " + receiverErrors);
            List<LogRecord> requestLogs = records.stream()
                    .filter(record -> record.getBody().getStringValue().equals("handling-request"))
                    .collect(Collectors.toList());
            assertEquals(1, requestLogs.size(), "Expected one exported request log");
            Map<String, String> attributes = requestLogs.get(0).getAttributesList().stream()
                    .collect(Collectors.toMap(attribute -> attribute.getKey(),
                            attribute -> attribute.getValue().getStringValue()));
            assertEquals("request-456", attributes.get("requestId"), "Explicit MDC capture must work first");
            String evidence = "HTTP response: " + response.body()
                    + "OTLP attributes: " + attributes + "\n";
            Files.writeString(output.resolve("evidence.txt"), evidence);
            Files.writeString(output.resolve("exported-log.txt"), requestLogs.get(0).toString());
            System.out.println(evidence);

            // This is the expected behavior assertion. It fails on affected upstream agents.
            assertEquals("session-123", attributes.get("baggage.sessionId"),
                    "Baggage is current in the HTTP handler, but absent from OTLP attributes");
        } finally {
            if (application != null && application.isAlive()) {
                application.destroyForcibly();
                application.waitFor(5, TimeUnit.SECONDS);
            }
            receiver.stop(0);
        }
    }

    private static URI awaitEndpoint(Process application, Path output) throws Exception {
        Pattern pattern = Pattern.compile("LISTENING (\\d+)");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            String console = Files.exists(output) ? Files.readString(output) : "";
            Matcher matcher = pattern.matcher(console);
            if (matcher.find()) {
                return URI.create("http://127.0.0.1:" + matcher.group(1) + "/hello");
            }
            assertTrue(application.isAlive(), () -> "Application exited: " + console);
            Thread.sleep(50);
        }
        throw new AssertionError("Application did not start; see " + output);
    }
}
