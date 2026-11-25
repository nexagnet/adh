package vn.shop.crm.advisor;

import vn.shop.crm.contact.CustomerFilter;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * Một đề xuất chiến dịch của AI đã qua kiểm cứng. Chỉ là đề xuất để người dùng xem: chưa là chiến dịch, chưa gửi gì.
 * Chữ ({@code title}, {@code messageAngle}, {@code reasons}, {@code risks}) chỉ để người dùng đọc, không bao giờ gửi cho khách.
 *
 * @param confidence        độ tin cậy sau khi mã đã chặn trần theo lượng dữ liệu (0..1)
 * @param confidenceCapped  true nếu AI tự nhận cao hơn và mã đã hạ xuống
 * @param weekdays          ngày trong tuần được gửi (bit 0 = thứ Hai ... bit 6 = Chủ nhật), null = mọi ngày
 * @param pickOrder         thứ tự lấy khách: OLDEST_APPROVED, NEWEST_PURCHASE hoặc OLDEST_PURCHASE
 * @param minDays           lần mua gần nhất cách đây ít nhất chừng này ngày (null = không giới hạn)
 * @param maxDays           lần mua gần nhất cách đây nhiều nhất chừng này ngày (null = không giới hạn)
 * @param productGroup      chỉ mời khách đã mua nhóm hàng này (AO_DIEU_HOA, PIN, PHU_KIEN_KHAC), null = mọi nhóm
 * @param filter            bộ lọc khách đầy đủ (cùng khuôn với form của người, PR 4); {@link CustomerFilter#NONE} nếu không có
 */
public record AdvisorProposal(String title, String area, int batchSize, LocalDate startOn, LocalDate endOn,
                              LocalTime windowFrom, LocalTime windowTo, int dailyCap, String messageAngle,
                              List<String> reasons, List<String> risks, double confidence, boolean confidenceCapped,
                              Integer weekdays, String pickOrder, Integer minDays, Integer maxDays, String productGroup,
                              CustomerFilter filter) {

    public AdvisorProposal {
        filter = filter == null ? CustomerFilter.NONE : filter;
    }


    /** Bản không có núm chọn khách và ngày gửi: theo mặc định hệ thống (mọi ngày, duyệt sớm nhất, không lọc độ mới lần mua). */
    public AdvisorProposal(String title, String area, int batchSize, LocalDate startOn, LocalDate endOn,
                           LocalTime windowFrom, LocalTime windowTo, int dailyCap, String messageAngle,
                           List<String> reasons, List<String> risks, double confidence, boolean confidenceCapped) {
        this(title, area, batchSize, startOn, endOn, windowFrom, windowTo, dailyCap, messageAngle, reasons, risks, confidence,
                confidenceCapped, null, "OLDEST_APPROVED", null, null, null, CustomerFilter.NONE);
    }

}
