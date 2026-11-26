package vn.shop.crm.catalog;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import vn.shop.crm.common.ImportException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

@Service
class TikTokCatalog {
    private static final URI BASE = URI.create("https://open-api.tiktokglobalshop.com");
    private static final String SEARCH = "/product/202309/products/search";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final String appKey;
    private final String secret;
    private final String shopCipher;
    private final String accessToken;
    private final Clock clock;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).build();

    public record Item(String productId, String skuId, String sellerSku, String name, BigDecimal price, String currency, Long stock) {
    }

    TikTokCatalog(@Value("${APP_TIKTOK_APP_KEY:}") String appKey, @Value("${APP_TIKTOK_APP_SECRET:}") String secret,
                  @Value("${APP_TIKTOK_SHOP_CIPHER:}") String shopCipher, @Value("${APP_TIKTOK_ACCESS_TOKEN:}") String accessToken,
                  Clock clock) {
        this.appKey = appKey;
        this.secret = secret;
        this.shopCipher = shopCipher;
        this.accessToken = accessToken;
        this.clock = clock;
    }

    boolean configured() {
        return !appKey.isBlank() && !secret.isBlank() && !shopCipher.isBlank() && !accessToken.isBlank();
    }

    List<Item> lookup(String sku) {
        if (!configured() || sku.isBlank() || sku.length() > 100 || sku.chars().anyMatch(Character::isISOControl)) {
            throw new ImportException("Kiểm tra cấu hình TikTok Shop và mã SKU cần tra.");
        }
        try {
            String payload = JSON.writeValueAsString(Map.of("status", "ACTIVATE", "seller_skus", List.of(sku)));
            var params = new TreeMap<>(Map.of("app_key", appKey, "page_size", "100", "shop_cipher", shopCipher,
                    "timestamp", Long.toString(clock.instant().getEpochSecond())));
            params.put("sign", signature(SEARCH, params, payload, secret));
            String query = params.entrySet().stream()
                    .map(e -> e.getKey() + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                    .collect(Collectors.joining("&"));
            var req = HttpRequest.newBuilder(BASE.resolve(SEARCH + "?" + query)).timeout(Duration.ofSeconds(8))
                    .header("content-type", "application/json")
                    .header("x-tts-access-token", accessToken).POST(HttpRequest.BodyPublishers.ofString(payload)).build();
            var response = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
            try (var body = response.body()) {
                byte[] bytes = body.readNBytes(1_000_001);
                if (response.statusCode() == 200 && bytes.length <= 1_000_000) {
                    JsonNode result = JSON.readTree(bytes);
                    if (result.path("code").asInt(-1) == 0 && result.path("data").path("products").isArray()) {
                        return findSku(result, sku);
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (java.io.IOException | RuntimeException e) {
            // Không ghi URL, token, hoặc nội dung trả về từ shop.
        }
        throw new ImportException("Chưa tra được TikTok Shop. Kiểm tra quyền đọc sản phẩm hoặc thử lại.");
    }

    static String signature(String path, TreeMap<String, String> params, String payload, String secret) {
        StringBuilder input = new StringBuilder(secret).append(path);
        params.forEach((key, value) -> input.append(key).append(value));
        input.append(payload).append(secret);
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(input.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Không ký được yêu cầu TikTok Shop.", e);
        }
    }

    static List<Item> findSku(JsonNode result, String wanted) {
        List<Item> found = new ArrayList<>();
        for (JsonNode product : result.path("data").path("products")) {
            if (!"ACTIVATE".equals(product.path("status").asString())) continue;
            for (JsonNode sku : product.path("skus")) {
                if (wanted.equals(sku.path("seller_sku").asString())) {
                    var price = sku.path("price");
                    found.add(new Item(product.path("id").asString(), sku.path("id").asString(), wanted,
                            product.path("title").asString(), amount(price.path("sale_price").asString()),
                            price.path("currency").asString(), stock(sku.path("inventory"))));
                }
            }
        }
        return List.copyOf(found);
    }

    private static Long stock(JsonNode inventory) {
        if (!inventory.isArray()) return null;
        long sum = 0;
        var warehouses = new HashSet<String>();
        for (JsonNode row : inventory) {
            String warehouse = row.path("warehouse_id").asString();
            long qty = row.path("quantity").asLong(-1);
            if (qty < 0 || warehouse.isBlank() || !warehouses.add(warehouse)) return null;
            sum = Math.addExact(sum, qty);
        }
        return sum;
    }

    private static BigDecimal amount(String value) {
        if (!value.matches("[0-9]{1,12}(\\.[0-9]{1,4})?")) return null;
        return new BigDecimal(value);
    }
}
