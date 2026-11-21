package vn.shop.crm.send;

import org.springframework.stereotype.Component;

/**
 * Xuất danh sách (số điện thoại, tên, nội dung tin kèm link riêng) để mẹ tự gửi bằng Zalo/điện thoại của mình.
 * Hệ thống không biết tin có thật sự tới khách hay không; chỉ ghi nhận "đã xuất để gửi tay".
 */
@Component
public class ManualExportSender implements InvitationSender {

    @Override
    public String channel() {
        return "MANUAL_EXPORT";
    }

    @Override
    public boolean automated() {
        return false;
    }

    @Override
    public boolean realDelivery() {
        return true;
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
