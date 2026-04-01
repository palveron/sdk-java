# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.4.0] - 2026-04-01

### Added
- Initial release
- `verify()` — Core governance verification with full response typing
- `check()` — Quick text-only convenience method
- `verifyFile()` / `verify_file()` — File attachment helper with auto MIME detection
- `health()` — Gateway health check
- `diagnostics()` — SDK and connection diagnostics
- Multi-modal attachments (image, audio, video, document, code)
- MCP/Agentic context support (RequestContext)
- Retry with exponential backoff + jitter (configurable, max 30s)
- Circuit breaker (5 failures → open → 30s cooldown → half-open)
- Typed errors: Authentication, RateLimit, Validation, Timeout, CircuitOpen
- Request ID tracking via X-Request-ID header
- On-premise / custom endpoint configuration
- Custom headers support (for proxies, additional auth)

### Security
- API keys transmitted via Authorization header only (never in URL or body)
- TLS verification enabled by default
- No secrets logged or exposed in error messages
