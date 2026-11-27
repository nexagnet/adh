package vn.shop.crm.zalo;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import vn.shop.crm.AbstractPostgresTest;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

class ZaloGroupUpgradeTest extends AbstractPostgresTest {
    @Autowired DataSource dataSource;

    @Test
    void upgradeFrom64_5PreservesLiveAndExpiredHoldsAndExistingMembership() throws Exception {
        String schema = "shared_group_upgrade";
        Flyway.configure().dataSource(dataSource).schemas(schema).target(MigrationVersion.fromVersion("64.5")).load().migrate();
        String url;
        try (var connection = dataSource.getConnection()) {
            url = connection.getMetaData().getURL();
        }
        try (var connection = java.sql.DriverManager.getConnection(url, "postgres", "")) {
            connection.setSchema(schema);
            var commands = JdbcClient.create(new SingleConnectionDataSource(connection, true));
            commands.sql("""
                    INSERT INTO zalo_group (id, name, invite_link, zalo_group_id, status, actual_customers)
                    VALUES (80, 'Nhóm cũ', 'https://zalo.me/g/old', '1234', 'OPEN', 4)
                    """).update();
            commands.sql("""
                    INSERT INTO customer (id, phone, segment, successful_purchases)
                    VALUES (80, '+84900000100', 'ONE_TIME', 1), (81, '+84900000101', 'ONE_TIME', 1),
                           (82, '+84900000102', 'ONE_TIME', 1)
                    """).update();
            commands.sql("""
                    INSERT INTO invitation_campaign (id, name, message_template, expires_days, created_by)
                    VALUES (80, 'Chiến dịch cũ', 'test', 14, 'test')
                    """).update();
            commands.sql("""
                    INSERT INTO invitation (id, campaign_id, customer_id, token, expires_at, status,
                                            group_id, reserved_group_id, reserved_until, joined_at)
                    VALUES (80, 80, 80, 'fixture-1', now()+interval '1 day', 'FRIENDED', NULL, 80, now()+interval '2 hours', NULL),
                           (81, 80, 81, 'fixture-2', now()+interval '1 day', 'FRIENDED', NULL, 80, now()-interval '2 hours', NULL),
                           (82, 80, 82, 'fixture-3', now()+interval '1 day', 'JOINED', 80, 80, now()-interval '2 hours', now())
                    """).update();
            commands.sql("""
                    INSERT INTO zalo_auto_log (action, invitation_id, group_id, result, actor)
                    VALUES ('THEM_NHOM', 82, 80, 'OK', 'system')
                    """).update();
            String invitations = "SELECT id, account_id, group_id, reserved_group_id, reserved_until, status, joined_at FROM invitation ORDER BY id";
            String groups = "SELECT id, account_id, name, invite_link, zalo_group_id, actual_customers FROM zalo_group ORDER BY id";
            var oldInvitations = commands.sql(invitations).query().listOfRows();
            var oldGroups = commands.sql(groups).query().listOfRows();
            var oldLog = commands.sql("SELECT * FROM zalo_auto_log").query().listOfRows();

            Flyway.configure().dataSource(dataSource).schemas(schema).load().migrate();

            assertThat(commands.sql(invitations).query().listOfRows()).isEqualTo(oldInvitations);
            assertThat(commands.sql(groups).query().listOfRows()).isEqualTo(oldGroups);
            assertThat(commands.sql("SELECT * FROM zalo_auto_log").query().listOfRows()).isEqualTo(oldLog);
            assertThat(commands.sql("SELECT shared_add_enabled FROM zalo_group").query(Boolean.class).single()).isFalse();
            assertThat(commands.sql("SELECT count(*) FROM zalo_group_add_attempt").query(Long.class).single()).isZero();
        }
    }
}
