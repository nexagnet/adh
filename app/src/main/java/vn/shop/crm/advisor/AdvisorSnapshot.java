package vn.shop.crm.advisor;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

/**
 * Số liệu TỔNG HỢP và ẨN DANH để đưa cho AI đề xuất chiến dịch (D21, docs/THIET-KE-AI-DE-XUAT-CHIEN-DICH.md).
 *
 * <p>Cố ý chỉ có số, mã khu vực và nhãn do mã đặt: KHÔNG có chuỗi tự do nào lấy từ cơ sở dữ liệu (tên, số điện thoại, địa chỉ,
 * mã Zalo, nội dung tin, tên hàng), nên không có đường nào để dữ liệu cá nhân lọt vào. Khu vực có dưới
 * {@link AdvisorSnapshotService#MIN_CELL} khách bị ẩn để không suy ngược ra từng người.
 */
public record AdvisorSnapshot(
        String generatedOn,
        Calendar calendar,
        Limits limits,
        Overall overall,
        List<Area> areas,
        List<Slot> sendSlots) {

    private static final JsonMapper JSON = JsonMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).build();

    /** Mùa theo miền (bảng cố định trong mã) và các dịp sắp tới. */
    public record Calendar(int month, List<Season> seasons, List<Occasion> upcoming) {
    }

    public record Season(String region, String label) {
    }

    public record Occasion(String name, int inDays) {
    }

    /** Giới hạn gửi hiện hành: đề xuất không được vượt. */
    public record Limits(int dailyCustomers, int gapSeconds, String windowFrom, String windowTo, int maxCampaignSize) {
    }

    public record Overall(long customers, long oneTime, long eligible, long awaitingApproval, long unknownRegion,
                          long hiddenSmallAreas, Funnel funnel) {
    }

    /** Phễu lời mời: đã nhắn, đồng ý kết bạn, đã vào nhóm, từ chối sau khi được nhắn. */
    public record Funnel(long sent, long friended, long joined, long optedOut) {
    }

    /** Thời gian từ lần mua gần nhất của khách mua một lần, theo ngày. */
    public record Recency(long upTo30, long from31to90, long from91to180, long over180, long unknown) {
    }

    /** Dự báo 48 giờ tới, trung bình theo số khách của từng tỉnh trong vùng. {@code rainyPercent}: % khách ở tỉnh có mưa đáng kể. */
    public record Weather(double avgMinTemp, double avgMaxTemp, int rainyPercent, int coveragePercent) {
    }

    public record ProvinceInfo(String code, String name, long customers, long eligible, Integer minTemp, Integer maxTemp) {
    }

    /** Một vùng (đủ khách để không suy ngược ra từng người). {@code code} dùng được làm khu vực của chiến dịch. */
    public record Area(String code, String label, String region, long customers, long oneTime, long eligible,
                       long awaitingApproval, long newLast30Days, Recency recency, Funnel funnel, Weather weather,
                       List<ProvinceInfo> topProvinces) {
    }

    /** Khung giờ nhắn (giờ Việt Nam): số tin đã nhắn và số khách kết bạn từ các tin đó. */
    public record Slot(String label, long sent, long friended) {
    }

    /** Chuỗi JSON đưa cho AI (khóa dạng snake_case). */
    public String toJson() {
        return JSON.writeValueAsString(this);
    }
}
