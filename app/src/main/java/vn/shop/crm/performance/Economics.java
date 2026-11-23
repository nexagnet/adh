package vn.shop.crm.performance;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * So sánh một đơn bán qua nhóm với cùng đơn đó nếu bán trên TikTok. Hàm thuần, không đụng cơ sở dữ liệu.
 *
 * <p>Hệ thống KHÔNG có dữ liệu đơn TikTok (ngoài phạm vi, BR-01) nên không đo được doanh thu TikTok thật. Cách tính:
 * giá trên TikTok của cùng đơn = doanh thu qua nhóm / (1 - ưu đãi). Từ đó:
 * <ul>
 *   <li>chi phí ưu đãi = giá TikTok - doanh thu qua nhóm;</li>
 *   <li>chi phí ship = cước J&amp;T của các đơn đó (chỉ khi miễn ship);</li>
 *   <li>mức phí TikTok hòa vốn = (chi phí ưu đãi + chi phí ship) / giá TikTok: nếu TikTok thu nhiều hơn mức này
 *       thì bán qua nhóm có lời hơn;</li>
 *   <li>nếu đã nhập phí TikTok: lời so với TikTok = phí TikTok tránh được - chi phí ưu đãi - chi phí ship.</li>
 * </ul>
 * Đây là so sánh trên CÙNG một đơn: không tính việc khách có mua lại nếu không có nhóm hay không (xem nhóm so sánh mua lại).
 */
public record Economics(BigDecimal revenue, BigDecimal tiktokPrice, BigDecimal discountCost, BigDecimal shipCost,
                        BigDecimal breakEvenFeePercent, BigDecimal tiktokFeeAvoided, BigDecimal netGain) {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    public static Economics of(BigDecimal revenue, BigDecimal carrierCost, PerformanceSettings s) {
        BigDecimal rev = revenue == null ? BigDecimal.ZERO : revenue;
        BigDecimal ship = s.freeShip() && carrierCost != null ? carrierCost : BigDecimal.ZERO;
        BigDecimal discount = s.discountPercent() == null ? BigDecimal.ZERO : s.discountPercent();
        if (rev.signum() <= 0 || discount.signum() < 0 || discount.compareTo(BigDecimal.valueOf(90)) > 0) {
            return new Economics(rev, rev, BigDecimal.ZERO, ship, null, null, null);
        }
        BigDecimal price = rev.multiply(HUNDRED).divide(HUNDRED.subtract(discount), 0, RoundingMode.HALF_UP);
        BigDecimal discountCost = price.subtract(rev);
        BigDecimal breakEven = discountCost.add(ship).multiply(HUNDRED).divide(price, 1, RoundingMode.HALF_UP);
        BigDecimal avoided = null;
        BigDecimal net = null;
        if (s.tiktokFeePercent() != null) {
            avoided = price.multiply(s.tiktokFeePercent()).divide(HUNDRED, 0, RoundingMode.HALF_UP);
            net = avoided.subtract(discountCost).subtract(ship);
        }
        return new Economics(rev, price, discountCost, ship, breakEven, avoided, net);
    }
}
