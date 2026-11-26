package vn.shop.crm.zalo;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

import java.net.URI;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Các tài khoản Zalo phụ THÊM (D23): tài khoản 1 vẫn dùng cấu hình cũ ({@code app.zalo.auto.bridge-url} và {@code bridge-key}), nên không khai báo
 * gì ở đây thì hệ thống chạy y như trước. Mỗi tài khoản thêm có cầu nối RIÊNG (container riêng, có thể ở máy chủ thứ hai) với khóa chung riêng; khóa
 * chỉ nằm ở môi trường triển khai (APP_ZALO_ACCOUNTS_0_ID, APP_ZALO_ACCOUNTS_0_BRIDGE_URL, APP_ZALO_ACCOUNTS_0_BRIDGE_KEY), không vào cơ sở dữ liệu.
 * Bảng {@code zalo_account} giữ nhãn và dấu tài khoản; mã tài khoản ở đây phải khớp một dòng của bảng đó.
 */
@ConfigurationProperties(prefix = "app.zalo")
public record ZaloAccountProperties(@DefaultValue List<Account> accounts) {

    public record Account(long id, String bridgeUrl, String bridgeKey, boolean live) {

        @ConstructorBinding
        public Account {
        }

        public Account(long id, String bridgeUrl, String bridgeKey) {
            this(id, bridgeUrl, bridgeKey, false);
        }
    }

    public ZaloAccountProperties {
        accounts = accounts == null ? List.of() : List.copyOf(accounts);
        Set<Long> seen = new HashSet<>();
        Set<String> endpoints = new HashSet<>();
        for (Account a : accounts) {
            if (a.id() < 2) {
                throw new IllegalStateException("Tài khoản Zalo thêm phải có mã từ 2 (mã 1 là tài khoản mặc định dùng cấu hình cũ).");
            }
            if (!seen.add(a.id())) {
                throw new IllegalStateException("Trùng mã tài khoản Zalo " + a.id() + " trong cấu hình.");
            }
            if (a.bridgeUrl() == null || a.bridgeUrl().isBlank() || a.bridgeKey() == null || a.bridgeKey().isBlank()) {
                throw new IllegalStateException("Tài khoản Zalo " + a.id() + " thiếu địa chỉ hoặc khóa chung của cầu nối.");
            }
            if (!endpoints.add(endpoint(a.bridgeUrl()))) {
                throw new IllegalStateException("Trùng địa chỉ cầu nối của tài khoản Zalo " + a.id() + ".");
            }
        }
    }

    public boolean live(long accountId) {
        return accountId == ZaloAccountContext.DEFAULT_ACCOUNT
                || accounts.stream().anyMatch(account -> account.id() == accountId && account.live());
    }

    static String endpoint(String url) {
        try {
            URI uri = URI.create(url).normalize();
            String scheme = uri.getScheme();
            if (("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) && uri.getHost() != null
                    && uri.getUserInfo() == null && uri.getQuery() == null && uri.getFragment() == null) {
                int port = uri.getPort();
                if (port == -1) {
                    port = "https".equalsIgnoreCase(scheme) ? 443 : 80;
                }
                return scheme.toLowerCase(Locale.ROOT) + "://" + uri.getHost().toLowerCase(Locale.ROOT) + ":" + port
                        + uri.getRawPath().replaceFirst("/+$", "");
            }
        } catch (IllegalArgumentException ignored) {
            // Trả cùng lỗi cấu hình và không đưa URL vào nhật ký.
        }
        throw new IllegalStateException("Địa chỉ cầu nối Zalo không hợp lệ.");
    }
}
