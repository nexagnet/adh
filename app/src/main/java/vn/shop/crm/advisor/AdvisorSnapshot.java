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
        List<Slot> sendSlots,
        List<CampaignInfo> campaigns,
        List<PastProposal> pastProposals,
        List<OwnerNote> ownerNotes) {

    /** Bản không có danh sách chiến dịch (dùng trong test và khi chưa có chiến dịch nào). */
    public AdvisorSnapshot(String generatedOn, Calendar calendar, Limits limits, Overall overall, List<Area> areas, List<Slot> sendSlots) {
        this(generatedOn, calendar, limits, overall, areas, sendSlots, List.of(), List.of(), List.of());
    }

    /** Bản không có kết quả các đề xuất trước (dùng trong test). */
    public AdvisorSnapshot(String generatedOn, Calendar calendar, Limits limits, Overall overall, List<Area> areas, List<Slot> sendSlots,
                           List<CampaignInfo> campaigns) {
        this(generatedOn, calendar, limits, overall, areas, sendSlots, campaigns, List.of(), List.of());
    }

    /** Bản không có ghi chú của chủ shop (dùng trong test). */
    public AdvisorSnapshot(String generatedOn, Calendar calendar, Limits limits, Overall overall, List<Area> areas, List<Slot> sendSlots,
                           List<CampaignInfo> campaigns, List<PastProposal> pastProposals) {
        this(generatedOn, calendar, limits, overall, areas, sendSlots, campaigns, pastProposals, List.of());
    }

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
    public record Weather(double avgMinTemp, double avgMaxTemp, int rainyPercent, int coveragePercent, List<OutlookDay> outlook) {
        /** Bản không có dự báo nhiều ngày (chỉ 48 giờ). */
        public Weather(double avgMinTemp, double avgMaxTemp, int rainyPercent, int coveragePercent) {
            this(avgMinTemp, avgMaxTemp, rainyPercent, coveragePercent, List.of());
        }

        /** Số ngày tới (kể từ hôm nay, hôm nay là 0) có dự báo; không có dự báo nhiều ngày thì coi là 2 ngày (48 giờ). */
        public int coverageDays() {
            return outlook.isEmpty() ? 2 : outlook.getLast().inDays() + 1;
        }
    }

    /** Dự báo một ngày sắp tới, trung bình theo số khách của các tỉnh trong vùng có dự báo ngày đó. {@code rainyPercent}: % khách ở tỉnh có tổng mưa ngày từ 10 mm. */
    public record OutlookDay(int inDays, double avgMinTemp, double avgMaxTemp, int rainyPercent) {
    }

    public record ProvinceInfo(String code, String name, long customers, long eligible, Integer minTemp, Integer maxTemp) {
    }

    /** Một vùng (đủ khách để không suy ngược ra từng người). {@code code} dùng được làm khu vực của chiến dịch. */
    public record Area(String code, String label, String region, long customers, long oneTime, long eligible,
                       long awaitingApproval, long newLast30Days, Recency recency, Funnel funnel, Weather weather,
                       List<ProvinceInfo> topProvinces, Products products) {
        /** Bản không có số khách theo nhóm hàng. */
        public Area(String code, String label, String region, long customers, long oneTime, long eligible,
                    long awaitingApproval, long newLast30Days, Recency recency, Funnel funnel, Weather weather,
                    List<ProvinceInfo> topProvinces) {
            this(code, label, region, customers, oneTime, eligible, awaitingApproval, newLast30Days, recency, funnel, weather,
                    topProvinces, null);
        }
    }

    /**
     * Số khách đủ điều kiện mời mà chưa mời, theo nhóm hàng đã mua (giao thành công). Phân nhóm GẦN ĐÚNG theo từ khóa trong tên hàng;
     * một khách mua nhiều loại được đếm ở mỗi nhóm nên các nhóm cộng lại có thể lớn hơn số khách. Không có tên hàng nào ra khỏi hệ thống.
     */
    public record Products(long aoDieuHoa, long pin, long phuKienKhac, long chuaRo) {
    }

    /** Khung giờ nhắn (giờ Việt Nam): số tin đã nhắn và số khách kết bạn từ các tin đó. */
    public record Slot(String label, long sent, long friended) {
    }

    /**
     * Một chiến dịch đang chạy hoặc mới chạy (không tên, không khách): để AI không đề xuất trùng khu vực và thời gian.
     * {@code scope}: mã khu vực của chiến dịch ("ALL" nếu mọi nơi). {@code state}: DANG_GUI (đã duyệt tự gửi, còn khách chờ),
     * CHUA_DUYET (còn khách chờ nhưng chưa duyệt tự gửi), XONG (hết khách chờ), LUU_TRU (chủ shop đã lưu trữ), HET_HAN (quá ngày kết thúc,
     * không còn gửi) hoặc BO_DO (chưa từng duyệt và tạo đã lâu). Chỉ DANG_GUI và CHUA_DUYET là còn chạy.
     */
    public record CampaignInfo(String scope, String state, String startOn, String endOn, String windowFrom, String windowTo,
                               Integer dailyCap, long total, long ready, long sent, Long friended, Long joined, Long optedOut, Long buyers) {
        /** Bản không có kết quả theo bước (dùng trong test). */
        public CampaignInfo(String scope, String state, String startOn, String endOn, String windowFrom, String windowTo,
                            Integer dailyCap, long total, long ready, long sent) {
            this(scope, state, startOn, endOn, windowFrom, windowTo, dailyCap, total, ready, sent, null, null, null, null);
        }

        public boolean open() {
            return "DANG_GUI".equals(state) || "CHUA_DUYET".equals(state);
        }
    }

    /**
     * Kết quả của một đề xuất AI trước đây đã được triển khai thành chiến dịch, để AI tự soi độ chính xác của mình: độ tin cậy lúc đó và phễu thật.
     * {@code buyers}: số khách mua sau khi vào nhóm, null nếu chiến dịch quá nhỏ (ẩn để không suy ngược ra từng khách).
     */
    public record PastProposal(double confidence, int daysAgo, long sent, long friended, long joined, long optedOut, Long buyers) {
    }

    /** Ghi chú còn hiệu lực của chủ shop (chữ tự gõ, đã kiểm). {@code daysLeft}: còn bao nhiêu ngày thì hết hiệu lực (0 là hết hạn hôm nay). */
    public record OwnerNote(String text, int daysLeft) {
    }

    /** Chuỗi JSON đưa cho AI (khóa dạng snake_case). */
    public String toJson() {
        return JSON.writeValueAsString(this);
    }
}
