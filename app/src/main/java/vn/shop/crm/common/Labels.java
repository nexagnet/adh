package vn.shop.crm.common;

import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Nhãn tiếng Việt cho các mã kỹ thuật lưu trong cơ sở dữ liệu. Giao diện không bao giờ được hiện mã gốc
 * (như MOCK, ANSWERED): dùng bean {@code labels} trong template, hoặc các hàm tĩnh ở mã Java.
 */
@Component("labels")
public class Labels {

    private static final Map<String, String> CHANNEL = Map.of(
            "MOCK", "Gửi giả lập (không gửi thật)",
            "MANUAL_EXPORT", "Zalo, mẹ gửi tay (xuất danh sách)",
            "ZALO_PHU", "Zalo tài khoản phụ (nhắn tay)");

    private static final Map<String, String> CONTACT_EVENT = Map.of(
            "BASIS_APPROVED", "Duyệt vào danh sách mời",
            "BLOCKED", "Chặn không liên hệ",
            "UNBLOCKED", "Bỏ chặn",
            "OPTED_OUT", "Khách xin ngừng nhận tin",
            "INVITATION_CREATED", "Tạo lời mời",
            "INVITATION_SENT", "Đã gửi lời mời",
            "INVITATION_FOLLOWUP", "Đã gửi tin nhắc",
            "REQUEST_OPENED", "Ghi nhận yêu cầu của khách",
            "EXPORTED", "Xuất dữ liệu khách",
            "ERASED", "Xóa dữ liệu khách");

    private static final Map<String, String> BOT_RESULT = Map.of(
            "RECEIVED", "Vừa nhận",
            "SELF", "Tin của chính trợ lý AI",
            "HUMAN_ACTIVE", "Mẹ đang trả lời, trợ lý AI im lặng",
            "BOT_OFF", "Trợ lý AI đang tắt",
            "RATE_LIMITED", "Trả lời quá dày, bỏ qua",
            "IGNORED", "Không hướng tới cửa hàng, bỏ qua",
            "ANSWERED", "Đã trả lời bằng câu hỏi thường gặp",
            "HANDOFF", "Đã báo mẹ xử lý",
            "DUPLICATE", "Tin trùng, bỏ qua",
            "UNKNOWN_GROUP", "Nhóm không có trong hệ thống");

    private static final Map<String, String> SENDER = Map.of(
            "CUSTOMER", "Khách",
            "OWNER", "Mẹ (chủ cửa hàng)",
            "BOT", "Trợ lý AI");

    private static String pick(Map<String, String> map, String code) {
        if (code == null) {
            return "";
        }
        return map.getOrDefault(code, "Khác");
    }

    public static String channelLabel(String code) {
        return pick(CHANNEL, code);
    }

    public String channel(String code) {
        return channelLabel(code);
    }

    public String contactEvent(String code) {
        return pick(CONTACT_EVENT, code);
    }

    public String botResult(String code) {
        return pick(BOT_RESULT, code);
    }

    public String sender(String code) {
        return pick(SENDER, code);
    }

    /**
     * Người thao tác hiển thị cho bố mẹ: cửa hàng dùng MỘT tài khoản đăng nhập chung (quyết định D17), nên "admin" là
     * "Bố/Mẹ"; nếu sau này có thêm tài khoản thì thêm tên vào đây.
     */
    public String person(String username) {
        if (username == null || username.isBlank()) {
            return "";
        }
        return switch (username) {
            case "admin" -> "Bố/Mẹ";
            case "system" -> "Hệ thống";
            default -> username;
        };
    }

    /** Tên ý định bằng tiếng Việt; mã lạ thì trả về rỗng. */
    public String intent(String code) {
        if (code == null) {
            return "";
        }
        try {
            return vn.shop.crm.bot.Intent.valueOf(code).label();
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    /** Bằng chứng duyệt dạng "VIETTEL_POST:123" hiển thị thành "Viettel Post, vận đơn 123". */
    public String evidence(String ref) {
        if (ref == null || ref.isBlank()) {
            return "";
        }
        int i = ref.indexOf(':');
        if (i < 0) {
            return ref;
        }
        String carrier = ref.substring(0, i);
        String tracking = ref.substring(i + 1);
        try {
            carrier = vn.shop.crm.shipping.Carrier.valueOf(carrier).label();
        } catch (IllegalArgumentException e) {
            // giữ nguyên tên hãng lạ
        }
        return carrier + ", vận đơn " + tracking;
    }

    /** Số điện thoại dạng dễ đọc (0912 345 678) từ số lưu dạng quốc tế. */
    public String phoneDisplay(String e164) {
        return e164 == null ? "" : PhoneNormalizer.toDisplay(e164);
    }
}
