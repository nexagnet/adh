package vn.shop.crm.imports;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import vn.shop.crm.shipping.Carrier;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Báo cho mẹ biết cần xuất file Excel của hãng từ ngày nào đến ngày nào (đồng bộ làm tay: hệ thống không đăng nhập cổng hãng).
 *
 * <p>Mốc bắt đầu = ngày TẠO ĐƠN lớn nhất đã nhập (không dùng giờ trong tên file) trừ {@code overlapDays} ngày gối đầu (trạng thái đơn đổi sau khi tạo), và lùi thêm tới
 * đơn cũ nhất còn chưa có kết quả cuối (chưa giao, hủy hay hoàn) nếu đơn đó trong vòng {@code lookbackDays} ngày, để thấy
 * được trạng thái mới của chúng. Đơn treo quá lâu (ví dụ "Đã lấy hàng" từ nhiều tháng trước, hãng không cập nhật nữa)
 * KHÔNG kéo mốc lùi mãi, chỉ được đếm để báo riêng.
 */
@Service
public class ExportWindowService {

    static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    /** Tỷ lệ của hạn mức mỗi lần xuất được dùng khi chia khoảng ngày, chừa chỗ cho ước lượng lệch. */
    private static final double SAFE_FRACTION = 0.8;
    private static final int RATE_DAYS = 60;
    static final int MAX_RANGES = 31;

    /** Một khoảng ngày để xuất một file (tính cả hai đầu). */
    public record Range(LocalDate from, LocalDate to) {
    }

    /**
     * Lời khuyên cho một hãng.
     *
     * @param hasData     đã có đơn của hãng này trong hệ thống chưa
     * @param latestOrder ngày của đơn mới nhất đã nhập
     * @param lastImport  ngày nhập file gần nhất
     * @param daysSince   số ngày kể từ lần nhập gần nhất
     * @param overdue     quá hạn nhắc (so với {@code app.imports.remind-days})
     * @param from        xuất từ ngày này
     * @param to          đến ngày này (hôm nay)
     * @param estimated   ước tính số đơn trong khoảng, 0 nếu chưa biết
     * @param fileLimit   hạn mức đơn mỗi lần xuất của hãng, 0 nếu không biết
     * @param ranges      các khoảng ngày nên xuất riêng (một phần tử nếu không cần chia)
     * @param stuckOpen   số đơn chưa có kết quả cuối từ trước mốc lùi (hãng không cập nhật nữa)
     */
    public record Advice(Carrier carrier, boolean hasData, LocalDate latestOrder, LocalDate lastImport, long daysSince,
                         boolean overdue, LocalDate from, LocalDate to, int estimated, int fileLimit,
                         List<Range> ranges, int stuckOpen) {
        public boolean split() {
            return ranges.size() > 1;
        }
    }

    private final JdbcClient jdbc;
    private final Clock clock;
    private final int overlapDays;
    private final int lookbackDays;
    private final int remindDays;

    @org.springframework.beans.factory.annotation.Autowired
    public ExportWindowService(JdbcClient jdbc,
                               @Value("${app.imports.overlap-days:14}") int overlapDays,
                               @Value("${app.imports.lookback-days:30}") int lookbackDays,
                               @Value("${app.imports.remind-days:7}") int remindDays) {
        this(jdbc, Clock.system(ZONE), overlapDays, lookbackDays, remindDays);
    }

    ExportWindowService(JdbcClient jdbc, Clock clock, int overlapDays, int lookbackDays, int remindDays) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.overlapDays = Math.max(0, overlapDays);
        this.lookbackDays = Math.max(1, lookbackDays);
        this.remindDays = Math.max(1, remindDays);
    }

    /** Hạn mức đơn mỗi lần xuất. Chỉ J&T đã biết (500); hãng khác trả 0 = không chia khoảng. */
    static int fileLimit(Carrier carrier) {
        return carrier == Carrier.JT_EXPRESS ? 500 : 0;
    }

    /** Số ngày tối đa của một lần lọc trên cổng hãng. J&T chỉ cho lọc tối đa 1 tháng; hãng khác trả 0 = không biết. */
    static int maxSpanDays(Carrier carrier) {
        return carrier == Carrier.JT_EXPRESS ? 31 : 0;
    }

    public List<Advice> all() {
        return java.util.Arrays.stream(Carrier.values()).map(this::advise).toList();
    }

    /** Các hãng đã từng nhập mà đã quá hạn nhắc. */
    public List<Advice> overdue() {
        return all().stream().filter(a -> a.hasData() && a.overdue()).toList();
    }

    public Advice advise(Carrier carrier) {
        LocalDate today = LocalDate.now(clock);
        LocalDate cutoff = today.minusDays(lookbackDays);
        LocalDate rateFrom = today.minusDays(RATE_DAYS);

        Timestamp latest = jdbc.sql("SELECT max(created_on) FROM shipment WHERE carrier = :c")
                .param("c", carrier.name()).query(Timestamp.class).optional().orElse(null);
        OffsetDateTime lastBatch = jdbc.sql("SELECT max(committed_at) FROM import_batch WHERE carrier = :c AND status = 'COMMITTED'")
                .param("c", carrier.name()).query(OffsetDateTime.class).optional().orElse(null);
        int fileLimit = fileLimit(carrier);
        if (latest == null) {
            return new Advice(carrier, false, null, toDate(lastBatch), 0, false, null, today, 0, fileLimit,
                    List.of(), 0);
        }
        // Đơn chưa có kết quả cuối: còn có thể đổi trạng thái, nên file xuất lần tới nên phủ tới chúng.
        String open = "carrier = :c AND canonical_status IN ('CREATED','IN_TRANSIT','RETURNING','UNKNOWN') AND created_on IS NOT NULL";
        Timestamp oldestOpen = jdbc.sql("SELECT min(created_on) FROM shipment WHERE " + open + " AND created_on >= :cut")
                .param("c", carrier.name()).param("cut", cutoff.atStartOfDay()).query(Timestamp.class).optional().orElse(null);
        int stuck = jdbc.sql("SELECT count(*) FROM shipment WHERE " + open + " AND created_on < :cut")
                .param("c", carrier.name()).param("cut", cutoff.atStartOfDay()).query(Integer.class).single();
        int recent = jdbc.sql("SELECT count(*) FROM shipment WHERE carrier = :c AND created_on >= :f")
                .param("c", carrier.name()).param("f", rateFrom.atStartOfDay()).query(Integer.class).single();
        Timestamp earliest = jdbc.sql("SELECT min(created_on) FROM shipment WHERE carrier = :c")
                .param("c", carrier.name()).query(Timestamp.class).single();

        LocalDate earliestDay = earliest.toLocalDateTime().toLocalDate();
        long span = Math.max(1, Math.min(RATE_DAYS, ChronoUnit.DAYS.between(earliestDay, today) + 1));
        double perDay = recent / (double) span;

        LocalDate lastImport = toDate(lastBatch);
        long daysSince = lastImport == null ? 0 : Math.max(0, ChronoUnit.DAYS.between(lastImport, today));
        Advice plan = plan(carrier, today, latest.toLocalDateTime().toLocalDate(),
                oldestOpen == null ? null : oldestOpen.toLocalDateTime().toLocalDate(),
                perDay, overlapDays, fileLimit, maxSpanDays(carrier), stuck);
        return new Advice(plan.carrier(), true, plan.latestOrder(), lastImport, daysSince,
                lastImport != null && daysSince >= remindDays, plan.from(), plan.to(), plan.estimated(),
                plan.fileLimit(), plan.ranges(), plan.stuckOpen());
    }

    /** Phần tính thuần (không đụng CSDL): mốc bắt đầu, ước tính số đơn, cách chia khoảng ngày. */
    static Advice plan(Carrier carrier, LocalDate today, LocalDate latestOrder, LocalDate oldestOpen,
                       double perDay, int overlapDays, int fileLimit, int maxSpan, int stuck) {
        LocalDate latest = latestOrder.isAfter(today) ? today : latestOrder; // dữ liệu ngày tương lai: không tin
        LocalDate from = latest.minusDays(overlapDays);
        if (oldestOpen != null && oldestOpen.isBefore(from)) {
            from = oldestOpen;
        }
        long days = ChronoUnit.DAYS.between(from, today) + 1;
        int estimated = (int) Math.round(perDay * days);
        List<Range> ranges = new ArrayList<>();
        long window = days;
        if (maxSpan > 0) {
            window = Math.min(window, maxSpan);
        }
        if (fileLimit > 0 && perDay > 0 && perDay * window > fileLimit * SAFE_FRACTION) {
            window = Math.max(1, (long) Math.floor(fileLimit * SAFE_FRACTION / perDay));
        }
        for (LocalDate start = from; !start.isAfter(today) && ranges.size() < MAX_RANGES; start = start.plusDays(window)) {
            LocalDate end = start.plusDays(window - 1);
            ranges.add(new Range(start, end.isAfter(today) ? today : end));
        }
        return new Advice(carrier, true, latest, null, 0, false, from, today, estimated, fileLimit,
                List.copyOf(ranges), stuck);
    }

    private static LocalDate toDate(OffsetDateTime t) {
        return t == null ? null : t.atZoneSameInstant(ZONE).toLocalDate();
    }
}
