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
}
