package vn.shop.crm.geo;

/**
 * Một tỉnh/thành. Tọa độ là tọa độ thành phố tỉnh lỵ (làm tròn), dùng để vẽ bản đồ và hỏi thời tiết: chỉ tọa độ
 * này được gửi ra ngoài, không bao giờ gửi dữ liệu khách.
 */
public record Province(String code, String name, SubRegion sub, double lat, double lon) {
    public Region region() {
        return sub.region();
    }
}
