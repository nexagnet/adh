package vn.shop.crm.zalo;

import org.springframework.stereotype.Service;
import vn.shop.crm.common.AppSettings;
import vn.shop.crm.common.ImportException;

import java.time.Instant;

/** Nấc, trần mỗi ngày và ngưỡng khách của việc tự tạo nhóm (D19), lưu ở bảng cài đặt; mọi thay đổi ghi người làm vào nhật ký Zalo. */
@Service
public class ZaloGroupSettings {

    static final String MODE = "zalo.group.mode";
    static final String DAILY = "zalo.group.daily";
    static final String MIN = "zalo.group.min_customers";
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

    public int daily() {
        return Math.max(1, Math.min(props.maxDaily(), intSetting(DAILY, props.defaultDaily())));
    }

    public int minCustomers() {
        return Math.max(2, Math.min(props.maxMembers(), intSetting(MIN, props.minCustomers())));
    }

    public void setLimits(int daily, int minCustomers, String by) {
        if (daily < 1 || daily > props.maxDaily()) {
            throw new ImportException("Số nhóm mới mỗi ngày phải từ 1 đến " + props.maxDaily() + " (trần cứng).");
        }
        if (minCustomers < 2 || minCustomers > props.maxMembers()) {
            throw new ImportException("Số khách chờ tối thiểu phải từ 2 đến " + props.maxMembers() + ".");
        }
        int oldDaily = daily();
        int oldMin = minCustomers();
        settings.set(DAILY, Integer.toString(daily), by);
        settings.set(MIN, Integer.toString(minCustomers), by);
        auto.log(ZaloAutoSettings.A_CONFIG, null, null, "TAO_NHOM_HAN_MUC " + daily + "/" + minCustomers,
                "CU " + oldDaily + "/" + oldMin, by);
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
