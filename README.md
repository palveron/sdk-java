# palveron sdk-java

Official Java SDK for the **Palveron AI Governance Gateway** — policy enforcement, trace verification, and blockchain-anchored audit trails for every AI interaction.

[![JitPack](https://img.shields.io/jitpack/v/github/palveron/sdk-java.svg?style=flat-square&color=cb3837)](https://jitpack.io/#palveron/sdk-java)
[![License: MIT](https://img.shields.io/badge/License-MIT-green.svg?style=flat-square)](https://opensource.org/licenses/MIT)
[![Documentation](https://img.shields.io/badge/docs-palveron.com-5A67D8?style=flat-square)](https://docs.palveron.com/sdks)

---

Every AI interaction your JVM service makes — governed, audited, and optionally anchored to the blockchain. Stdlib-only client.

- **Zero runtime dependencies** — built on `java.net.http.HttpClient`, no third-party JARs
- **Multi-modal** — text, images, audio, documents, code
- **Enterprise-grade** — retry with exponential backoff, circuit breaker, typed exceptions
- **On-prem ready** — point to any Palveron gateway endpoint

## Installation

Add the JitPack repository to your build, then declare the SDK as a dependency.

### Gradle (Kotlin DSL)

```kotlin
repositories {
    maven { url = uri("https://jitpack.io") }
}

dependencies {
    implementation("com.github.palveron:sdk-java:v1.0.0")
}
```

### Maven

```xml
<repositories>
    <repository>
        <id>jitpack.io</id>
        <url>https://jitpack.io</url>
    </repository>
</repositories>

<dependency>
    <groupId>com.github.palveron</groupId>
    <artifactId>sdk-java</artifactId>
    <version>v1.0.0</version>
</dependency>
```

## Quick Start

```java
import com.palveron.sdk.Palveron;

var client = Palveron.builder(System.getenv("PALVERON_API_KEY"))
    .baseUrl("https://gateway.palveron.com")
    .build();

var result = client.verify("Transfer $50,000 to account DE89370400440532013000");

if (result.isBlocked()) {
    throw new SecurityException("Blocked by policy: " + result.reason());
}

// Always use result.output() instead of the raw prompt so downstream
// LLMs never see PII / secrets the gateway redacted.
System.out.println(result.output());
System.out.println(result.traceId());
```

## Features

- **Policy enforcement** — every prompt routed through your active guardrails before it reaches an LLM
- **Trace verification** — every decision logged with an integrity hash for tamper detection
- **Multi-modal attachments** — file helpers with auto MIME detection
- **Agentic / MCP context** — pass `RequestContext` so the audit trail captures the tool chain
- **Blockchain attestation** — high-severity traces anchored to Flare for cryptographic audit trails

## Requirements

- **JDK 17 or newer**
- No third-party runtime dependencies

## Links

- **Documentation** — [docs.palveron.com/sdks](https://docs.palveron.com/sdks)
- **Dashboard** — [palveron.com](https://palveron.com)
- **Support** — [hello@palveron.com](mailto:hello@palveron.com)
- **GitHub** — [palveron/sdk-java](https://github.com/palveron/sdk-java)
- **JitPack** — [jitpack.io/#palveron/sdk-java](https://jitpack.io/#palveron/sdk-java)
- **Changelog** — [CHANGELOG.md](https://github.com/palveron/sdk-java/blob/main/CHANGELOG.md)

## License

[MIT](./LICENSE) — Copyright © 2026 Palveron.
