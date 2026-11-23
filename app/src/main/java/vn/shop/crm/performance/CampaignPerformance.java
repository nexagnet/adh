package vn.shop.crm.performance;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Hiệu quả một chiến dịch: phễu mời, kết bạn, vào nhóm, mua trực tiếp, doanh thu. Chỉ có SỐ ĐẾM và TỔNG TIỀN
 * (không có tên, số điện thoại hay mã khách nào), nên dùng được cho báo cáo xuất và cho AI đề xuất chiến dịch.
 *
 * @param orders        số lần mua giao thành công sau khi khách vào nhóm (một mã đơn bán hàng là một lần mua)
 * @param settledOrders trong số đó, số lần mua đã có dòng đối soát (có tiền thu hộ thật)
 * @param revenue       tổng tiền thu hộ đã đối soát của các lần mua đó (chưa đối soát thì chưa tính)
 * @param carrierCost   cước vận chuyển và phí thu hộ của các lần mua đó
 * @param masked        true khi chiến dịch quá ít người vào nhóm: các số mua, tiền và so sánh bị ẩn để không suy ngược ra từng khách
 */
public record CampaignPerformance(long id, String name, String scopeLabel, LocalDate createdOn,
                                  long invited, long friended, long joined,
                                  long buyers, long orders, long settledOrders,
                                  BigDecimal revenue, BigDecimal carrierCost, Economics economics,
                                  Cohort joinedCohort, Cohort notJoinedCohort, boolean masked) {

    /** Nhóm khách để so sánh mua lại: {@code customers} người đủ 30 ngày quan sát, {@code bought} người mua lại trong 30 ngày đó. */
    public record Cohort(long customers, long bought) {
        public static final Cohort EMPTY = new Cohort(0, 0);

        /** Tỉ lệ mua lại, null khi chưa có ai đủ thời gian quan sát. */
        public Double rate() {
            return customers == 0 ? null : (double) bought / customers;
        }

        public Cohort plus(Cohort o) {
            return new Cohort(customers + o.customers, bought + o.bought);
        }
    }

    public long ordersWithoutMoney() {
        return orders - settledOrders;
    }

    /** Phần trăm so với bước trước của phễu, null khi bước trước bằng 0. */
    public static Double percent(long part, long whole) {
        return whole == 0 ? null : 100.0 * part / whole;
    }

    public Double friendedPercent() {
        return percent(friended, invited);
    }

    public Double joinedPercent() {
        return percent(joined, invited);
    }

    public Double buyersPercent() {
        return percent(buyers, joined);
    }

    /** Bản đã ẩn các số có thể suy ngược ra từng khách (chiến dịch có ít hơn {@link PerformanceService#MIN_CELL} người vào nhóm). */
    CampaignPerformance mask() {
        return new CampaignPerformance(id, name, scopeLabel, createdOn, invited, friended, joined,
                0, 0, 0, BigDecimal.ZERO, BigDecimal.ZERO, null, Cohort.EMPTY, Cohort.EMPTY, true);
    }
}
