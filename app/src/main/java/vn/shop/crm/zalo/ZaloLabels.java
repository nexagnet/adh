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
            Map.entry("NHIEU_LOI_NGUOI", "Sáu trong tám lần thêm gần nhất đều lỗi riêng người, lẫn nhiều nhóm (nghi Zalo đang hạn chế thêm người)"),
            Map.entry("NHIEU_LOI_NHOM", "Ba lần thêm liền đều báo lỗi cả nhóm (nhiều nhóm hỏng cùng lúc, nghi tài khoản phụ mất quyền)"),
            Map.entry("GROUP_ERROR", "Zalo báo lỗi cả nhóm (nhóm không còn hoặc tài khoản phụ không đủ quyền): nhóm đã được tạm dừng"),
            Map.entry("TIN_INTERRUPTED", "Sập đúng lúc đang gửi tin, không biết tin đã đi chưa"),
            Map.entry("NHIEU_KHONG_TIM_THAY", "Năm lần gửi liền đều không tìm thấy người nhận trên Zalo (nghi Zalo đang chặn tra số)"),
            Map.entry("NHIEU_LOI_NGUOI_TIN", "Ba lần gửi tin liền đều bị Zalo từ chối cho từng người"),
            Map.entry("MSG_LIMIT", "Chạm trần số tin mỗi ngày của cầu nối"),
            Map.entry("NOT_ACTIVE", "Cầu nối đang dừng hoặc chưa đăng nhập"),
            Map.entry("LOGIN_REQUIRED", "Cầu nối cần quét lại mã QR"),
            Map.entry("TRIPPED", "Cầu nối tự dừng"),
            Map.entry("TAO_NHOM_INTERRUPTED", "Sập đúng lúc đang tạo nhóm, không biết nhóm đã tạo chưa"),
            Map.entry("GROUP_LIMIT", "Chạm trần số nhóm mới mỗi ngày của cầu nối"));

    private static final Map<String, String> ACTION = Map.ofEntries(
            Map.entry(ZaloAutoSettings.A_LISTEN, "Nhận sự kiện kết bạn"),
            Map.entry(ZaloAutoSettings.A_MATCH, "Ghép khách"),
            Map.entry(ZaloAutoSettings.A_APPROVE, "Duyệt, thử lại, tự xử lý"),
            Map.entry(ZaloAutoSettings.A_ADD, "Thêm vào nhóm"),
            Map.entry(ZaloAutoSettings.A_STOP, "Dừng"),
            Map.entry(ZaloAutoSettings.A_REOPEN, "Mở lại"),
            Map.entry(ZaloAutoSettings.A_CONFIG, "Đổi cấu hình"),
            Map.entry(ZaloAutoSettings.A_LOGIN, "Đăng nhập QR"),
            Map.entry(ZaloAutoSettings.A_LOGOUT, "Đăng xuất"),
            Map.entry(ZaloGroupSettings.A_GROUP, "Tạo nhóm"),
            Map.entry(ZaloInboxService.A_INBOX, "Đọc tin riêng của khách (trợ lý AI)"));

    private static final Map<String, String> RESULT = Map.ofEntries(
            Map.entry("DA_THANH_BAN", "Đã thành bạn"),
            Map.entry("AI_GHI_TU_CHOI", "Trợ lý AI ghi sổ khách từ chối"),
            Map.entry("AI_TAM_DUNG", "Tạm dừng tin cho khách, chờ mẹ xem"),
            Map.entry("AI_BO_QUA", "Không phải từ chối, bỏ qua"),
            Map.entry("KHONG_PHAI_KHACH", "Không phải khách đang được mời, bỏ qua"),
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
            Map.entry("DOI_TUONG_THU", "Số của chiến dịch thử (không ghép khách thật)"),
            Map.entry("YEU_CAU", "Yêu cầu"),
            Map.entry("DE_XUAT", "Đề xuất tạo nhóm, chờ mẹ duyệt"),
            Map.entry("DE_XUAT_TU_DONG", "Đề xuất tạo nhóm (nấc tự động)"),
            Map.entry("DUYET", "Mẹ duyệt tạo nhóm"),
            Map.entry("TU_CHOI", "Mẹ từ chối đề xuất"),
            Map.entry("DA_KIEM_TRA", "Người đã kiểm tra trên Zalo"),
            Map.entry("HET_HAN", "Đề xuất hết hạn (không còn đủ khách)"),
            Map.entry("PERSON_ERROR", "Đầu vào bị từ chối, chưa tạo"),
            Map.entry("RESTRICTED", "Zalo hạn chế hoặc chạm trần"),
            Map.entry("SESSION_LOST", "Mất phiên đăng nhập"),
            Map.entry("SYSTEM_ERROR", "Lỗi lạ hoặc không biết kết quả"));

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

    private static final Map<String, String> HEALTH_TITLE = Map.of(
            ZaloHealthService.K_LOGIN, "Tài khoản phụ bị đăng xuất hoặc phiên Zalo hết hạn",
            ZaloHealthService.K_RESTRICTED, "Zalo có dấu hiệu hạn chế tài khoản phụ",
            ZaloHealthService.K_STRANGE, "Lỗi lạ hoặc không biết tin đã gửi chưa",
            ZaloHealthService.K_ACCOUNT, "Tài khoản đăng nhập không đúng, hoặc Zalo đang mở ở nơi khác",
            ZaloHealthService.K_STOPPED, "Bộ nối Zalo đã tự dừng",
            ZaloHealthService.K_FAIL_RATE, "Nhiều tin gửi bị lỗi trong 24 giờ qua",
            ZaloHealthService.K_BRIDGE_DOWN, "Không gọi được tới cầu nối Zalo hơn 15 phút",
            ZaloHealthService.K_REFUSAL, "Có chiến dịch bị tự dừng vì nhiều khách từ chối");

    private static final Map<String, String> HEALTH_ADVICE = Map.of(
            ZaloHealthService.K_LOGIN, "Bấm Quét mã QR ở dưới rồi quét bằng điện thoại của tài khoản phụ. Nếu Zalo báo tài khoản bị khóa thì dừng lại và báo cho Việt.",
            ZaloHealthService.K_RESTRICTED, "Bộ nối đã tự dừng. Đừng bấm Mở lại ngay. Mở Zalo trên điện thoại của tài khoản phụ xem có yêu cầu xác minh hay thông báo hạn chế không, chờ ít nhất một ngày rồi mở lại với hạn mức thấp hơn.",
            ZaloHealthService.K_STRANGE, "Hệ thống không tự gửi lại. Xem nhật ký và kiểm tra trên Zalo của tài khoản phụ xem tin đã đi chưa rồi mới bấm Mở lại. Mã ghi bên cạnh gửi cho Việt để biết đây là lỗi gì.",
            ZaloHealthService.K_ACCOUNT, "Đăng xuất Zalo của tài khoản phụ ở máy khác, hoặc quét lại mã QR bằng đúng tài khoản phụ.",
            ZaloHealthService.K_STOPPED, "Đọc nhật ký bên dưới để biết vì sao, xử lý rồi bấm Mở lại.",
            ZaloHealthService.K_FAIL_RATE, "Chưa dừng, nhưng Zalo đang từ chối nhiều tin. Nên giảm hạn mức mỗi ngày hoặc bấm Dừng ngay, rồi xem nhật ký gửi.",
            ZaloHealthService.K_BRIDGE_DOWN, "Nhờ Việt kiểm tra máy chủ: cầu nối có thể đang tắt hoặc máy hết bộ nhớ.",
            ZaloHealthService.K_REFUSAL, "Mở chiến dịch bị dừng để xem lý do. Đọc lại nội dung tin, xem có làm khách khó chịu không. Chỉ duyệt lại khi đã sửa; sau khi duyệt lại, hệ thống đếm tỷ lệ từ chối từ đầu.");

    public String healthTitle(String kind) {
        return HEALTH_TITLE.getOrDefault(kind, "Có dấu hiệu bất thường ở tài khoản Zalo phụ");
    }

    public String healthAdvice(String kind) {
        return HEALTH_ADVICE.getOrDefault(kind, "Xem nhật ký bên dưới.");
    }

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
        String head = code.startsWith("NAC_") ? "Đổi nấc" : code.startsWith("HAN_MUC") ? "Đổi hạn mức"
                : code.startsWith("TAO_NHOM_NAC_") ? "Đổi nấc tự tạo nhóm" : code.startsWith("TAO_NHOM_HAN_MUC") ? "Đổi hạn mức tạo nhóm"
                : code.equals("TIN_BAT") ? "Bật tự gửi tin" : code.equals("TIN_TAT") ? "Tắt tự gửi tin"
                : code.startsWith("DUYET_CHIEN_DICH") ? "Duyệt chiến dịch cho tự gửi" : code.startsWith("BO_DUYET_CHIEN_DICH") ? "Bỏ duyệt chiến dịch" : null;
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
            case "DANG_GOI" -> "Đang gọi Zalo thì bị gián đoạn, chưa biết đã thêm chưa: hãy xem nhóm trên Zalo rồi chọn Mẹ tự thêm tay hoặc Cho thử lại";
            case "LOI_NGUOI" -> "Zalo không thêm được người này (đã trong nhóm, nhóm đầy hoặc chỉ quản trị viên được thêm)";
            default -> "Zalo báo lỗi, xem nhật ký";
        };
    }
}
