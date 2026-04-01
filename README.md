# VEXIS SDKs — AI Governance for Every Stack

Official SDKs for the VEXIS AI Governance Platform. Every LLM call. Every modality. Every trace — verified, audited, anchored.

## Quick Start

### TypeScript / JavaScript
```bash
npm install @vexis/sdk
```
```typescript
import { Vexis } from '@vexis/sdk';

const vexis = new Vexis({ apiKey: process.env.VEXIS_API_KEY! });

// Text verification
const result = await vexis.verify({ prompt: 'User input here' });
if (result.decision === 'BLOCKED') throw new Error(result.reason);

// With image attachment
const result = await vexis.verifyWithFile('Analyze this', './photo.jpg');

// Quick check
const { decision } = await vexis.check('Is this safe?');
```

### Python
```bash
pip install vexis-sdk
```
```python
from vexis import Vexis, Attachment

client = Vexis(api_key="gp_live_xxx")

# Text verification
result = client.verify("User input here")
if result.is_blocked:
    raise RuntimeError(result.reason)

# With file attachment
result = client.verify_file("Analyze this", "/path/to/doc.pdf")

# Async
from vexis import AsyncVexis
async with AsyncVexis(api_key="gp_live_xxx") as client:
    result = await client.verify("Check this")
```

### Go
```bash
go get github.com/vexis-security/vexis-sdk-go
```
```go
client := vexis.NewClient("gp_live_xxx",
    vexis.WithBaseURL("https://gateway.acme.corp:8080"),
)

result, err := client.Verify(ctx, &vexis.VerifyRequest{
    Prompt: "User input here",
})
if result.IsBlocked() {
    log.Fatal(result.Reason)
}
```

### Java / Kotlin
```java
var client = Vexis.builder("gp_live_xxx")
    .baseUrl("https://gateway.acme.corp:8080")
    .timeout(Duration.ofSeconds(10))
    .build();

var result = client.verify("User input here");
if (result.isBlocked()) {
    throw new SecurityException(result.reason());
}
```

## Features (All SDKs)

| Feature | TS | Python | Go | Java |
|---------|:--:|:------:|:--:|:----:|
| Text verification | ✅ | ✅ | ✅ | ✅ |
| Multi-modal (image/audio/doc/code) | ✅ | ✅ | ✅ | ✅ |
| File attachment helpers | ✅ | ✅ | ✅ | ✅ |
| MCP/Agentic context | ✅ | ✅ | ✅ | ✅ |
| Retry with exponential backoff | ✅ | ✅ | ✅ | ✅ |
| Circuit breaker | ✅ | ✅ | ✅ | ✅ |
| Typed errors | ✅ | ✅ | ✅ | ✅ |
| Request ID tracking | ✅ | ✅ | ✅ | ✅ |
| Health check | ✅ | ✅ | ✅ | ✅ |
| Async support | ✅ | ✅ | ✅ (goroutines) | ✅ (CompletableFuture) |
| Zero/minimal dependencies | ✅ (0) | httpx | ✅ (0) | ✅ (0) |
| On-prem / custom endpoint | ✅ | ✅ | ✅ | ✅ |
| Custom headers (proxy/auth) | ✅ | ✅ | ✅ | ✅ |
| Type-safe (strict mode) | ✅ | ✅ (py.typed) | ✅ | ✅ (records) |

## On-Premise Configuration

All SDKs support custom endpoints for on-premise deployments:

```typescript
const vexis = new Vexis({
  apiKey: 'gp_live_xxx',
  baseUrl: 'https://gateway.internal.acme.corp:8080',
  timeout: 10_000,
  maxRetries: 5,
});
```

## Error Handling

All SDKs provide typed, structured errors:

| Error | Code | Retryable | Meaning |
|-------|------|:---------:|---------|
| `VexisAuthenticationError` | `AUTHENTICATION_FAILED` | ❌ | Invalid or expired API key |
| `VexisRateLimitError` | `RATE_LIMITED` | ✅ | Request quota exceeded |
| `VexisValidationError` | `VALIDATION_ERROR` | ❌ | Malformed request |
| `VexisTimeoutError` | `TIMEOUT` | ✅ | Gateway didn't respond in time |
| `VexisCircuitOpenError` | `CIRCUIT_OPEN` | ❌ | Too many consecutive failures |

## Versioning

SDKs follow [SemVer](https://semver.org/). All SDKs are versioned together:
- `0.x.y` — Beta (current). Breaking changes possible between minor versions.
- `1.0.0` — Stable GA. Breaking changes only in major versions.

## License

Apache 2.0
