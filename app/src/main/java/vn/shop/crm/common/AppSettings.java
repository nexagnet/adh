package vn.shop.crm.common;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.util.Optional;

/** Cài đặt khóa/giá trị lưu trong cơ sở dữ liệu. Chỉ dành cho cờ vận hành, không chứa bí mật. */
@Service
public class AppSettings {

    /** Cờ "tạm dừng tất cả chiến dịch": khi bật, không tạo lời mời mới và không gửi tin. */
    public static final String PAUSED = "campaigns.paused";
    public static final String PAUSED_REASON = "campaigns.paused_reason";
    /** Bot tắt/bật toàn hệ thống (mốc 1E). Mặc định TẮT. */
    public static final String BOT_ENABLED = "bot.enabled";

    private final JdbcClient jdbc;

    public AppSettings(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<String> get(String key) {
        return jdbc.sql("SELECT value FROM app_setting WHERE key = :k").param("k", key).query(String.class).optional();
    }

    public boolean flag(String key) {
        return get(key).map("true"::equals).orElse(false);
    }

    public void set(String key, String value, String by) {
        jdbc.sql("""
                        INSERT INTO app_setting (key, value, updated_by) VALUES (:k, :v, :by)
                        ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, updated_at = now(),
                                                        updated_by = EXCLUDED.updated_by""")
                .param("k", key).param("v", value).param("by", by).update();
    }

    public boolean paused() {
        return flag(PAUSED);
    }

    public String pausedReason() {
        return get(PAUSED_REASON).orElse("");
    }

    /** Tạm dừng tất cả chiến dịch, ghi lý do và người thực hiện. */
    public void pauseAll(String reason, String by) {
        set(PAUSED_REASON, reason == null ? "" : reason, by);
        set(PAUSED, "true", by);
    }

    public void resumeAll(String by) {
        set(PAUSED_REASON, "", by);
        set(PAUSED, "false", by);
    }
}
