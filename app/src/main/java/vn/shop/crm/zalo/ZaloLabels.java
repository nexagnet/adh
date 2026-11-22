package vn.shop.crm.zalo;

import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Nhãn tiếng Việt cho mã kỹ thuật của bộ nối Zalo (trạng thái, lý do dừng, hành động nhật ký). Giao diện không bao giờ
 * hiện mã gốc. Dùng trong mẫu trang qua {@code @zaloLabels}.
 */
@Component("zaloLabels")
public class ZaloLabels {

    private static final Map<String, String> BRIDGE_STATE = Map.of(
            "DISABLED", "Chưa bật",
            "UNREACHABLE", "Không gọi được tới cầu nối",
            "LOGIN_REQUIRED", "Cần quét mã QR để đăng nhập",
            "CONNECTING", "Đang nối lại",
            "LISTENING", "Đang nghe",
            "TRIPPED", "Cầu nối tự dừng",
            "STOPPED", "Đã dừng tay");

    private static final Map<String, String> REASON = Map.ofEntries(
            Map.entry("DUNG_TAY", "Có người bấm dừng"),
            Map.entry("DANG_XUAT", "Đã đăng xuất tài khoản phụ"),
            Map.entry("ACCOUNT_MISMATCH", "Đăng nhập nhầm tài khoản (không phải tài khoản phụ đã khai báo)"),
            Map.entry("SESSION_LOST", "Phiên Zalo hết hạn hoặc bị đăng xuất"),
            Map.entry("RESTRICTED", "Zalo yêu cầu xác minh hoặc báo hạn chế"),
            Map.entry("SYSTEM_ERROR", "Lỗi lạ hoặc không biết kết quả"),
            Map.entry("DUPLICATE_CONNECTION", "Tài khoản phụ đang mở Zalo ở nơi khác"),
            Map.entry("KICKED", "Zalo ngắt kết nối"),
            Map.entry("LIMIT", "Chạm trần số người mỗi ngày của cầu nối"),
            Map.entry("WINDOW", "Có yêu cầu thêm ngoài khung giờ"),
            Map.entry("RECONNECT_FAILED", "Nối lại nhiều lần không được"),
            Map.entry("LEDGER_CORRUPT", "Sổ đếm hạn mức bị hỏng"),
            Map.entry("INTERRUPTED", "Cầu nối sập đúng lúc đang thêm người, không biết kết quả"),
            Map.entry("CRASH_LOOP", "Cầu nối khởi động lại liên tục"),
            Map.entry("STATE_CORRUPT", "Tệp trạng thái cầu nối bị hỏng"),
            Map.entry("TRIPPED", "Cầu nối tự dừng"));

    private static final Map<String, String> ACTION = Map.of(
            ZaloAutoSettings.A_LISTEN, "Nhận sự kiện kết bạn",
            ZaloAutoSettings.A_MATCH, "Ghép khách",
            ZaloAutoSettings.A_APPROVE, "Duyệt, thử lại, tự xử lý",
            ZaloAutoSettings.A_ADD, "Thêm vào nhóm",
            ZaloAutoSettings.A_STOP, "Dừng",
            ZaloAutoSettings.A_REOPEN, "Mở lại",
            ZaloAutoSettings.A_CONFIG, "Đổi cấu hình",
            ZaloAutoSettings.A_LOGIN, "Đăng nhập QR",
            ZaloAutoSettings.A_LOGOUT, "Đăng xuất");

    private static final Map<String, String> RESULT = Map.ofEntries(
            Map.entry("DA_THANH_BAN", "Đã thành bạn"),
            Map.entry("KHONG_CO_SO", "Không có số, chuyển hàng đợi tay"),
            Map.entry("NGUOI_LA", "Người lạ, không lưu"),
            Map.entry("KHONG_DU_DIEU_KIEN", "Khách không đủ điều kiện"),
            Map.entry("TRUNG_UID", "Mã Zalo đã gắn với khách khác"),
            Map.entry("KHOP", "Khớp một lời mời"),
            Map.entry("GHEP_TAY", "Người ghép tay"),
            Map.entry("BO_QUA", "Bỏ qua"),
            Map.entry("THU_LAI", "Cho thử lại"),
            Map.entry("TU_XU_LY", "Người tự thêm tay"),
            Map.entry("DANG_GOI", "Đang gọi Zalo (chưa có kết quả)"),
            Map.entry("OK", "Thành công"),
            Map.entry("TRIPPED", "Đã dừng"),
            Map.entry("BAT_DAU", "Bắt đầu"),
            Map.entry("XONG", "Xong"),
            Map.entry("SAI_TAI_KHOAN", "Sai tài khoản"),
            Map.entry("YEU_CAU", "Yêu cầu"));

    private static final Map<String, String> ADD_STATE = Map.of(
            "WAITING_APPROVAL", "Chờ mẹ duyệt",
            "QUEUED", "Đã duyệt, chờ tới lượt trong hạn mức",
            "FAILED", "Lỗi, cần người xem");

    private static final Map<String, String> LOGIN = Map.of(
            "IDLE", "Chưa bắt đầu",
            "WAITING_SCAN", "Chờ quét mã QR",
            "SCANNED", "Đã quét, hãy bấm xác nhận trên điện thoại",
            "DONE", "Đăng nhập xong",
            "EXPIRED", "Mã QR đã hết hạn, hãy bắt đầu lại",
            "FAILED", "Đăng nhập không thành công, hãy bắt đầu lại",
            "ACCOUNT_MISMATCH", "Sai tài khoản: đã từ chối và đăng xuất tài khoản vừa quét");

    public String bridgeState(String code) {
        return BRIDGE_STATE.getOrDefault(code, "Chưa rõ");
    }

    /** Lý do dừng; mã có đuôi ({@code SYSTEM_ERROR:Z123}, {@code BRIDGE_LIMIT}) được bóc để lấy nhãn. */
    public String reason(String code) {
        if (code == null || code.isBlank()) {
            return "";
        }
        String c = code.startsWith("BRIDGE_") ? code.substring("BRIDGE_".length()) : code;
        int colon = c.indexOf(':');
        String head = colon > 0 ? c.substring(0, colon) : c;
        return REASON.getOrDefault(head, "Lý do chưa có nhãn");
    }

    public String action(String code) {
        return ACTION.getOrDefault(code, "Khác");
    }

    public String result(String code) {
        if (code == null) {
            return "";
        }
        String head = code.startsWith("NAC_") ? "Đổi nấc" : code.startsWith("HAN_MUC") ? "Đổi hạn mức" : null;
        return head != null ? head : RESULT.getOrDefault(code, "Khác");
    }

    public String addState(String code) {
        return ADD_STATE.getOrDefault(code, "");
    }

    public String loginState(String code) {
        return LOGIN.getOrDefault(code, "Chưa rõ");
    }

    /** Nhãn lỗi của một lần thêm (mã ngắn do cầu nối trả). */
    public String addCode(String code) {
        if (code == null || code.isBlank()) {
            return "";
        }
        return switch (code) {
            case "KHONG_DU_DIEU_KIEN" -> "Khách không còn đủ điều kiện";
            case "LOI_NGUOI" -> "Zalo không thêm được người này (đã trong nhóm, nhóm đầy hoặc chỉ quản trị viên được thêm)";
            default -> "Zalo báo lỗi, xem nhật ký";
        };
    }
}
