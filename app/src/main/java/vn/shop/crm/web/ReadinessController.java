package vn.shop.crm.web;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

@RestController
public class ReadinessController {
    private static final Pattern SHA = Pattern.compile("[0-9a-f]{40}");
    private final DataSource dataSource;
    private final String buildRevision = packagedRevision();
    private final ReentrantLock databaseCheck = new ReentrantLock();

    public ReadinessController(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @GetMapping(value = "/readyz", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> ready() {
        // Chỉ cho một yêu cầu kiểm DB tại một thời điểm, kể cả khi endpoint đi qua proxy công khai.
        if (!databaseCheck.tryLock()) {
            return unavailable();
        }
        try (var connection = dataSource.getConnection()) {
            return connection.isValid(2)
                    ? ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(buildRevision)
                    : unavailable();
        } catch (SQLException e) {
            return unavailable();
        } finally {
            databaseCheck.unlock();
        }
    }

    private static ResponseEntity<String> unavailable() {
        return ResponseEntity.status(503).cacheControl(CacheControl.noStore()).build();
    }

    private static String packagedRevision() {
        try (var resource = ReadinessController.class.getResourceAsStream("/adh-build-revision.txt")) {
            if (resource != null) {
                String value = new String(resource.readAllBytes(), StandardCharsets.US_ASCII).strip();
                if (SHA.matcher(value).matches()) {
                    return value;
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Không đọc được mã bản build của ADH", e);
        }
        return "local";
    }
}
