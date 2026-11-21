package vn.shop.crm.contact;

/** Trạng thái cơ sở liên hệ của một khách cho một mục đích. Không có dòng nào = không được liên hệ. */
public enum ContactStatus {
    ELIGIBLE("Đã duyệt, được mời"),
    BLOCKED("Bị chặn (thủ công)"),
    OPTED_OUT("Đã từ chối nhận liên hệ");

    private final String label;

    ContactStatus(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
