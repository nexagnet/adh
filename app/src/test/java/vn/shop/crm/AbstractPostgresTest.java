package vn.shop.crm;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;

/**
 * Nền cho các test tích hợp: một Postgres thật chạy nhúng (không cần Docker), Flyway chạy migration thật.
 * Dữ liệu giữa các test được dọn sạch; bảng ánh xạ trạng thái trả về đúng dữ liệu gốc của migration.
 */
@SpringBootTest
@ActiveProfiles("test")
public abstract class AbstractPostgresTest {

    private static final EmbeddedPostgres POSTGRES;

    static {
        try {
            POSTGRES = EmbeddedPostgres.builder().start();
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl("postgres", "postgres"));
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "");
    }

    @Autowired
    protected JdbcClient jdbc;

    @BeforeEach
    void cleanDatabase() {
        jdbc.sql("""
                TRUNCATE shipment_status_history, shipment, customer, import_error, import_batch,
                         column_mapping, import_setting, contact_basis, customer_note, suppression, contact_event,
                         data_request, invitation, invitation_campaign, open_entry, zalo_group, app_setting, send_log, faq_entry, bot_event, handoff, weather_forecast, test_run, test_target, zalo_friend_event, zalo_auto_log, zalo_msg_log, ai_usage
                         RESTART IDENTITY CASCADE""").update();
        jdbc.sql("DELETE FROM status_mapping WHERE carrier <> 'VIETTEL_POST'").update();
        jdbc.sql("DELETE FROM status_mapping WHERE status_key = 'cho xu ly'").update();
    }
}
