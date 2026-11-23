package vn.shop.crm.zalo;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.LocalTime;

/**
 * Cấu hình tự tạo nhóm Zalo theo khu vực (D19). Cùng luật D18: tắt mặc định (cần cả {@code app.zalo.auto.enabled} và nấc chọn ở trang
 * Zalo), trần thấp, khoảng nghỉ cố định (không ngẫu nhiên), không né phát hiện. Mã Zalo của mẹ chỉ đặt bằng biến môi trường, không vào repo.
 */
@ConfigurationProperties(prefix = "app.zalo.group")
public record ZaloGroupProperties(
        /* Mã Zalo (chữ số) của mẹ để thêm làm phó nhóm; trống = không thêm phó nhóm. Biến môi trường ZALO_DEPUTY_ID. */
        @DefaultValue("") String deputyId,
        @DefaultValue("08:00") LocalTime windowStart,
        @DefaultValue("20:00") LocalTime windowEnd,
        /* Số nhóm mới mỗi ngày: mặc định 1, trang quản trị chỉnh đến trần cứng (tối đa 3, bằng trần của cầu nối). */
        @DefaultValue("1") int defaultDaily,
        @DefaultValue("3") int maxDaily,
        /* Khoảng nghỉ CỐ ĐỊNH giữa hai lần tạo (phút); cầu nối có sàn riêng 5 phút. */
        @DefaultValue("60") int gapMinutes,
        /* Số khách chờ tối thiểu ở một khu vực mới tạo nhóm (chỉnh được ở trang quản trị trong khoảng 2 đến maxMembers). */
        @DefaultValue("3") int minCustomers,
        /* Số khách tối đa đưa vào nhóm mới lúc tạo (cầu nối cũng giới hạn 5). */
        @DefaultValue("5") int maxMembers,
        /* Bao lâu thì xét đề xuất một lần (phút). */
        @DefaultValue("10") int planEveryMinutes,
        /* Mẫu tên nhóm: %s là tên khu vực, %02d là số thứ tự. Sai mẫu thì dùng mẫu mặc định. */
        @DefaultValue("Pin ADH - %s - %02d") String namePattern,
        /* Đề xuất chờ duyệt quá số giờ này thì hết hạn (khách có thể đã đổi). */
        @DefaultValue("48") int proposalExpireHours) {

    public ZaloGroupProperties {
        deputyId = deputyId == null ? "" : deputyId.strip();
        if (maxDaily < 1) {
            maxDaily = 1;
        }
        if (maxDaily > 3) {
            maxDaily = 3;
        }
        if (defaultDaily < 1 || defaultDaily > maxDaily) {
            defaultDaily = 1;
        }
        if (gapMinutes < 10) {
            gapMinutes = 10;
        }
        if (maxMembers < 2 || maxMembers > 5) {
            maxMembers = 5;
        }
        if (minCustomers < 2 || minCustomers > maxMembers) {
            minCustomers = Math.min(3, maxMembers);
        }
        if (planEveryMinutes < 1) {
            planEveryMinutes = 1;
        }
        if (proposalExpireHours < 1) {
            proposalExpireHours = 1;
        }
    }

    /** Tên nhóm theo mẫu; mẫu hỏng hoặc dài quá 100 ký tự thì dùng mẫu mặc định. */
    public String groupName(String areaLabel, int seq) {
        String name;
        try {
            name = String.format(namePattern, areaLabel, seq);
        } catch (java.util.IllegalFormatException e) {
            name = String.format("Pin ADH - %s - %02d", areaLabel, seq);
        }
        return name.length() > 100 ? String.format("Pin ADH - %s - %02d", areaLabel, seq) : name;
    }
}
