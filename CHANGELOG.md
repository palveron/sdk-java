# Changelog

All notable changes to the Palveron Java SDK will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/).

## [1.1.0] — 2026-05-19

### Changed
- `verify()` now treats the gateway's Sprint-87 HTTP semantics as governance
  decisions rather than exceptions:
  - `200 OK` → `decision: "PASSED" | "MODIFIED" | "FLAGGED" | "POLICY_CHANGE"`
  - `202 Accepted` → `decision: "PENDING_APPROVAL"`
  - `403 Forbidden` → `decision: "BLOCKED"`
  - `429 Too Many Requests` → `decision: "RATE_LIMITED"` (synthesised) with
    `VerifyResponse.retryAfterMs` parsed from `Retry-After`
- Previous behaviour: 403/429 threw `PalveronException`. New behaviour:
  only transport / auth / 400 / 5xx / network failures throw. Every
  governance outcome flows through `VerifyResponse.decision`.
- `health()` and any future non-verify endpoint keep strict
  throw-on-non-2xx, so 429 on idempotent reads still retries.
- `VERSION` constant fixed: was `"0.5.0"` even after the 1.0 release.

### Added
- `VerifyResponse.retryAfterMs` and `VerifyResponse.httpStatus` fields.
- `VerifyResponse.isAllowed()` now matches both `"ALLOWED"` and `"PASSED"`.
- `VerifyResponse.isPendingApproval()` and `isRateLimited()` convenience
  methods alongside the existing `isBlocked()`.
- `parseRetryAfterMs()` helper — supports both RFC-7231 delta-seconds
  and HTTP-date forms.

### Migration
- If you previously caught `PalveronException` with `code == "RATE_LIMITED"`,
  switch to `result.isRateLimited()` plus `Thread.sleep(result.retryAfterMs())`.
- If you previously caught a `PalveronException` for a block, switch to
  `result.isBlocked()` — the SDK no longer throws on a successful 403.

## [1.0.0] — 2026-05-18

### Added
- Initial public release of `com.github.palveron:sdk-java` (tagged `v1.0.0`)
- `Palveron` synchronous client built on `java.net.http.HttpClient`
- `verify()`, `check()`, `verifyFile()` for the core governance call
- `listPolicies()` and `health()` endpoints
- Multi-modal attachments (image, audio, video, document, code)
- MCP / agentic `RequestContext` propagation
- Retry with exponential backoff + jitter (configurable, max 30 s)
- Circuit breaker (5 failures → open → 30 s cooldown → half-open)
- Typed errors: `PalveronAuthenticationException`, `PalveronRateLimitException`,
  `PalveronValidationException`, `PalveronTimeoutException`,
  `PalveronCircuitOpenException`
- Custom headers + on-premise base URL support
- Single dependency: stdlib only (JDK 17+)

### Security
- API keys transmitted via `Authorization` header only (never in URL or body)
- TLS verification enabled by default
- No secrets logged or exposed in error messages
