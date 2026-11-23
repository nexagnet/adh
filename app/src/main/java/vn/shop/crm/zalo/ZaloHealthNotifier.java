package vn.shop.crm.zalo;

/**
 * Kênh báo ngoài (tùy chọn, mặc định không có) để chủ shop biết ngay cả khi không mở trang quản trị. Nội dung báo chỉ mô tả
 * sự cố bằng chữ, KHÔNG BAO GIỜ chứa số điện thoại, tên khách, mã Zalo, khóa hay đường dẫn.
 */
public interface ZaloHealthNotifier {

    /** Gửi một tin báo. Không được ném lỗi ra ngoài; trả false nếu không gửi được. */
    boolean send(String text);
}
