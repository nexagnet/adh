package vn.shop.crm.common;

/** Lỗi nghiệp vụ khi nhập file, thông điệp viết bằng tiếng Việt để hiển thị thẳng cho người dùng. */
public class ImportException extends RuntimeException {
    public ImportException(String message) {
        super(message);
    }
}
