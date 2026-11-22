package vn.shop.crm.send;

import org.springframework.stereotype.Component;

/** Chạy thử (DRY_RUN): cho biết "nếu gửi thì sẽ gửi tin này cho ai", không gửi gì, không đổi trạng thái. */
@Component
public class MockSender implements InvitationSender {

    @Override
    public String channel() {
        return "MOCK";
    }

    @Override
    public boolean automated() {
        return false;
    }

    @Override
    public boolean realDelivery() {
        return false;
    }

    @Override
    public int costPerMessageVnd() {
        return 0;
    }

    @Override
    public SendResult send(OutboundInvite invite) {
        return SendResult.success();
    }
}
