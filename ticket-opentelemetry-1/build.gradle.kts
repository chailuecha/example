plugins {
    application
}

repositories {
    mavenCentral()
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

val agentVersion = providers.gradleProperty("otelAgentVersion").getOrElse("2.31.1")
val javaagent by configurations.creating {
    isTransitive = false
}

dependencies {
    implementation("io.opentelemetry:opentelemetry-api:1.65.0")
    implementation("org.slf4j:slf4j-api:2.0.15")
    runtimeOnly("ch.qos.logback:logback-classic:1.5.7")
    javaagent("io.opentelemetry.javaagent:opentelemetry-javaagent:$agentVersion")
    testImplementation(platform("org.junit:junit-bom:5.11.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("io.opentelemetry.proto:opentelemetry-proto:1.3.2-alpha")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass.set("example.BaggageServer")
}

tasks.named<JavaExec>("run") {
    inputs.files(javaagent)
    systemProperty("server.port", providers.gradleProperty("serverPort").getOrElse("8080"))
    doFirst {
        jvmArgs("-javaagent:${javaagent.singleFile.absolutePath}",
            "-Dotel.service.name=ticket-opentelemetry-1",
            "-Dotel.logs.exporter=console",
            "-Dotel.metrics.exporter=none",
            "-Dotel.traces.exporter=none",
            "-Dotel.propagators=tracecontext,baggage",
            "-Dotel.instrumentation.logback-mdc.add-baggage=true",
            "-Dotel.instrumentation.logback-appender.experimental.capture-mdc-attributes=*")
    }
}

tasks.test {
    useJUnitPlatform()
    inputs.files(javaagent)
    val launcher = javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
    doFirst {
        systemProperty("reproducer.java", launcher.get().executablePath.asFile.absolutePath)
        systemProperty("reproducer.classpath", sourceSets.main.get().runtimeClasspath.asPath)
        systemProperty("reproducer.agent", javaagent.singleFile.absolutePath)
        systemProperty("reproducer.outputDir", layout.buildDirectory.dir("verification/$agentVersion").get().asFile)
    }
    reports.junitXml.outputLocation.set(layout.buildDirectory.dir("test-results/$agentVersion"))
    reports.html.outputLocation.set(layout.buildDirectory.dir("reports/tests/$agentVersion"))
    testLogging {
        events("passed", "failed")
        showStandardStreams = true
    }
}

tasks.register<Zip>("reproducerZip") {
    group = "distribution"
    description = "Packages the standalone source reproducer for an issue attachment."
    archiveFileName.set("ticket-opentelemetry-1.zip")
    destinationDirectory.set(layout.buildDirectory.dir("attachments"))
    into("ticket-opentelemetry-1") {
        from(projectDir) {
            include("README.md", ".gitignore", "settings.gradle.kts", "build.gradle.kts",
                "gradlew", "gradlew.bat", "gradle/wrapper/**", "src/**")
        }
    }
}
