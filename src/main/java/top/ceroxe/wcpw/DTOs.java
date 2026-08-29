package top.ceroxe.wcpw;

public class DTOs {
    public record PaymentRequest(
            String token,
            double money,
            String timestamp,
            String callbackUrl
    ) {
    }

    public record BaseResponse(String status, String message, Object data) {
    }

    public record PendingData(int waitSeconds) {
    }

    // 增加了 oid 字段，确保 payload 完整
    public record CallbackPayload(
            String oid,
            String status,
            String requestTimestamp,
            long detectTimestamp,
            double amount,
            String message
    ) {
    }

    public record DurableCallbackTask(
            String recordId,
            String taskId,
            String callbackUrl,
            CallbackPayload payload,
            int attemptsMade,
            long nextAttemptAt
    ) {
        public DurableCallbackTask(String recordId, String taskId, String callbackUrl, CallbackPayload payload) {
            this(recordId, taskId, callbackUrl, payload, 0, 0L);
        }

        public DurableCallbackTask withAttemptsMade(int attemptsMade) {
            return new DurableCallbackTask(recordId, taskId, callbackUrl, payload, attemptsMade, nextAttemptAt);
        }

        public DurableCallbackTask withAttemptState(int attemptsMade, long nextAttemptAt) {
            return new DurableCallbackTask(recordId, taskId, callbackUrl, payload, attemptsMade, nextAttemptAt);
        }
    }
}
