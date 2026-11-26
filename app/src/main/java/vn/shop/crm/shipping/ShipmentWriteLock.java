package vn.shop.crm.shipping;

import org.springframework.jdbc.core.simple.JdbcClient;

/** Giữ việc ghi vận đơn và tính lại phân khúc trong cùng một thứ tự trên mọi tiến trình app. */
public final class ShipmentWriteLock {
    private static final long KEY = 0x414448534849504CL;

    private ShipmentWriteLock() {
    }

    /** Gọi trong giao dịch trước khi đọc dữ liệu dùng để quyết định ghi. */
    public static void acquire(JdbcClient jdbc) {
        jdbc.sql("SELECT 1 FROM (SELECT pg_advisory_xact_lock(:key)) AS locked")
                .param("key", KEY).query(Integer.class).single();
    }
}
