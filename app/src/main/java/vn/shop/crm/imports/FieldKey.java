package vn.shop.crm.imports;

import vn.shop.crm.shipping.StatusKey;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Các trường dữ liệu cần lấy từ file của hãng, kèm từ khóa để đoán cột. */
public enum FieldKey {
    TRACKING_NO("Mã vận đơn", true,
            List.of("ma van don", "ma vd", "bill code", "billcode", "tracking number", "tracking no", "tracking", "van don")),
    ORDER_REF("Mã đơn hàng của shop (để gộp đơn tách kiện)", false,
            List.of("ma don hang", "ma don", "order number", "order code", "order id", "don hang")),
    RECEIVER_PHONE("SĐT người nhận", true,
            List.of("dt nhan", "sdt nguoi nhan", "so dien thoai nguoi nhan", "dien thoai nguoi nhan", "sdt nhan",
                    "receiver phone", "receiver mobile", "receiver tel", "so dien thoai nhan", "so dien thoai",
                    "dien thoai", "sdt", "phone")),
    RECEIVER_NAME("Tên người nhận", false,
            List.of("ten nguoi nhan", "ho ten nguoi nhan", "receiver name", "nguoi nhan", "ten khach hang")),
    RECEIVER_ADDRESS("Địa chỉ người nhận", false,
            List.of("dia chi nguoi nhan", "dia chi nhan", "receiver address", "dia chi giao hang", "dia chi")),
    ADMIN_AREA("Địa chỉ hành chính (xã, huyện, tỉnh — nếu hãng có cột riêng)", false,
            List.of("dia chi hanh chinh", "don vi hanh chinh", "tinh thanh", "tinh/thanh pho")),
    STATUS("Trạng thái đơn", true,
            List.of("trang thai don hang", "trang thai van don", "trang thai", "status", "tinh trang")),
    CREATED_ON("Ngày tạo đơn", false,
            List.of("ngay tao", "ngay gui", "thoi gian tao", "created", "create time")),
    DELIVERED_ON("Ngày giao", false,
            List.of("ngay giao", "ngay phat", "thoi gian giao", "delivered", "delivery time")),
    STATUS_UPDATED_ON("Thời gian cập nhật trạng thái", false,
            List.of("thoi gian cap nhat trang thai", "ngay cap nhat trang thai", "status update time", "status updated at")),
    GOODS("Nội dung hàng hóa (đơn này mua gì)", false,
            List.of("noi dung hang hoa", "ten hang hoa", "noi dung hang", "ten hang", "mo ta hang hoa", "ten san pham",
                    "goods name", "goods", "product name", "item name"));

    private final String label;
    private final boolean required;
    private final List<String> synonyms;

    FieldKey(String label, boolean required, List<String> synonyms) {
        this.label = label;
        this.required = required;
        this.synonyms = synonyms;
    }

    public String label() {
        return label;
    }

    public boolean required() {
        return required;
    }

    /** Đoán cột theo tiêu đề; chỉ gợi ý, người dùng luôn xác nhận được ở bước ánh xạ. */
    public static Map<FieldKey, String> guess(List<String> headers) {
        Map<FieldKey, String> result = new EnumMap<>(FieldKey.class);
        for (FieldKey field : values()) {
            String found = null;
            // ưu tiên khớp chính xác theo thứ tự từ khóa, sau đó mới tới "chứa từ khóa"
            for (String syn : field.synonyms) {
                found = headers.stream().filter(h -> StatusKey.of(h).equals(syn)).findFirst().orElse(null);
                if (found != null) {
                    break;
                }
            }
            if (found == null) {
                for (String syn : field.synonyms) {
                    found = headers.stream()
                            .filter(h -> StatusKey.of(h).contains(syn))
                            .filter(h -> !isSenderColumn(field, h))
                            .filter(h -> !(field == RECEIVER_ADDRESS && StatusKey.of(h).contains("hanh chinh")))
                            .findFirst().orElse(null);
                    if (found != null) {
                        break;
                    }
                }
            }
            if (found != null) {
                result.put(field, found);
            }
        }
        return result;
    }

    private static boolean isSenderColumn(FieldKey field, String header) {
        if (field != RECEIVER_PHONE && field != RECEIVER_NAME && field != RECEIVER_ADDRESS && field != ADMIN_AREA) {
            return false;
        }
        String key = StatusKey.of(header);
        if (key.contains("nguoi gui") || key.contains("sender")) {
            return true;
        }
        // "Tỉnh/Thành phố gửi", "Quận/Huyện gửi", "Địa chỉ gửi": nơi gửi hàng (cửa hàng), không phải nơi nhận.
        return List.of(key.split(" ")).contains("gui");
    }
}
