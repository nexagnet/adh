package vn.shop.crm.bot;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.util.Optional;

/** Nhận sự kiện đã xác thực từ kênh Zalo. Không dùng ID nội bộ do phía gửi cung cấp. */
@Service
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "APP_ADH_STAGE2_ENABLED", havingValue = "true")
public class ZaloGroupIngress {
    private final JdbcClient jdbc;
    private final BotEngine engine;

    public ZaloGroupIngress(JdbcClient jdbc, BotEngine engine) {
        this.jdbc = jdbc;
        this.engine = engine;
    }

    public Optional<BotEngine.Decision> receive(long accountId, String groupId, String eventId, BotEngine.SenderKind sender, String text) {
        if (accountId <= 0 || groupId == null || !groupId.matches("[A-Za-z0-9_-]{1,80}")
                || eventId == null || !eventId.matches("[A-Za-z0-9_-]{1,128}") || sender == null
                || text == null || text.isBlank() || text.length() > 2000) {
            return Optional.empty();
        }
        var groups = jdbc.sql("""
                        SELECT g.id FROM zalo_group g JOIN zalo_account a ON g.account_id = a.id
                        WHERE a.id = :account AND a.enabled AND g.zalo_group_id = :group AND g.bot_enabled LIMIT 2""")
                .param("account", accountId).param("group", groupId).query(Long.class).list();
        return groups.size() == 1
                ? Optional.of(engine.handle(new BotEngine.IncomingMessage(groups.getFirst(), eventId, sender, text)))
                : Optional.empty();
    }
}
