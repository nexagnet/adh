package vn.shop.crm.zalo;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.LocalTime;

/**
 * Cấu hình tự tạo nhóm Zalo theo khu vực (D19). Cùng luật D18: tắt mặc định (cần cả {@code app.zalo.auto.enabled} và nấc chọn ở trang
 * Zalo), khoảng nghỉ cố định (không ngẫu nhiên), không né phát hiện. KHÔNG có trần số nhóm mới mỗi ngày (chủ dự án chốt 24/11/2026: chỉ
 * giới hạn số KHÁCH được nhắn mỗi ngày); số nhóm mỗi ngày tự bị chặn bởi khung giờ và khoảng nghỉ cố định. Mã Zalo của mẹ chỉ đặt bằng biến môi trường, không vào repo.
 */
@ConfigurationProperties(prefix = "app.zalo.group")
public record ZaloGroupProperties(
        /* Mã Zalo (chữ số) của mẹ để thêm làm phó nhóm; trống = không thêm phó nhóm. Biến môi trường ZALO_DEPUTY_ID. */
        @DefaultValue("") String deputyId,
        @DefaultValue("08:00") LocalTime windowStart,
        @DefaultValue("20:00") LocalTime windowEnd,
        /* Khoảng nghỉ CỐ ĐỊNH giữa hai lần tạo (phút); cầu nối có sàn riêng 5 phút. */
        @DefaultValue("60") int gapMinutes,
        /* Số khách chờ tối thiểu ở một khu vực mới tạo nhóm (chỉnh được ở trang quản trị trong khoảng 2 đến maxMembers). */
        @DefaultValue("3") int minCustomers,
        /* Số khách tối đa đưa vào nhóm mới lúc tạo (cầu nối cũng giới hạn 5). Các khách còn lại vào dần qua đường thêm người sẵn có. */
        @DefaultValue("5") int maxMembers,
        /* Sức chứa mục tiêu của một nhóm tự tạo (số khách, không tính mẹ và tài khoản phụ); trang quản trị chỉnh trong khoảng 5 đến maxTargetCustomers.
           Chủ dự án chốt 24/11/2026: khoảng 5 người mỗi nhóm; vượt vài người cũng được (trần mềm 8 khách, xem app.grouping.soft-cap ở GroupService). */
        @DefaultValue("5") int targetCustomers,
        @DefaultValue("100") int maxTargetCustomers,
        /* Khách dư lẻ: mới đồng ý thì chỉ vào nhóm CÙNG TỈNH còn dưới trần mềm; chờ từ mergeUpAfterDays ngày thì được gộp lên nhóm cùng vùng hoặc miền;
           chờ từ relaxAfterDays ngày thì khu vực chỉ cần 2 khách là tạo nhóm và được ưu tiên; chờ từ alertAfterDays thì báo mẹ. Chỉnh ở trang Zalo. */
        @DefaultValue("3") int mergeUpAfterDays,
        @DefaultValue("7") int relaxAfterDays,
        @DefaultValue("14") int alertAfterDays,
        /* Bao lâu thì xét đề xuất một lần (phút). */
        @DefaultValue("10") int planEveryMinutes,
        /* Mẫu tên nhóm: %s là tên khu vực, %02d là số thứ tự. Sai mẫu thì dùng mẫu mặc định. */
        @DefaultValue("Áo điều hòa ADH - %s - %02d") String namePattern,
        /* Đề xuất chờ duyệt quá số giờ này thì hết hạn (khách có thể đã đổi). */
        @DefaultValue("48") int proposalExpireHours) {

    public ZaloGroupProperties {
        deputyId = deputyId == null ? "" : deputyId.strip();
        if (gapMinutes < 10) {
            gapMinutes = 10;
        }
        if (maxMembers < 2 || maxMembers > 5) {
            maxMembers = 5;
        }
        if (minCustomers < 2 || minCustomers > maxMembers) {
            minCustomers = Math.min(3, maxMembers);
        }
        if (maxTargetCustomers < 5) {
            maxTargetCustomers = 5;
        }
        if (maxTargetCustomers > 200) {
            maxTargetCustomers = 200;
        }
        if (targetCustomers < 5 || targetCustomers > maxTargetCustomers) {
            targetCustomers = Math.min(5, maxTargetCustomers);
        }
        if (mergeUpAfterDays < 1) {
            mergeUpAfterDays = 3;
        }
        if (relaxAfterDays < mergeUpAfterDays) {
            relaxAfterDays = Math.max(7, mergeUpAfterDays);
        }
        if (alertAfterDays < relaxAfterDays) {
            alertAfterDays = Math.max(14, relaxAfterDays);
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
            name = String.format("Áo điều hòa ADH - %s - %02d", areaLabel, seq);
        }
        return name.length() > 100 ? String.format("Áo điều hòa ADH - %s - %02d", areaLabel, seq) : name;
    }
}
