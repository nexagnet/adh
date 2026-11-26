package vn.shop.crm.bot;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import vn.shop.crm.common.AppSettings;

@Service
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "APP_ADH_STAGE2_ENABLED", havingValue = "true")
class HandoffNotificationDispatcher {
    private final JdbcClient jdbc;
    private final AppSettings settings;
    private final ObjectProvider<HandoffNotificationTransport> channel;
    private final String link;

    HandoffNotificationDispatcher(JdbcClient jdbc, AppSettings settings, ObjectProvider<HandoffNotificationTransport> channel,
                                  @Value("${app.public-base-url:http://localhost:8080}") String baseUrl) {
        this.jdbc = jdbc;
        this.settings = settings;
        this.channel = channel;
        this.link = baseUrl.replaceAll("/+$", "") + "/staff/work";
    }

    @Scheduled(fixedDelayString = "${app.bot.notifications.tick-ms:60000}", initialDelayString = "${app.bot.notifications.tick-ms:60000}")
    void tick() {
        var transport = channel.getIfAvailable();
        if (transport == null || !settings.botEnabled() || settings.botDryRun()) return;
        var next = jdbc.sql("""
                WITH task AS (
                    SELECT n.handoff_id FROM handoff_notification n JOIN handoff h ON h.id = n.handoff_id
                    WHERE n.state = 'PENDING' AND h.status <> 'RESOLVED'
                      AND (n.attempted_at IS NULL OR n.attempted_at < now() - interval '5 minutes')
                    ORDER BY n.queued_at LIMIT 1 FOR UPDATE OF n SKIP LOCKED
                )
                UPDATE handoff_notification n SET state = 'SENDING', attempted_at = now()
                FROM task WHERE n.handoff_id = task.handoff_id RETURNING n.handoff_id
                """).query(Long.class).optional();
        next.ifPresent(id -> dispatch(transport, id));
    }

    private void dispatch(HandoffNotificationTransport transport, long id) {
        var result = HandoffNotificationTransport.Outcome.UNKNOWN;
        try {
            result = transport.send(id, "Có việc cần xử lý trong ADH. " + link);
        } finally {
            String state = switch (result) {
                case SENT -> "SENT";
                case REJECTED_BEFORE_SEND -> "PENDING";
                case UNKNOWN -> "UNKNOWN";
            };
            jdbc.sql("""
                    UPDATE handoff_notification SET state = :state,
                        sent_at = CASE WHEN :state = 'SENT' THEN now() ELSE NULL END
                    WHERE handoff_id = :id AND state = 'SENDING'
                    """).param("id", id).param("state", state).update();
        }
    }
}
