package fun.ceroxe.wcpw;

public class DTOs {
    // 【修改】增加了 token 字段
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

    public record CallbackPayload(
            String status,
            String requestTimestamp,
            long detectTimestamp,
            double amount,
            String message
    ) {
    }
}