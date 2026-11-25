package vn.shop.crm.advisor;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import vn.shop.crm.common.ImportException;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Ghi chú của chủ shop cho AI đề xuất chiến dịch (V54). Mỗi ghi chú có ngày hết hạn nên không bao giờ cũ: hết hạn là tự không còn được đưa cho AI.
 * Ghi chú chỉ là chữ chủ shop gõ ({@link OwnerText}); không lấy gì từ khách. Lớp này không gọi AI, Zalo hay gửi gì.
 */
@Service
public class AdvisorNotes {

    public static final int MIN_LENGTH = 5;
    public static final int MAX_LENGTH = 300;
    /** Số ghi chú còn hiệu lực tối đa (để lời nhắn gửi AI không phình to). */
    public static final int MAX_ACTIVE = 10;
    /** Một ghi chú sống tối đa bấy nhiêu ngày kể từ hôm nay. */
    public static final int MAX_DAYS = 90;

    public record Note(long id, String body, LocalDate expiresOn, String createdBy, LocalDateTime createdAt, int daysLeft) {
    }

    private final JdbcClient jdbc;
    private final Clock clock;

    public AdvisorNotes(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    private LocalDate today() {
        return clock.instant().atZone(AdvisorSnapshotService.ZONE).toLocalDate();
    }

    /** Ghi chú còn hiệu lực (chưa gỡ, chưa quá ngày hết hạn), cái sắp hết hạn trước. */
    public List<Note> active() {
        LocalDate today = today();
        return jdbc.sql("""
                        SELECT id, body, expires_on, created_by, created_at FROM advisor_note
                        WHERE removed_at IS NULL AND expires_on >= :today ORDER BY expires_on, id""")
                .param("today", today)
                .query((rs, i) -> {
                    LocalDate exp = rs.getObject("expires_on", LocalDate.class);
                    OffsetDateTime at = rs.getObject("created_at", OffsetDateTime.class);
                    return new Note(rs.getLong("id"), rs.getString("body"), exp, rs.getString("created_by"),
                            at.atZoneSameInstant(AdvisorSnapshotService.ZONE).toLocalDateTime(), (int) ChronoUnit.DAYS.between(today, exp));
                }).list();
    }

    public long add(String body, LocalDate expiresOn, String by) {
        String text = OwnerText.clean(body, MIN_LENGTH, MAX_LENGTH, "Ghi chú");
        LocalDate today = today();
        if (expiresOn == null) {
            throw new ImportException("Hãy chọn ngày hết hạn cho ghi chú.");
        }
        if (expiresOn.isBefore(today) || expiresOn.isAfter(today.plusDays(MAX_DAYS))) {
            throw new ImportException("Ngày hết hạn phải từ hôm nay tới " + MAX_DAYS + " ngày tới.");
        }
        if (active().size() >= MAX_ACTIVE) {
            throw new ImportException("Đã có " + MAX_ACTIVE + " ghi chú còn hiệu lực. Hãy gỡ bớt ghi chú cũ rồi thêm.");
        }
        return jdbc.sql("INSERT INTO advisor_note (body, expires_on, created_by) VALUES (:b, :e, :by) RETURNING id")
                .param("b", text).param("e", expiresOn).param("by", by).query(Long.class).single();
    }

    /** Gỡ tay một ghi chú (đánh dấu, không xóa dòng). Không có hoặc đã gỡ rồi thì không làm gì. */
    public void remove(long id, String by) {
        jdbc.sql("UPDATE advisor_note SET removed_at = now(), removed_by = :by WHERE id = :id AND removed_at IS NULL")
                .param("by", by).param("id", id).update();
    }
}
