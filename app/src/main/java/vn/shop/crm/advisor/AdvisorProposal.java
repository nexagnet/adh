package vn.shop.crm.advisor;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * Một đề xuất chiến dịch của AI đã qua kiểm cứng. Chỉ là đề xuất để người dùng xem: chưa là chiến dịch, chưa gửi gì.
 * Chữ ({@code title}, {@code messageAngle}, {@code reasons}, {@code risks}) chỉ để người dùng đọc, không bao giờ gửi cho khách.
 *
 * @param confidence        độ tin cậy sau khi mã đã chặn trần theo lượng dữ liệu (0..1)
 * @param confidenceCapped  true nếu AI tự nhận cao hơn và mã đã hạ xuống
 */
public record AdvisorProposal(String title, String area, int batchSize, LocalDate startOn, LocalDate endOn,
                              LocalTime windowFrom, LocalTime windowTo, int dailyCap, String messageAngle,
                              List<String> reasons, List<String> risks, double confidence, boolean confidenceCapped) {
}
