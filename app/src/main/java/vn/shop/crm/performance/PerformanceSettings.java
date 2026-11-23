package vn.shop.crm.performance;

import java.math.BigDecimal;

/**
 * Các giá trị chủ shop nhập để tính "ưu đãi nhóm có lời hơn mua qua TikTok không".
 *
 * @param discountPercent  ưu đãi khi mua qua nhóm so với giá trên TikTok (mặc định 10, đúng như Tin 1)
 * @param freeShip         đơn qua nhóm miễn ship, tức shop chịu cước vận chuyển
 * @param tiktokFeePercent tổng các khoản TikTok thu trên một đơn (hoa hồng, phí giao dịch...) tính theo % giá bán; null = chưa nhập
 * @param groupOrderPrefix tiền tố mã đơn hàng của đơn bán qua nhóm (ví dụ NHOM); rỗng = chưa phân biệt được, coi mọi đơn
 *                         giao thành công SAU KHI khách vào nhóm là đơn qua nhóm (số ước tính)
 */
public record PerformanceSettings(BigDecimal discountPercent, boolean freeShip, BigDecimal tiktokFeePercent,
                                  String groupOrderPrefix) {

    public static final BigDecimal DEFAULT_DISCOUNT = BigDecimal.TEN;

    public static PerformanceSettings defaults() {
        return new PerformanceSettings(DEFAULT_DISCOUNT, true, null, "");
    }

    public boolean hasPrefix() {
        return groupOrderPrefix != null && !groupOrderPrefix.isBlank();
    }
}
