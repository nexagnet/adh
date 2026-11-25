package vn.shop.crm.zalo;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Danh sách tài khoản Zalo phụ ĐANG CHẠY (D23). Tài khoản có cầu nối khai báo ở cấu hình VÀ có dòng bật ở bảng {@code zalo_account}.
 * Tài khoản 1 luôn có. Tài khoản thêm mặc định KHÔNG chạy việc gì (cờ {@code app.zalo.multi.enabled} tắt): chỉ khi các PR gán khách theo tài khoản
 * đã có và chủ dự án bật cờ thì bộ hẹn giờ mới chạy chúng, để không bao giờ có hai tài khoản cùng nhắn một khách.
 */
@Component
public class ZaloAccountRegistry {

    private final ZaloAccountRoutes routes;
    private final JdbcClient jdbc;
    private final boolean multiEnabled;

    public ZaloAccountRegistry(ZaloAccountRoutes routes, JdbcClient jdbc,
                               @org.springframework.beans.factory.annotation.Value("${app.zalo.multi.enabled:false}") boolean multiEnabled) {
        this.routes = routes;
        this.jdbc = jdbc;
        this.multiEnabled = multiEnabled;
    }

    /** Lúc khởi động: tài khoản đã khai báo cầu nối luôn có dòng trong bảng (các bảng khác tham chiếu tới nó), kể cả khi chưa bật chạy. */
    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    void onReady() {
        ensureRows();
    }

    /** Tạo dòng {@code zalo_account} cho các tài khoản đã khai báo cầu nối mà chưa có dòng (không đổi dòng đã có). */
    public void ensureRows() {
        for (long id : routes.accountIds()) {
            if (id != ZaloAccountContext.DEFAULT_ACCOUNT) {
                jdbc.sql("INSERT INTO zalo_account (id, label) VALUES (:id, :l) ON CONFLICT (id) DO NOTHING")
                        .param("id", id).param("l", "Tài khoản phụ " + id).update();
            }
        }
    }

    /** Tài khoản bộ hẹn giờ phải chạy ở nhịp này, tài khoản 1 trước. */
    public List<Long> active() {
        if (!multiEnabled) {
            return List.of(ZaloAccountContext.DEFAULT_ACCOUNT);
        }
        ensureRows();
        List<Long> enabled = jdbc.sql("SELECT id FROM zalo_account WHERE enabled ORDER BY id").query(Long.class).list();
        return routes.accountIds().stream().filter(enabled::contains).sorted().toList();
    }

    public boolean multiEnabled() {
        return multiEnabled;
    }

    /** Một tài khoản để hiện ở trang quản trị. */
    public record View(long id, String label, boolean enabled) {
    }

    /** Mọi tài khoản đã khai báo cầu nối, tài khoản 1 trước (để hiện và chuyển qua lại ở trang Zalo). */
    public List<View> views() {
        ensureRows();
        var rows = jdbc.sql("SELECT id, label, enabled FROM zalo_account ORDER BY id")
                .query((rs, i) -> new View(rs.getLong("id"), rs.getString("label"), rs.getBoolean("enabled"))).list();
        return rows.stream().filter(v -> routes.has(v.id())).toList();
    }

    /** Có chọn tài khoản này để xem và chỉnh ở trang quản trị được không (đã khai báo cầu nối). */
    public boolean selectable(long id) {
        return routes.has(id);
    }

    /**
     * Bật hoặc tạm ngưng một tài khoản THÊM (tài khoản 1 luôn chạy). Tạm ngưng thì bộ hẹn giờ bỏ qua nó (không gửi, không đọc tin); khách đã
     * gắn tài khoản đó ở yên, KHÔNG chuyển sang tài khoản khác.
     */
    public void setEnabled(long id, boolean on) {
        if (id == ZaloAccountContext.DEFAULT_ACCOUNT || !routes.has(id)) {
            throw new vn.shop.crm.common.ImportException("Chỉ bật hoặc tạm ngưng được các tài khoản thêm đã khai báo cầu nối.");
        }
        ensureRows();
        jdbc.sql("UPDATE zalo_account SET enabled = :on WHERE id = :id").param("on", on).param("id", id).update();
    }
}
