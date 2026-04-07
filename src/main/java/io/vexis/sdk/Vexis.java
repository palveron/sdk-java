package io.vexis.sdk;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Official Java SDK for the VEXIS AI Governance Platform.
 *
 * <pre>{@code
 * var client = Vexis.builder("gp_live_xxx")
 *     .baseUrl("https://gateway.acme.corp:8080")
 *     .build();
 *
 * var result = client.verify("Check this prompt");
 * if (result.isBlocked()) {
 *     throw new SecurityException(result.reason());
 * }
 * }</pre>
 *
 * <p>Thread-safe. Uses Java 17+ HttpClient internally.</p>
 */
public final class Vexis implements AutoCloseable {

    public static final String VERSION = "0.5.0";
    private static final String DEFAULT_BASE_URL = "https://gateway.vexis.io";

    private final String apiKey;
    private final String baseUrl;
    private final HttpClient http;
    private final Duration timeout;
    private final int maxRetries;
    private final Map<String, String> headers;
    private final CircuitBreaker circuit;

    private Vexis(Builder b) {
        this.apiKey = Objects.requireNonNull(b.apiKey, "apiKey is required");
        this.baseUrl = b.baseUrl != null ? b.baseUrl.replaceAll("/+$", "") : DEFAULT_BASE_URL;
        this.timeout = b.timeout != null ? b.timeout : Duration.ofSeconds(30);
        this.maxRetries = b.maxRetries;
        this.headers = b.headers != null ? Map.copyOf(b.headers) : Map.of();
        this.circuit = new CircuitBreaker(b.circuitThreshold, b.circuitCooldown);
        this.http = HttpClient.newBuilder()
                .connectTimeout(this.timeout)
                .build();
    }

    public static Builder builder(String apiKey) { return new Builder(apiKey); }

    // ── Core API ────────────────────────────────────────────

    /** Send a governance verification request. */
    public VerifyResponse verify(VerifyRequest request) throws VexisException {
        long start = System.nanoTime();
        String body = request.toJson();
        Map<String, Object> raw = doRequest("POST", "/api/v1/verify", body);
        double latencyMs = (System.nanoTime() - start) / 1_000_000.0;
        return VerifyResponse.fromMap(raw, latencyMs);
    }

    /** Quick text-only verification. */
    public VerifyResponse verify(String prompt) throws VexisException {
        return verify(VerifyRequest.of(prompt));
    }

    /** Verify with a file attachment. */
    public VerifyResponse verifyFile(String prompt, Path filePath) throws VexisException, IOException {
        byte[] data = Files.readAllBytes(filePath);
        String mime = Files.probeContentType(filePath);
        if (mime == null) mime = "application/octet-stream";
        Attachment att = new Attachment(mime, Base64.getEncoder().encodeToString(data), filePath.getFileName().toString(), null);
        return verify(VerifyRequest.builder(prompt).attachment(att).build());
    }

    /** Check gateway health. */
    public Map<String, Object> health() throws VexisException {
        return doRequest("GET", "/health", null);
    }

    /** SDK diagnostics. */
    public Map<String, Object> diagnostics() {
        return Map.of("sdkVersion", VERSION, "baseUrl", baseUrl, "timeout", timeout.toString(), "circuitState", circuit.state());
    }

    @Override
    public void close() { /* HttpClient is managed by JVM */ }

    // ── Internal HTTP ───────────────────────────────────────

    @SuppressWarnings("unchecked")
    private Map<String, Object> doRequest(String method, String path, String jsonBody) throws VexisException {
        if (!circuit.canRequest()) {
            throw new VexisException("Circuit breaker open", "CIRCUIT_OPEN", 503, null, false);
        }

        VexisException lastError = null;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            if (attempt > 0) {
                try { Thread.sleep(backoffMs(attempt)); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new VexisException("Interrupted", "INTERRUPTED", 0, null, false); }
            }

            String requestId = makeRequestId();
            try {
                var reqBuilder = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + path))
                        .timeout(timeout)
                        .header("Authorization", "Bearer " + apiKey)
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json")
                        .header("User-Agent", "vexis-sdk-java/" + VERSION)
                        .header("X-Request-ID", requestId);

                headers.forEach(reqBuilder::header);

                if ("POST".equals(method) && jsonBody != null) {
                    reqBuilder.POST(HttpRequest.BodyPublishers.ofString(jsonBody));
                } else {
                    reqBuilder.GET();
                }

                HttpResponse<String> resp = http.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString());
                String rid = resp.headers().firstValue("x-request-id").orElse(requestId);

                if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                    circuit.onSuccess();
                    return SimpleJson.parse(resp.body());
                }

                switch (resp.statusCode()) {
                    case 401 -> throw new VexisException("Invalid API key", "AUTH_FAILED", 401, rid, false);
                    case 429 -> throw new VexisException("Rate limit exceeded", "RATE_LIMITED", 429, rid, true);
                    case 400 -> {
                        Map<String, Object> errBody = SimpleJson.parse(resp.body());
                        throw new VexisException(String.valueOf(errBody.getOrDefault("error", "Bad request")), "VALIDATION", 400, rid, false);
                    }
                }

                if (resp.statusCode() >= 500) {
                    circuit.onFailure();
                    lastError = new VexisException("Server error " + resp.statusCode(), "SERVER_ERROR", resp.statusCode(), rid, true);
                    continue;
                }

                throw new VexisException("HTTP " + resp.statusCode(), "CLIENT_ERROR", resp.statusCode(), rid, false);

            } catch (VexisException e) {
                if (!e.isRetryable()) throw e;
                lastError = e;
            } catch (IOException | InterruptedException e) {
                circuit.onFailure();
                lastError = new VexisException("Network error: " + e.getMessage(), "NETWORK_ERROR", 0, requestId, true);
            }
        }
        throw lastError != null ? lastError : new VexisException("Max retries exceeded", "MAX_RETRIES", 0, null, false);
    }

    private static long backoffMs(int attempt) {
        long base = (long) (500 * Math.pow(2, attempt - 1));
        long jitter = ThreadLocalRandom.current().nextLong(base / 5);
        return Math.min(base + jitter, 30_000);
    }

    private static String makeRequestId() {
        return "vx_" + Long.toHexString(System.currentTimeMillis()) + "_" + Integer.toHexString(ThreadLocalRandom.current().nextInt());
    }

    // ── Builder ─────────────────────────────────────────────

    public static final class Builder {
        private final String apiKey;
        private String baseUrl;
        private Duration timeout;
        private int maxRetries = 3;
        private Map<String, String> headers;
        private int circuitThreshold = 5;
        private Duration circuitCooldown = Duration.ofSeconds(30);

        private Builder(String apiKey) { this.apiKey = apiKey; }
        public Builder baseUrl(String url) { this.baseUrl = url; return this; }
        public Builder timeout(Duration d) { this.timeout = d; return this; }
        public Builder maxRetries(int n) { this.maxRetries = n; return this; }
        public Builder headers(Map<String, String> h) { this.headers = h; return this; }
        public Builder circuitBreaker(int threshold, Duration cooldown) { this.circuitThreshold = threshold; this.circuitCooldown = cooldown; return this; }
        public Vexis build() { return new Vexis(this); }
    }

    // ── Data Types ──────────────────────────────────────────

    public record Attachment(String contentType, String data, String filename, Map<String, Object> metadata) {
        public static Attachment fromFile(Path path) throws IOException {
            byte[] bytes = Files.readAllBytes(path);
            String mime = Files.probeContentType(path);
            return new Attachment(mime != null ? mime : "application/octet-stream", Base64.getEncoder().encodeToString(bytes), path.getFileName().toString(), Map.of("size_bytes", bytes.length));
        }
    }

    public record VerifyRequest(String prompt, String extractedText, Map<String, Object> metadata, List<Attachment> attachments) {
        public static VerifyRequest of(String prompt) { return new VerifyRequest(prompt, null, null, null); }
        public static ReqBuilder builder(String prompt) { return new ReqBuilder(prompt); }
        String toJson() { return SimpleJson.buildVerifyBody(this); }

        public static final class ReqBuilder {
            private final String prompt;
            private String extractedText;
            private Map<String, Object> metadata;
            private final List<Attachment> attachments = new ArrayList<>();
            ReqBuilder(String prompt) { this.prompt = prompt; }
            public ReqBuilder extractedText(String t) { this.extractedText = t; return this; }
            public ReqBuilder metadata(Map<String, Object> m) { this.metadata = m; return this; }
            public ReqBuilder attachment(Attachment a) { this.attachments.add(a); return this; }
            public VerifyRequest build() { return new VerifyRequest(prompt, extractedText, metadata, attachments.isEmpty() ? null : List.copyOf(attachments)); }
        }
    }

    public record Finding(String risk, String category, String description, double confidence) {}

    public record VerifyResponse(String decision, String output, String reason, String traceId, String integrityHash, boolean shouldAnchor, String flareStatus, String flareTxHash, String contentType, List<Finding> findings, double latencyMs) {
        public boolean isAllowed() { return "ALLOWED".equals(decision); }
        public boolean isBlocked() { return "BLOCKED".equals(decision); }
        public boolean hasFindings() { return findings != null && !findings.isEmpty(); }

        @SuppressWarnings("unchecked")
        static VerifyResponse fromMap(Map<String, Object> m, double latency) {
            List<Finding> findings = new ArrayList<>();
            if (m.get("findings") instanceof List<?> fl) {
                for (Object f : fl) {
                    if (f instanceof Map<?, ?> fm) {
                        findings.add(new Finding(str(fm, "risk"), str(fm, "category"), str(fm, "description"), num(fm, "confidence")));
                    }
                }
            }
            return new VerifyResponse(str(m, "decision"), str(m, "output"), str(m, "reason"), str(m, "trace_id"), str(m, "integrity_hash"), bool(m, "should_anchor"), str(m, "flare_status"), (String) m.get("flare_tx_hash"), str(m, "content_type"), findings, latency);
        }
        private static String str(Map<?, ?> m, String k) { Object v = m.get(k); return v != null ? v.toString() : ""; }
        private static boolean bool(Map<?, ?> m, String k) { Object v = m.get(k); return v instanceof Boolean b && b; }
        private static double num(Map<?, ?> m, String k) { Object v = m.get(k); return v instanceof Number n ? n.doubleValue() : 0; }
    }

    // ── Exception ───────────────────────────────────────────

    public static final class VexisException extends Exception {
        private final String code;
        private final int statusCode;
        private final String requestId;
        private final boolean retryable;

        public VexisException(String message, String code, int statusCode, String requestId, boolean retryable) {
            super(message);
            this.code = code;
            this.statusCode = statusCode;
            this.requestId = requestId;
            this.retryable = retryable;
        }

        public String code() { return code; }
        public int statusCode() { return statusCode; }
        public String requestId() { return requestId; }
        public boolean isRetryable() { return retryable; }
    }

    // ── Circuit Breaker ─────────────────────────────────────

    private static final class CircuitBreaker {
        private final int threshold;
        private final long cooldownMs;
        private final AtomicInteger failures = new AtomicInteger();
        private final AtomicLong lastFailure = new AtomicLong();
        private final AtomicReference<String> state = new AtomicReference<>("closed");

        CircuitBreaker(int threshold, Duration cooldown) { this.threshold = threshold; this.cooldownMs = cooldown.toMillis(); }
        boolean canRequest() {
            String s = state.get();
            if ("closed".equals(s)) return true;
            if ("open".equals(s) && System.currentTimeMillis() - lastFailure.get() >= cooldownMs) { state.set("half-open"); return true; }
            return "half-open".equals(s);
        }
        void onSuccess() { failures.set(0); state.set("closed"); }
        void onFailure() { lastFailure.set(System.currentTimeMillis()); if (failures.incrementAndGet() >= threshold) state.set("open"); }
        String state() { return state.get(); }
    }

    // ── Minimal JSON (zero dependencies) ────────────────────

    static final class SimpleJson {

        /**
         * Minimal recursive-descent JSON parser. Handles the subset needed for
         * VEXIS API responses: objects, arrays, strings, numbers, booleans, null.
         * Zero external dependencies. For high-throughput production use, add
         * Jackson or Gson to the classpath and swap this implementation.
         */
        @SuppressWarnings("unchecked")
        static Map<String, Object> parse(String json) {
            if (json == null || json.isBlank()) {
                return Map.of();
            }
            var parser = new JsonParser(json.trim());
            Object result = parser.parseValue();
            if (result instanceof Map<?, ?> map) {
                return (Map<String, Object>) map;
            }
            return Map.of("value", result);
        }

        static String buildVerifyBody(VerifyRequest req) {
            var sb = new StringBuilder("{");
            sb.append("\"prompt\":").append(escapeJson(req.prompt()));
            if (req.extractedText() != null) sb.append(",\"extracted_text\":").append(escapeJson(req.extractedText()));
            if (req.metadata() != null && !req.metadata().isEmpty()) {
                sb.append(",\"metadata\":{");
                var it = req.metadata().entrySet().iterator();
                while (it.hasNext()) {
                    var e = it.next();
                    sb.append(escapeJson(e.getKey())).append(":");
                    if (e.getValue() instanceof String s) sb.append(escapeJson(s));
                    else if (e.getValue() instanceof Number n) sb.append(n);
                    else if (e.getValue() instanceof Boolean b) sb.append(b);
                    else sb.append(escapeJson(String.valueOf(e.getValue())));
                    if (it.hasNext()) sb.append(",");
                }
                sb.append("}");
            }
            if (req.attachments() != null && !req.attachments().isEmpty()) {
                sb.append(",\"attachments\":[");
                for (int i = 0; i < req.attachments().size(); i++) {
                    if (i > 0) sb.append(",");
                    var a = req.attachments().get(i);
                    sb.append("{\"content_type\":").append(escapeJson(a.contentType()))
                      .append(",\"data\":").append(escapeJson(a.data()));
                    if (a.filename() != null) sb.append(",\"filename\":").append(escapeJson(a.filename()));
                    sb.append("}");
                }
                sb.append("]");
            }
            sb.append("}");
            return sb.toString();
        }

        private static String escapeJson(String s) {
            if (s == null) return "null";
            return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\"";
        }

        // ── Recursive-descent JSON parser ───────────────────

        private static final class JsonParser {
            private final String input;
            private int pos;

            JsonParser(String input) { this.input = input; this.pos = 0; }

            Object parseValue() {
                skipWhitespace();
                if (pos >= input.length()) return null;
                char c = input.charAt(pos);
                return switch (c) {
                    case '{' -> parseObject();
                    case '[' -> parseArray();
                    case '"' -> parseString();
                    case 't', 'f' -> parseBoolean();
                    case 'n' -> parseNull();
                    default -> parseNumber();
                };
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> parseObject() {
                expect('{');
                var map = new LinkedHashMap<String, Object>();
                skipWhitespace();
                if (pos < input.length() && input.charAt(pos) == '}') { pos++; return map; }
                while (pos < input.length()) {
                    skipWhitespace();
                    String key = parseString();
                    skipWhitespace();
                    expect(':');
                    Object value = parseValue();
                    map.put(key, value);
                    skipWhitespace();
                    if (pos < input.length() && input.charAt(pos) == ',') { pos++; continue; }
                    break;
                }
                skipWhitespace();
                if (pos < input.length() && input.charAt(pos) == '}') pos++;
                return map;
            }

            List<Object> parseArray() {
                expect('[');
                var list = new ArrayList<>();
                skipWhitespace();
                if (pos < input.length() && input.charAt(pos) == ']') { pos++; return list; }
                while (pos < input.length()) {
                    list.add(parseValue());
                    skipWhitespace();
                    if (pos < input.length() && input.charAt(pos) == ',') { pos++; continue; }
                    break;
                }
                skipWhitespace();
                if (pos < input.length() && input.charAt(pos) == ']') pos++;
                return list;
            }

            String parseString() {
                expect('"');
                var sb = new StringBuilder();
                while (pos < input.length()) {
                    char c = input.charAt(pos++);
                    if (c == '"') return sb.toString();
                    if (c == '\\' && pos < input.length()) {
                        char esc = input.charAt(pos++);
                        switch (esc) {
                            case '"' -> sb.append('"');
                            case '\\' -> sb.append('\\');
                            case '/' -> sb.append('/');
                            case 'n' -> sb.append('\n');
                            case 'r' -> sb.append('\r');
                            case 't' -> sb.append('\t');
                            case 'b' -> sb.append('\b');
                            case 'f' -> sb.append('\f');
                            case 'u' -> {
                                if (pos + 4 <= input.length()) {
                                    sb.append((char) Integer.parseInt(input.substring(pos, pos + 4), 16));
                                    pos += 4;
                                }
                            }
                            default -> { sb.append('\\'); sb.append(esc); }
                        }
                    } else {
                        sb.append(c);
                    }
                }
                return sb.toString();
            }

            Number parseNumber() {
                int start = pos;
                if (pos < input.length() && input.charAt(pos) == '-') pos++;
                while (pos < input.length() && Character.isDigit(input.charAt(pos))) pos++;
                boolean isFloat = false;
                if (pos < input.length() && input.charAt(pos) == '.') { isFloat = true; pos++; while (pos < input.length() && Character.isDigit(input.charAt(pos))) pos++; }
                if (pos < input.length() && (input.charAt(pos) == 'e' || input.charAt(pos) == 'E')) { isFloat = true; pos++; if (pos < input.length() && (input.charAt(pos) == '+' || input.charAt(pos) == '-')) pos++; while (pos < input.length() && Character.isDigit(input.charAt(pos))) pos++; }
                String numStr = input.substring(start, pos);
                if (isFloat) return Double.parseDouble(numStr);
                long val = Long.parseLong(numStr);
                if (val >= Integer.MIN_VALUE && val <= Integer.MAX_VALUE) return (int) val;
                return val;
            }

            Boolean parseBoolean() {
                if (input.startsWith("true", pos)) { pos += 4; return Boolean.TRUE; }
                if (input.startsWith("false", pos)) { pos += 5; return Boolean.FALSE; }
                throw new IllegalStateException("Expected boolean at position " + pos);
            }

            Object parseNull() {
                if (input.startsWith("null", pos)) { pos += 4; return null; }
                throw new IllegalStateException("Expected null at position " + pos);
            }

            void expect(char c) {
                skipWhitespace();
                if (pos < input.length() && input.charAt(pos) == c) { pos++; return; }
                throw new IllegalStateException("Expected '" + c + "' at position " + pos + " but got '" + (pos < input.length() ? input.charAt(pos) : "EOF") + "'");
            }

            void skipWhitespace() {
                while (pos < input.length() && Character.isWhitespace(input.charAt(pos))) pos++;
            }
        }
    }
}