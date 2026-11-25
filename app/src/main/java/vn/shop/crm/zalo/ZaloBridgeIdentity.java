package vn.shop.crm.zalo;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Dấu tài khoản ghi trong CRM sau khi xác nhận cầu nối. Giữ nguyên cả khi tạm ngưng tài khoản. */
@Component
final class ZaloBridgeIdentity {

    private final JdbcClient jdbc;

    ZaloBridgeIdentity(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    String pinnedTag(long accountId) {
        return jdbc.sql("SELECT account_tag FROM zalo_account WHERE id = :id AND account_tag IS NOT NULL")
                .param("id", accountId).query(String.class).optional().orElse(null);
    }

    boolean pin(long accountId, String tag) {
        return jdbc.sql("""
                        UPDATE zalo_account SET account_tag = :tag
                        WHERE id = :id AND (account_tag IS NULL OR account_tag = :tag)""")
                .param("id", accountId).param("tag", tag).update() == 1;
    }

    static boolean usable(ZaloGateway.Status status) {
        return status.listening() && Boolean.TRUE.equals(status.accountOk()) && status.accountTag() != null
                && status.accountTag().matches("[0-9a-f]{12}");
    }
}
