package vn.shop.crm.send;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Hẹn giờ gửi từng tin một qua kênh tự động. Mặc định TẮT; hiện chưa có kênh tự động nào (chưa chọn nhà cung cấp
 * SMS/ZBS) nên bật cũng chưa làm gì. Mọi lần gửi vẫn đi qua {@link SendService} và cổng chặn của nó.
 */
@Component
@ConditionalOnProperty(prefix = "app.sending", name = "scheduler-enabled", havingValue = "true")
public class SendScheduler {

    private final SendService service;
    private final SendProperties props;

    public SendScheduler(SendService service, SendProperties props) {
        this.service = service;
        this.props = props;
    }

    @Scheduled(fixedDelayString = "${app.sending.tick-millis:30000}")
    void tick() {
        service.sender(props.autoChannel()).filter(InvitationSender::automated).ifPresent(service::sendNext);
    }
}
