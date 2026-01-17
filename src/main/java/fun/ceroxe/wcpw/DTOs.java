package fun.ceroxe.wcpw;

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
}