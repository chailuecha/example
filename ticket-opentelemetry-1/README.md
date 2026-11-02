# ticket-opentelemetry-1

This standalone project uses only public dependencies and the unmodified
OpenTelemetry Java agent. No agent extensions, private repositories, collector,
Docker, or application-side baggage copying are required.

## Requirements

- JDK 17 available to Gradle's toolchain discovery.
- Network access to Maven Central and the Gradle distribution on first run.

## Automated reproduction

```sh
./gradlew test --console=plain
```

This downloads upstream agent **2.31.1**, starts a JDK HTTP server in a separate
JVM with that agent, sends `baggage: sessionId=session-123`, and decodes the
exported OTLP HTTP/protobuf log record using a local test receiver.

On an affected agent the test is **expected to fail** at the final assertion:

```text
expected: <session-123> but was: <null>
```

Before that assertion, the test verifies that HTTP extraction succeeded,
Logback console MDC includes `baggage.sessionId=session-123`, and explicit
`MDC.put("requestId", "request-456")` is captured in OTLP attributes.
The failure is therefore the missing baggage-derived attribute, rather than
an application startup, context extraction, or exporter connectivity failure.

Evidence is saved under `build/verification/2.31.1/`:

- `evidence.txt`: HTTP response, Logback line, and exported attribute map.
- `exported-log.txt`: decoded OTLP log record.
- `application.log`: complete child-process output.

To test another agent version:

```sh
./gradlew test --console=plain -PotelAgentVersion=2.33.1
```

Outputs and test reports are stored separately for each agent version.

## Manual reproduction

Terminal 1:

```sh
./gradlew run --console=plain
```

Wait for `LISTENING 8080`. Gradle remains running while the server is up.

Terminal 2:

```sh
curl -H 'baggage: sessionId=session-123' http://127.0.0.1:8080/hello
```

The response is `sessionId=session-123`. Compare the `LOGBACK handling-request`
line with the OpenTelemetry console-exporter record: Logback's MDC contains the
baggage entry but the exported record's attributes do not. Stop with Ctrl+C.

Both reproduction paths enable these settings:

```properties
otel.propagators=tracecontext,baggage
otel.instrumentation.logback-mdc.add-baggage=true
otel.instrumentation.logback-appender.experimental.capture-mdc-attributes=*
```

The manual path uses the console exporter for direct inspection. The automated
test uses OTLP HTTP/protobuf to verify what is actually exported.

## Package the reproducer for a ticket

```sh
./gradlew reproducerZip --console=plain
```

Attach `build/attachments/ticket-opentelemetry-1.zip` to the ticket. The archive
contains source and the Gradle wrapper, without downloaded dependencies, build
outputs, or machine-specific logs. A suggested issue description is in
`issue-draft.txt`. The reproduced and verified agent version is 2.31.1; the
additional version command above is available for maintainers to compare versions.
