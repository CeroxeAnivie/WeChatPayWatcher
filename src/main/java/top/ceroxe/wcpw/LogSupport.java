package top.ceroxe.wcpw;

/**
 * Produces bounded, actionable exception summaries for operational logs.
 * Full stack traces are intentionally excluded from normal service logs so a
 * transient dependency failure cannot flood disk or hide the real event.
 */
public final class LogSupport {
    private LogSupport() {
    }

    public static String describe(Throwable error) {
        if (error == null) return "unknown";
        Throwable root = error;
        int depth = 0;
        while (root.getCause() != null && root.getCause() != root && depth++ < 8) {
            root = root.getCause();
        }
        String type = root.getClass().getSimpleName();
        String message = root.getMessage();
        if (message == null || message.isBlank()) return type;
        return type + ": " + compact(message);
    }

    private static String compact(String message) {
        String normalized = message.replaceAll("[\\r\\n\\t]+", " ").trim();
        return normalized.length() <= 240 ? normalized : normalized.substring(0, 240) + "...";
    }
}
