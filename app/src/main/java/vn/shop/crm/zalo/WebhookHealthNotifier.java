package vn.shop.crm.zalo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * POST văn bản thuần tới một địa chỉ do chủ shop đặt ở biến môi trường {@code APP_ALERT_WEBHOOK_URL} (ví dụ một chủ đề ntfy.sh bí mật,
 * hoặc dịch vụ tự dựng). Chỉ tồn tại khi biến này khác rỗng. Địa chỉ có thể chứa khóa nên không bao giờ được ghi ra log.
 */
@Component
@ConditionalOnExpression("!'${app.alert.webhook-url:}'.isBlank()")
public class WebhookHealthNotifier implements ZaloHealthNotifier {

    private static final Logger log = LoggerFactory.getLogger(WebhookHealthNotifier.class);

    private final URI uri; // null nếu cấu hình sai
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public WebhookHealthNotifier(@Value("${app.alert.webhook-url:}") String url) {
        URI parsed = null;
        try {
            parsed = URI.create(url.strip());
            if (!"https".equalsIgnoreCase(parsed.getScheme()) || parsed.getHost() == null) {
                parsed = null;
            }
        } catch (IllegalArgumentException e) {
            parsed = null;
        }
        if (parsed == null) {
            // Kênh báo phụ không được làm ứng dụng không khởi động: báo lỗi cấu hình (không in địa chỉ) và tắt kênh này.
            log.warn("APP_ALERT_WEBHOOK_URL không hợp lệ (phải là địa chỉ https://...): kênh báo ngoài bị tắt.");
        }
        this.uri = parsed;
    }

    @Override
    public boolean send(String text) {
        if (uri == null) {
            return false;
        }
        try {
            HttpRequest req = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "text/plain; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(text, StandardCharsets.UTF_8)).build();
            int code = client.send(req, HttpResponse.BodyHandlers.discarding()).statusCode();
            if (code / 100 != 2) {
                log.warn("Kênh báo ngoài trả mã {}", code);
                return false;
            }
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            log.warn("Không gửi được tin báo ngoài: {}", e.getClass().getSimpleName());
            return false;
        }
    }
}
