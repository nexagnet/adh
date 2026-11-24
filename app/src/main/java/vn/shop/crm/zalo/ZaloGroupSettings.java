package vn.shop.crm.zalo;

import org.springframework.stereotype.Service;
import vn.shop.crm.common.AppSettings;
import vn.shop.crm.common.ImportException;

import java.time.Instant;

/** Nấc và ngưỡng khách của việc tự tạo nhóm (D19), lưu ở bảng cài đặt; mọi thay đổi ghi người làm vào nhật ký Zalo. */
@Service
public class ZaloGroupSettings {

    static final String MODE = "zalo.group.mode";
    static final String MIN = "zalo.group.min_customers";
    static final String TARGET = "zalo.group.target_customers";
    static final String D_MERGE = "zalo.group.merge_up_days";
    static final String D_RELAX = "zalo.group.relax_days";
    static final String D_ALERT = "zalo.group.alert_days";
    static final String LAST_PLAN = "zalo.group.last_plan_at";
    public static final String A_GROUP = "TAO_NHOM";

    private final AppSettings settings;
    private final ZaloGroupProperties props;
    private final ZaloAutoSettings auto;

    public ZaloGroupSettings(AppSettings settings, ZaloGroupProperties props, ZaloAutoSettings auto) {
        this.settings = settings;
        this.props = props;
        this.auto = auto;
    }

    public ZaloGroupProperties props() {
        return props;
    }

    /** Nấc người dùng chọn. Mặc định OFF. */
    public ZaloGroupMode chosenMode() {
        return ZaloGroupMode.parse(settings.get(MODE).orElse(null));
    }

    /** Nấc có hiệu lực: OFF nếu bộ nối Zalo chưa bật ở cấu hình hoặc cầu dao (dùng chung với gửi tin) đang nhảy. */
    public ZaloGroupMode effectiveMode() {
        if (!auto.props().enabled() || auto.tripped()) {
            return ZaloGroupMode.OFF;
        }
        return chosenMode();
    }

    /** AUTO chỉ được chọn khi người bấm xác nhận đã thử tay một nhóm thật (thiết kế mục 6). */
    public void setMode(ZaloGroupMode mode, boolean confirmedManualTest, String by) {
        if (!auto.props().enabled()) {
            throw new ImportException("Bộ nối Zalo chưa được bật ở cấu hình triển khai (APP_ZALO_AUTO_ENABLED).");
        }
        if (mode == ZaloGroupMode.AUTO && !confirmedManualTest) {
            throw new ImportException("Muốn bật nấc tự động, hãy tick xác nhận đã thử tay một nhóm thật ở nấc xác nhận.");
        }
        ZaloGroupMode old = chosenMode();
        if (old == mode) {
            return;
        }
        settings.set(MODE, mode.name(), by);
        auto.log(ZaloAutoSettings.A_CONFIG, null, null, "TAO_NHOM_NAC_" + mode.name(), "TU_" + old.name(), by);
    }

    public int minCustomers() {
        return Math.max(2, Math.min(props.maxMembers(), intSetting(MIN, props.minCustomers())));
    }

    /** Sức chứa (số khách) của nhóm tự tạo mới. Nhóm đã có không đổi. */
    public int targetCustomers() {
        return Math.max(5, Math.min(props.maxTargetCustomers(), intSetting(TARGET, props.targetCustomers())));
    }

    public void setLimits(int minCustomers, int targetCustomers, String by) {
        if (targetCustomers < 5 || targetCustomers > props.maxTargetCustomers()) {
            throw new ImportException("Sức chứa mỗi nhóm phải từ 5 đến " + props.maxTargetCustomers() + " khách.");
        }
        setMinCustomers(minCustomers, by);
        int oldTarget = targetCustomers();
        settings.set(TARGET, Integer.toString(targetCustomers), by);
        if (oldTarget != targetCustomers) {
            auto.log(ZaloAutoSettings.A_CONFIG, null, null, "TAO_NHOM_SUC_CHUA " + targetCustomers, "CU " + oldTarget, by);
        }
    }

    public void setMinCustomers(int minCustomers, String by) {
        if (minCustomers < 2 || minCustomers > props.maxMembers()) {
            throw new ImportException("Số khách chờ tối thiểu phải từ 2 đến " + props.maxMembers() + ".");
        }
        int oldMin = minCustomers();
        settings.set(MIN, Integer.toString(minCustomers), by);
        auto.log(ZaloAutoSettings.A_CONFIG, null, null, "TAO_NHOM_NGUONG " + minCustomers, "CU " + oldMin, by);
    }

    /** Khách dư lẻ chờ quá số ngày này thì được gộp lên nhóm cùng vùng hoặc miền (mới đồng ý chỉ vào nhóm cùng tỉnh). */
    public int mergeUpDays() {
        return Math.max(1, Math.min(60, intSetting(D_MERGE, props.mergeUpAfterDays())));
    }

    /** Chờ quá số ngày này thì khu vực chỉ cần 2 khách là tạo nhóm, và được ưu tiên. */
    public int relaxDays() {
        return Math.max(mergeUpDays(), Math.min(60, intSetting(D_RELAX, props.relaxAfterDays())));
    }

    /** Chờ quá số ngày này thì báo mẹ xử lý. */
    public int alertDays() {
        return Math.max(relaxDays(), Math.min(90, intSetting(D_ALERT, props.alertAfterDays())));
    }

    public void setDays(int mergeUp, int relax, int alert, String by) {
        if (mergeUp < 1 || relax < mergeUp || alert < relax || alert > 90) {
            throw new ImportException("Số ngày phải tăng dần: gộp lên vùng (từ 1 ngày), rồi nới ngưỡng xuống 2 khách, rồi báo mẹ (tối đa 90 ngày).");
        }
        String old = mergeUpDays() + "/" + relaxDays() + "/" + alertDays();
        settings.set(D_MERGE, Integer.toString(mergeUp), by);
        settings.set(D_RELAX, Integer.toString(relax), by);
        settings.set(D_ALERT, Integer.toString(alert), by);
        if (!old.equals(mergeUp + "/" + relax + "/" + alert)) {
            auto.log(ZaloAutoSettings.A_CONFIG, null, null, "TAO_NHOM_NGAY " + mergeUp + "/" + relax + "/" + alert, "CU " + old, by);
        }
    }

    Instant lastPlanAt() {
        return settings.get(LAST_PLAN).filter(v -> !v.isBlank()).map(v -> {
            try {
                return Instant.parse(v.strip());
            } catch (RuntimeException e) {
                return null;
            }
        }).orElse(null);
    }

    void markPlanned(Instant at) {
        settings.set(LAST_PLAN, at.toString(), "system");
    }

    private int intSetting(String key, int fallback) {
        return settings.get(key).map(v -> {
            try {
                return Integer.parseInt(v.strip());
            } catch (NumberFormatException e) {
                return fallback;
            }
        }).orElse(fallback);
    }
}
