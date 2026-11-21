package vn.shop.crm;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;

import java.nio.file.Path;

/**
 * Chạy thử ứng dụng trên máy mà KHÔNG cần Docker hay cài PostgreSQL: dùng Postgres nhúng, dữ liệu giữ lại
 * trong thư mục target/dev-pg giữa các lần chạy. Chỉ dùng để thử (dữ liệu giả!), không dùng để chạy thật.
 *
 * <pre>./mvnw spring-boot:test-run -Dspring-boot.run.mainClass=vn.shop.crm.DevApplication</pre>
 * Mở http://localhost:8080 — tài khoản "admin", mật khẩu "dev-only-password".
 */
public final class DevApplication {

    private DevApplication() {
    }

    public static void main(String[] args) throws Exception {
        EmbeddedPostgres postgres = EmbeddedPostgres.builder()
                .setDataDirectory(Path.of("target", "dev-pg"))
                .setCleanDataDirectory(false)
                .setPort(54329)
                .start();
        System.setProperty("spring.datasource.url", postgres.getJdbcUrl("postgres", "postgres"));
        System.setProperty("spring.datasource.username", "postgres");
        System.setProperty("spring.datasource.password", "");
        System.setProperty("app.admin.password", "dev-only-password");
        System.setProperty("spring.thymeleaf.cache", "false");
        CrmApplication.main(args);
    }
}
