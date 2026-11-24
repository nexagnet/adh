package vn.shop.crm.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Gọi API kiểu OpenAI của DeepSeek ({@code POST {baseUrl}/chat/completions}). Không chuyển hướng (khỏi gửi khóa sang nơi lạ),
 * hết giờ chờ ngắn, không thử lại (đã có trần lượt và cầu dao ở {@link AiGate}).
 * Không bao giờ ghi khóa, câu hỏi hay câu trả lời vào log hoặc thông điệp lỗi; chỉ ghi mã HTTP.
 */
public class DeepSeekClient implements AiClient {

    private static final Logger log = LoggerFactory.getLogger(DeepSeekClient.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final HttpClient http;
    private final URI endpoint;
    private final String key;
    private final String model;
    private final Duration timeout;
    private final int maxTokens;

    public DeepSeekClient(AiProperties props) {
        this(props, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .version(HttpClient.Version.HTTP_1_1).followRedirects(HttpClient.Redirect.NEVER).build());
    }

    DeepSeekClient(AiProperties props, HttpClient http) {
        String base = props.baseUrl().strip();
        base = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        URI uri = URI.create(base + "/chat/completions");
        boolean local = "localhost".equals(uri.getHost()) || "127.0.0.1".equals(uri.getHost());
        if (!"https".equals(uri.getScheme()) && !(local && "http".equals(uri.getScheme()))) {
            // khóa API không bao giờ đi trên đường không mã hóa
            throw new IllegalStateException("APP_AI_BASE_URL phải là địa chỉ https.");
        }
        this.endpoint = uri;
        this.key = props.apiKey();
        this.model = props.model();
        this.timeout = Duration.ofSeconds(props.timeoutSeconds());
        this.maxTokens = props.maxOutputTokens();
        this.http = http;
    }

    @Override
    public String complete(String system, String user, boolean json) {
        return call(system, user, json, maxTokens, timeout, model, false);
    }

    @Override
    public String complete(String system, String user, boolean json, Options options) {
        boolean think = options.thinking();
        int tokens = Math.max(50, Math.min(options.maxOutputTokens(), think ? Options.HARD_MAX_TOKENS_THINKING : Options.HARD_MAX_TOKENS));
        Duration t = Duration.ofSeconds(Math.max(2, Math.min(options.timeoutSeconds(),
                think ? Options.HARD_MAX_TIMEOUT_THINKING : Options.HARD_MAX_TIMEOUT)));
        String m = options.model() != null && MODEL_NAME.matcher(options.model()).matches() ? options.model() : model;
        return call(system, user, json, tokens, t, m, think);
    }

    private static final java.util.regex.Pattern MODEL_NAME = java.util.regex.Pattern.compile("[A-Za-z0-9._:-]{1,64}");

    private String call(String system, String user, boolean json, int maxTokens, Duration timeout, String model, boolean think) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", List.of(Map.of("role", "system", "content", system), Map.of("role", "user", "content", user)));
        body.put("max_tokens", maxTokens);
        body.put("temperature", 0);
        // Mặc định tắt chế độ "suy nghĩ sâu": phân loại và soạn câu ngắn không cần, và nó làm chậm cả lượt gọi lẫn hàng chờ trả lời.
        // Chỉ lượt đề xuất chiến dịch (người bấm chờ, ít lượt mỗi ngày) mới bật.
        body.put("thinking", Map.of("type", think ? "enabled" : "disabled"));
        body.put("stream", false);
        if (json) {
            body.put("response_format", Map.of("type", "json_object"));
        }
        try {
            HttpRequest req = HttpRequest.newBuilder(endpoint).timeout(timeout)
                    .header("Authorization", "Bearer " + key).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) {
                log.warn("AI trả mã HTTP {}", res.statusCode());
                throw new AiUnavailableException("AI trả mã " + res.statusCode());
            }
            JsonNode content = JSON.readTree(res.body()).path("choices").path(0).path("message").path("content");
            if (!content.isString() || content.asString().isBlank()) {
                throw new AiUnavailableException("AI trả câu trả lời rỗng hoặc lạ");
            }
            return content.asString();
        } catch (AiUnavailableException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiUnavailableException("Bị ngắt khi chờ AI");
        } catch (IOException | RuntimeException e) {
            log.warn("Không gọi được AI: {}", e.getClass().getSimpleName());
            throw new AiUnavailableException("Không gọi được AI (" + e.getClass().getSimpleName() + ")");
        }
    }
}
