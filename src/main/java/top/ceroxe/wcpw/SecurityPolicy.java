package top.ceroxe.wcpw;

import okhttp3.HttpUrl;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Centralizes input and outbound-request policy so the HTTP entry point and
 * callback client enforce the same contract.
 */
public final class SecurityPolicy {
    private final int maxRequestBodyBytes;
    private final int maxCallbackUrlLength;
    private final double maxAmount;
    private final int maxOrderTimeoutSeconds;
    private final Set<String> allowedCallbackSchemes;
    private final String signatureAlgorithm;

    public SecurityPolicy() {
        this.maxRequestBodyBytes = boundedInt("request.max.body.bytes", 64 * 1024, 1024, 1024 * 1024);
        this.maxCallbackUrlLength = boundedInt("callback.url.max.length", 2048, 128, 16 * 1024);
        this.maxAmount = boundedDouble("order.max.amount", 1_000_000d, 0.01d, 100_000_000d);
        this.maxOrderTimeoutSeconds = boundedInt("order.timeout.max.seconds", 900, 10, 86_400);
        this.allowedCallbackSchemes = parseSchemes(AppConfig.get("callback.allowed.schemes", "http,https"));
        this.signatureAlgorithm = normalizeSignatureAlgorithm(AppConfig.get("callback.signature.algorithm", "MD5"));
    }

    public int maxRequestBodyBytes() {
        return maxRequestBodyBytes;
    }

    public String validatePaymentRequest(DTOs.PaymentRequest request) {
        if (request == null) return "request is missing";
        if (request.token() == null || request.token().isBlank()) return "token is missing";
        if (!Double.isFinite(request.money()) || request.money() <= 0 || request.money() > maxAmount) {
            return "money is invalid";
        }
        if (BigDecimal.valueOf(request.money()).stripTrailingZeros().scale() > 2) {
            return "money must have at most two decimal places";
        }
        if (request.timestamp() == null || request.timestamp().isBlank() || request.timestamp().length() > 64) {
            return "timestamp is invalid";
        }
        if (request.callbackUrl() == null || request.callbackUrl().isBlank()) {
            return "callbackUrl is missing";
        }
        if (request.callbackUrl().length() > maxCallbackUrlLength) {
            return "callbackUrl is too long";
        }

        HttpUrl parsed = HttpUrl.parse(request.callbackUrl());
        if (parsed == null || !allowedCallbackSchemes.contains(parsed.scheme().toLowerCase(Locale.ROOT))) {
            return "callbackUrl scheme is not allowed";
        }
        if (parsed.host().isBlank() || !parsed.username().isEmpty()) {
            return "callbackUrl is invalid";
        }
        return null;
    }

    public boolean matchesToken(String expected, String supplied) {
        if (expected == null || supplied == null) return false;
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                supplied.getBytes(StandardCharsets.UTF_8)
        );
    }

    public boolean isCallbackSchemeAllowed(String scheme) {
        return scheme != null && allowedCallbackSchemes.contains(scheme.toLowerCase(Locale.ROOT));
    }

    public String signatureAlgorithm() {
        return signatureAlgorithm;
    }

    public int normalizeOrderTimeoutSeconds(int configured) {
        return Math.max(1, Math.min(maxOrderTimeoutSeconds, configured));
    }

    private int boundedInt(String key, int defaultValue, int min, int max) {
        int value = AppConfig.getInt(key, defaultValue);
        return Math.max(min, Math.min(max, value));
    }

    private double boundedDouble(String key, double defaultValue, double min, double max) {
        String raw = AppConfig.get(key);
        if (raw == null || raw.isBlank()) return defaultValue;
        try {
            double value = Double.parseDouble(raw);
            return Double.isFinite(value) ? Math.max(min, Math.min(max, value)) : defaultValue;
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    private Set<String> parseSchemes(String raw) {
        if (raw == null || raw.isBlank()) return Set.of("http", "https");
        Set<String> schemes = Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .map(value -> value.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
        return schemes.isEmpty() ? Set.of("http", "https") : schemes;
    }

    private String normalizeSignatureAlgorithm(String raw) {
        String value = raw == null ? "MD5" : raw.trim().toUpperCase(Locale.ROOT);
        return switch (value) {
            case "MD5", "HMAC-SHA256" -> value;
            default -> "MD5";
        };
    }
}
