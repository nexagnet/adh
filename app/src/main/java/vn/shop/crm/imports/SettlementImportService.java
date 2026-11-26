package vn.shop.crm.imports;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.shop.crm.common.ImportException;
import vn.shop.crm.common.PhoneHash;
import vn.shop.crm.common.PhoneNormalizer;
import vn.shop.crm.customers.CustomerService;
import vn.shop.crm.shipping.CanonicalStatus;
import vn.shop.crm.shipping.Carrier;
import vn.shop.crm.shipping.ShipmentWriteLock;
import vn.shop.crm.shipping.StatusKey;

import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Nhập file "Lịch sử đối soát" của J&T (Dòng tiền, Lịch sử đối soát, Tải về): mỗi file là một kỳ đối soát. Cấu trúc
 * file: dòng "Mã khách hàng", dòng "Mã kỳ đối soát", dòng tiêu đề (16 cột, đọc theo TÊN cột), các dòng vận đơn, dòng
 * "Tổng". File có tên, SĐT và địa chỉ khách, nhưng hệ thống chỉ giữ mã vận đơn, mã đơn khách hàng, thời gian ký nhận
 * và các khoản tiền.
 *
 * <p>File đối soát bổ sung thông tin cho vận đơn đã có. Có ký nhận và COD thực thu, không có phí hoàn
 * thì có thể xác nhận giao thành công cho đơn đang mở. Nhập lại cùng một kỳ thì thay thế kỳ cũ.
 */
@Service
public class SettlementImportService {

    public static final int MAX_FILES_PER_BATCH = 60;

    /** Một kỳ đối soát trong báo cáo xem trước hoặc danh sách đã nhập. */
    public record PeriodInfo(String code, LocalDate from, LocalDate to, int lines, BigDecimal codCollected,
                             BigDecimal net, BigDecimal footerCod, BigDecimal footerNet, boolean replacesExisting,
                             OffsetDateTime importedAt) implements java.io.Serializable {
        /** Tổng cuối file J&T ghi khác tổng cộng các dòng của chính file đó. */
        public boolean footerDiffers() {
            return (footerNet != null && footerNet.compareTo(net) != 0)
                    || (footerCod != null && footerCod.compareTo(codCollected) != 0);
        }
    }

    public record Report(int totalRows, int validRows, int errorRows, int duplicatesInFile, int erasedRows,
                         int netMismatchLines, List<PeriodInfo> periods, int matchedShipments, int unknownShipments,
                         int orderRefToFill, int deliveredOnToFill, int signedNotDelivered,
                         Map<String, Long> signedNotDeliveredByStatus, List<ImportService.RowError> sampleErrors,
                         int customersRecalculated, int upgradedToDelivered,
                         Map<String, Long> upgradedByStatus) implements java.io.Serializable {
    }

    public record BatchView(long id, String filename, String status) {
        public boolean committed() {
            return "COMMITTED".equals(status);
        }
    }

    private record Head(String code, String customerCode, BigDecimal footerCod, BigDecimal footerNet) {
    }

    private record Line(int rowNumber, String periodCode, String trackingNo, String orderRef, LocalDateTime sentAt,
                        LocalDateTime signedAt, BigDecimal cod, BigDecimal freight, BigDecimal codFee,
                        BigDecimal returnFee, BigDecimal partialFee, BigDecimal adjustment, BigDecimal net,
                        LocalDateTime adjustedAt, String note) {
    }

    private record Parsed(Map<String, Head> heads, List<Line> lines, List<ImportService.RowError> errors,
                          int totalRows, int duplicates, int erased, int netMismatch) {
    }

    private record ShipmentRow(long id, String orderRef, LocalDateTime deliveredOn, String canonical, String rawStatus,
                               String phone) {
    }

    /** Thông tin gộp theo mã vận đơn (một đơn có thể xuất hiện ở nhiều kỳ). */
    private record Merged(String orderRef, LocalDateTime signedAt, boolean proven) {
    }

    private record Staged(long id, String filename, String status, byte[] content) {
    }

    private static final Carrier CARRIER = Carrier.JT_EXPRESS;
    private static final int CHUNK = 1000;
    private static final int SAMPLE_ERRORS = 50;
    private static final Pattern PERIOD_CODE = Pattern.compile("^(.+?)-(\\d{8})-(\\d{4})$");
    private static final Pattern PLAIN_NUMBER = Pattern.compile("^-?\\d+(\\.\\d+)?$");
    private static final Pattern GROUPED_NUMBER = Pattern.compile("^-?\\d{1,3}([.,]\\d{3})+$");

    private static final String K_PERIOD = "ma ky doi soat";
    private static final String K_CUSTOMER = "ma khach hang";
    private static final String K_TRACKING = "ma van don";
    private static final String K_TOTAL = "tong";

    private final JdbcClient jdbc;
    private final CustomerService customers;
    private final int maxRows;
    private final long maxUncompressedBytes;

    public SettlementImportService(JdbcClient jdbc, CustomerService customers,
                                   @Value("${app.imports.max-rows:50000}") int maxRows,
                                   @Value("${app.imports.max-uncompressed-bytes:104857600}") long maxUncompressedBytes) {
        this.jdbc = jdbc;
        this.customers = customers;
        this.maxRows = maxRows;
        this.maxUncompressedBytes = maxUncompressedBytes;
    }

    // ================= nhận diện file =================

    /** Trong vài dòng đầu có dòng "Mã kỳ đối soát": đúng là file Lịch sử đối soát của J&T. */
    static boolean looksLikeSettlement(List<List<String>> rows) {
        for (int i = 0; i < Math.min(rows.size(), 10); i++) {
            if (K_PERIOD.equals(key(first(rows.get(i))))) {
                return true;
            }
        }
        return false;
    }

    // ================= tải lên =================

    /**
     * Nhận một hoặc nhiều file kỳ đối soát. Các file được ghép nối tiếp thành một lô (mỗi file giữ nguyên dòng "Mã kỳ
     * đối soát" của nó) để dùng chung một bước xem trước và xác nhận.
     */
    @Transactional
    public long createBatch(List<ImportService.UploadedFile> files) {
        List<ImportService.UploadedFile> given = files == null ? List.of() : files.stream()
                .filter(f -> f != null && f.content() != null && f.content().length > 0).toList();
        if (given.isEmpty()) {
            throw new ImportException("Chưa chọn file hoặc file rỗng.");
        }
        if (given.size() > MAX_FILES_PER_BATCH) {
            throw new ImportException("Mỗi lần chỉ tải tối đa " + MAX_FILES_PER_BATCH + " file. Hãy chia thành nhiều lần.");
        }
        List<List<String>> merged = new ArrayList<>();
        for (ImportService.UploadedFile file : given) {
            List<List<String>> rows = TabularReader.read(file.content(), maxUncompressedBytes);
            if (!looksLikeSettlement(rows)) {
                throw new ImportException("File \"" + shortName(file.filename()) + "\" không phải file Lịch sử đối soát "
                        + "của J&T (không thấy dòng \"Mã kỳ đối soát\"). File danh sách đơn hàng thì nhập ở mục Nhập đơn.");
            }
            merged.addAll(rows);
            merged.add(List.of()); // ngăn cách giữa các file
            if (merged.size() > maxRows + 10L * given.size()) {
                throw new ImportException("Các file gộp lại có quá nhiều dòng. Hãy nhập thành nhiều lần.");
            }
        }
        byte[] content = ImportService.toCsv(merged);
        String name = given.size() == 1 ? shortName(given.getFirst().filename())
                : given.size() + " file: " + given.stream().map(f -> shortName(f.filename()))
                .collect(java.util.stream.Collectors.joining(", "));
        if (name.length() > 200) {
            name = name.substring(0, 200);
        }
        parse(content); // báo lỗi cấu trúc ngay lúc tải lên
        return jdbc.sql("""
                        INSERT INTO import_batch (carrier, kind, filename, sha256, file_content)
                        VALUES (:carrier, 'SETTLEMENT', :filename, :sha, :content) RETURNING id""")
                .param("carrier", CARRIER.name()).param("filename", name)
                .param("sha", sha256(content)).param("content", content)
                .query(Long.class).single();
    }

    public BatchView batch(long id) {
        Staged s = load(id, false);
        return new BatchView(s.id(), s.filename(), s.status());
    }

    // ================= xem trước & xác nhận =================

    public Report preview(long batchId) {
        Staged staged = load(batchId, false);
        if ("COMMITTED".equals(staged.status())) {
            throw new ImportException("Lô này đã được xác nhận trước đó.");
        }
        ShipmentWriteLock.acquire(jdbc);
        Parsed parsed = parse(staged.content());
        return buildReport(parsed, loadShipments(parsed.lines()), 0, existingPeriodCodes());
    }

    @Transactional
    public Report commit(long batchId) {
        Staged staged = load(batchId, true); // khóa dòng lô: hai lần bấm xác nhận cùng lúc không nhập đôi
        if ("COMMITTED".equals(staged.status())) {
            throw new ImportException("Lô nhập này đã được xác nhận trước đó. Hãy tải file lên lại nếu muốn nhập tiếp.");
        }
        Parsed parsed = parse(staged.content());
        Map<String, ShipmentRow> shipments = loadShipments(parsed.lines());
        Set<String> replaced = existingPeriodCodes(); // chụp trước khi ghi, để báo cáo nói đúng kỳ nào bị thay

        for (ImportService.RowError e : parsed.errors()) {
            jdbc.sql("""
                            INSERT INTO import_error (batch_id, row_number, code, message, raw_row)
                            VALUES (:batch, :row, :code, :message, :raw)""")
                    .param("batch", batchId).param("row", e.rowNumber()).param("code", e.code())
                    .param("message", e.message()).param("raw", ImportService.clean(e.rawRow(), 200)).update();
        }

        // 1) lưu kỳ và các dòng tiền (kỳ đã có thì thay bằng bản mới)
        Map<String, Long> periodIds = new HashMap<>();
        for (Head head : parsed.heads().values()) {
            if (parsed.lines().stream().noneMatch(l -> l.periodCode().equals(head.code()))) {
                continue; // kỳ không có dòng hợp lệ nào thì không tạo
            }
            LocalDate[] range = periodRange(head.code());
            long id = jdbc.sql("""
                            INSERT INTO settlement_period (carrier, period_code, customer_code, period_from, period_to,
                                footer_cod, footer_net, last_batch_id)
                            VALUES (:carrier, :code, :customer, :from, :to, :fcod, :fnet, :batch)
                            ON CONFLICT (carrier, period_code) DO UPDATE SET customer_code = EXCLUDED.customer_code,
                                period_from = EXCLUDED.period_from, period_to = EXCLUDED.period_to,
                                footer_cod = EXCLUDED.footer_cod, footer_net = EXCLUDED.footer_net,
                                last_batch_id = EXCLUDED.last_batch_id, imported_at = now()
                            RETURNING id""")
                    .param("carrier", CARRIER.name()).param("code", head.code())
                    .param("customer", head.customerCode()).param("from", range[0]).param("to", range[1])
                    .param("fcod", head.footerCod()).param("fnet", head.footerNet()).param("batch", batchId)
                    .query(Long.class).single();
            jdbc.sql("DELETE FROM settlement_line WHERE period_id = :p").param("p", id).update();
            periodIds.put(head.code(), id);
        }
        for (Line l : parsed.lines()) {
            jdbc.sql("""
                            INSERT INTO settlement_line (period_id, tracking_no, order_ref, sent_at, signed_at,
                                cod_collected, freight_fee, cod_fee, return_fee, partial_fee, adjustment, net_payable,
                                adjusted_at, note)
                            VALUES (:period, :tracking, :orderRef, :sent, :signed, :cod, :freight, :codFee, :returnFee,
                                :partial, :adj, :net, :adjAt, :note)""")
                    .param("period", periodIds.get(l.periodCode())).param("tracking", l.trackingNo())
                    .param("orderRef", l.orderRef()).param("sent", ts(l.sentAt())).param("signed", ts(l.signedAt()))
                    .param("cod", l.cod()).param("freight", l.freight()).param("codFee", l.codFee())
                    .param("returnFee", l.returnFee()).param("partial", l.partialFee()).param("adj", l.adjustment())
                    .param("net", l.net()).param("adjAt", ts(l.adjustedAt())).param("note", l.note()).update();
        }

        // 2) Bổ sung thông tin và bằng chứng giao thành công cho đơn đã có.
        Set<String> touchedPhones = new HashSet<>();
        for (Map.Entry<String, Merged> e : mergeByTracking(parsed.lines()).entrySet()) {
            ShipmentRow s = shipments.get(e.getKey());
            if (s != null && applyProof(batchId, s, e.getValue())) {
                touchedPhones.add(s.phone());
            }
        }
        customers.recalculate(touchedPhones);

        Report report = buildReport(parsed, shipments, touchedPhones.size(), replaced);
        jdbc.sql("""
                        UPDATE import_batch SET status = 'COMMITTED', committed_at = now(),
                            total_rows = :total, valid_rows = :valid, error_rows = :errors, duplicate_in_file = :dups,
                            file_content = decode('', 'hex')   -- đã nhập xong: không giữ file gốc (chứa SĐT/địa chỉ khách)
                        WHERE id = :id""")
                .param("total", report.totalRows()).param("valid", report.validRows())
                .param("errors", report.errorRows()).param("dups", report.duplicatesInFile()).param("id", batchId)
                .update();
        return report;
    }

    /** Lô vận đơn có thể đến sau lô đối soát. Lúc đó dùng bằng chứng đã lưu trước khi tính phân khúc. */
    void reconcileNewShipments(long batchId, List<String> trackings) {
        Map<String, ShipmentRow> shipments = loadShipmentsByTracking(trackings);
        for (int from = 0; from < trackings.size(); from += CHUNK) {
            List<String> chunk = trackings.subList(from, Math.min(from + CHUNK, trackings.size()));
            jdbc.sql("""
                            SELECT l.tracking_no,
                                   (array_agg(l.order_ref ORDER BY p.period_to DESC NULLS LAST, p.period_code DESC)
                                       FILTER (WHERE l.order_ref IS NOT NULL))[1] AS order_ref,
                                   (array_agg(l.signed_at ORDER BY p.period_to DESC NULLS LAST, p.period_code DESC)
                                       FILTER (WHERE l.signed_at IS NOT NULL))[1] AS signed_at,
                                   bool_or(l.signed_at IS NOT NULL AND l.cod_collected > 0 AND l.return_fee = 0)
                                       AND NOT bool_or(l.return_fee > 0) AS proven
                            FROM settlement_line l JOIN settlement_period p ON l.period_id = p.id
                            WHERE p.carrier = :carrier AND l.tracking_no IN (:list)
                            GROUP BY l.tracking_no""")
                    .param("carrier", CARRIER.name()).param("list", chunk)
                    .query((rs, i) -> Map.entry(rs.getString("tracking_no"), new Merged(rs.getString("order_ref"),
                            rs.getObject("signed_at", LocalDateTime.class), rs.getBoolean("proven"))))
                    .list().forEach(entry -> applyProof(batchId, shipments.get(entry.getKey()), entry.getValue()));
        }
    }

    private boolean applyProof(long batchId, ShipmentRow shipment, Merged proof) {
        boolean upgraded = upgradable(shipment, proof);
        boolean fillRef = shipment.orderRef() == null && proof.orderRef() != null;
        boolean fillTime = shipment.deliveredOn() == null && proof.signedAt() != null
                && (upgraded || isDelivered(shipment));
        if (!upgraded && !fillRef && !fillTime) {
            return false;
        }
        jdbc.sql("""
                        UPDATE shipment SET order_ref = COALESCE(order_ref, :ref),
                            delivered_on = COALESCE(delivered_on, CAST(:signed AS timestamp)),
                            canonical_status = CASE WHEN :upgrade THEN 'DELIVERED' ELSE canonical_status END,
                            settled_delivered = settled_delivered OR :upgrade, updated_at = now() WHERE id = :id""")
                .param("ref", proof.orderRef()).param("signed", fillTime ? ts(proof.signedAt()) : null)
                .param("upgrade", upgraded).param("id", shipment.id()).update();
        if (upgraded) {
            jdbc.sql("""
                            INSERT INTO shipment_status_history (shipment_id, batch_id, raw_status, canonical_status)
                            VALUES (:s, :batch, :raw, 'DELIVERED')""")
                    .param("s", shipment.id()).param("batch", batchId)
                    .param("raw", ImportService.clean(shipment.rawStatus() + " (đối soát J&T)", 200)).update();
        }
        return true;
    }

    // ================= danh sách kỳ đã nhập =================

    /** Các kỳ đã nhập, mới nhất trước; số tiền cộng từ các dòng đang lưu. */
    public List<PeriodInfo> periods() {
        return jdbc.sql("""
                        SELECT p.period_code, p.period_from, p.period_to, p.footer_cod, p.footer_net, p.imported_at,
                               count(l.id) AS lines, coalesce(sum(l.cod_collected), 0) AS cod,
                               coalesce(sum(l.net_payable), 0) AS net
                        FROM settlement_period p LEFT JOIN settlement_line l ON l.period_id = p.id
                        WHERE p.carrier = :c
                        GROUP BY p.id ORDER BY p.period_to DESC NULLS LAST, p.id DESC LIMIT 200""")
                .param("c", CARRIER.name())
                .query((rs, i) -> new PeriodInfo(rs.getString("period_code"), toDate(rs.getDate("period_from")),
                        toDate(rs.getDate("period_to")), rs.getInt("lines"), rs.getBigDecimal("cod"),
                        rs.getBigDecimal("net"), rs.getBigDecimal("footer_cod"), rs.getBigDecimal("footer_net"), false,
                        rs.getObject("imported_at", OffsetDateTime.class)))
                .list();
    }

    /** Ngày cuối của kỳ mới nhất đã nhập (để nhắc tải kỳ sau đó); rỗng nếu chưa nhập kỳ nào. */
    public Optional<LocalDate> latestPeriodEnd() {
        return jdbc.sql("SELECT max(period_to) FROM settlement_period WHERE carrier = :c")
                .param("c", CARRIER.name()).query(java.sql.Date.class).optional().map(java.sql.Date::toLocalDate);
    }

    // ================= phân tích file =================

    private Parsed parse(byte[] content) {
        List<List<String>> all = TabularReader.read(content, maxUncompressedBytes);
        if (!looksLikeSettlement(all)) {
            throw new ImportException("Không thấy dòng \"Mã kỳ đối soát\": đây không phải file Lịch sử đối soát của J&T.");
        }
        if (all.size() > maxRows + 10L * MAX_FILES_PER_BATCH) {
            throw new ImportException("File có quá nhiều dòng (tối đa " + maxRows + " dòng dữ liệu).");
        }
        Set<String> erased = new HashSet<>(jdbc.sql("SELECT phone_hash FROM suppression WHERE reason = 'ERASED'")
                .query(String.class).list());

        Map<String, Head> heads = new LinkedHashMap<>();
        Map<String, Map<String, Line>> byPeriod = new LinkedHashMap<>();
        List<ImportService.RowError> errors = new ArrayList<>();
        String customerCode = null;
        String period = null;
        Map<String, Integer> col = null;
        boolean sawHeader = false;
        int total = 0;
        int duplicates = 0;
        int erasedRows = 0;
        int netMismatch = 0;

        for (int r = 0; r < all.size(); r++) {
            List<String> row = all.get(r);
            if (ImportService.isBlankRow(row)) {
                continue;
            }
            String first = key(first(row));
            if (K_CUSTOMER.equals(first)) {
                customerCode = blankToNull(cell(row, 1));
                col = null;
                period = null;
                continue;
            }
            if (K_PERIOD.equals(first)) {
                String code = ImportService.clean(cell(row, 1), 100);
                if (code.isEmpty()) {
                    throw new ImportException("Dòng " + (r + 1) + ": thiếu mã kỳ đối soát.");
                }
                period = code;
                col = null;
                heads.putIfAbsent(code, new Head(code, customerCode, null, null));
                byPeriod.computeIfAbsent(code, k -> new LinkedHashMap<>());
                continue;
            }
            if (col == null) {
                if (period != null && K_TRACKING.equals(first)) {
                    col = headerColumns(row);
                    sawHeader = true;
                }
                continue; // dòng phần đầu file khác (tiêu đề báo cáo...) thì bỏ qua
            }
            if (K_TOTAL.equals(first)) {
                Head h = heads.get(period);
                heads.put(period, new Head(h.code(), h.customerCode(), lenientMoney(cell(row, col.get("cod"))),
                        lenientMoney(cell(row, col.get("net")))));
                col = null; // hết kỳ này
                continue;
            }

            total++;
            int rowNumber = r + 1;
            String tracking = ImportService.normalizeCode(cell(row, col.get("tracking")));
            if (tracking.isEmpty()) {
                errors.add(new ImportService.RowError(rowNumber, "MISSING_TRACKING", "Thiếu mã vận đơn.", ""));
                continue;
            }
            if (tracking.length() > 100) {
                errors.add(new ImportService.RowError(rowNumber, "INVALID_TRACKING", "Mã vận đơn quá dài.", ""));
                continue;
            }
            // khách đã yêu cầu xóa dữ liệu: không lưu bất cứ gì liên quan tới đơn của họ
            Integer phoneCol = col.get("phone");
            if (phoneCol != null) {
                var phone = PhoneNormalizer.firstValid(cell(row, phoneCol));
                if (phone.isPresent() && erased.contains(PhoneHash.of(phone.get()))) {
                    erasedRows++;
                    errors.add(new ImportService.RowError(rowNumber, "ERASED",
                            "Khách đã yêu cầu xóa dữ liệu, bỏ qua dòng này.", ""));
                    continue;
                }
            }
            BigDecimal cod;
            BigDecimal freight;
            BigDecimal codFee;
            BigDecimal returnFee;
            BigDecimal partial;
            BigDecimal adjustment;
            BigDecimal net;
            try {
                cod = money(row, col, "cod");
                freight = money(row, col, "freight");
                codFee = money(row, col, "codFee");
                returnFee = money(row, col, "returnFee");
                partial = money(row, col, "partial");
                adjustment = money(row, col, "adjustment");
                net = money(row, col, "net");
            } catch (ImportException e) {
                errors.add(new ImportService.RowError(rowNumber, "INVALID_AMOUNT", e.getMessage(), tracking));
                continue;
            }
            BigDecimal expected = cod.subtract(freight).subtract(codFee).subtract(returnFee).subtract(partial)
                    .add(adjustment);
            if (expected.compareTo(net) != 0) {
                netMismatch++;
            }
            Line line = new Line(rowNumber, period, tracking,
                    blankToNull(ImportService.normalizeCode(cell(row, col.get("orderRef")))),
                    ImportService.parseDate(cell(row, col.get("sentAt"))),
                    ImportService.parseDate(cell(row, col.get("signedAt"))), cod, freight, codFee, returnFee, partial,
                    adjustment, net, ImportService.parseDate(cell(row, col.get("adjustedAt"))),
                    blankToNull(ImportService.clean(cell(row, col.get("note")), 500)));
            if (byPeriod.get(period).put(tracking, line) != null) {
                duplicates++;
            }
        }
        if (!sawHeader) {
            throw new ImportException("Không thấy dòng tiêu đề \"Mã vận đơn\" bên dưới \"Mã kỳ đối soát\".");
        }
        List<Line> lines = byPeriod.values().stream().flatMap(m -> m.values().stream()).toList();
        return new Parsed(heads, lines, errors, total, duplicates, erasedRows, netMismatch);
    }

    private static final Map<String, List<String>> HEADERS = new LinkedHashMap<>();

    static {
        HEADERS.put("tracking", List.of("ma van don"));
        HEADERS.put("orderRef", List.of("ma don kh", "ma don khach hang"));
        HEADERS.put("phone", List.of("so dien thoai nguoi nhan", "sdt nguoi nhan"));
        HEADERS.put("sentAt", List.of("ngay gui hang"));
        HEADERS.put("signedAt", List.of("thoi gian ky nhan"));
        HEADERS.put("cod", List.of("tien cod da ky nhan"));
        HEADERS.put("freight", List.of("tien cuoc pp_pm", "tien cuoc"));
        HEADERS.put("codFee", List.of("phi thu ho cod"));
        HEADERS.put("returnFee", List.of("phi chuyen hoan"));
        HEADERS.put("partial", List.of("phi giao mot phan"));
        HEADERS.put("adjustment", List.of("dieu chinh"));
        HEADERS.put("net", List.of("so tien phai tra sau can tru", "so tien phai tra sau can", "so tien phai tra"));
        HEADERS.put("adjustedAt", List.of("thoi gian dieu chinh"));
        HEADERS.put("note", List.of("ghi chu"));
    }

    private static final Map<String, String> HEADER_LABELS = Map.ofEntries(
            Map.entry("tracking", "Mã vận đơn"), Map.entry("cod", "Tiền COD đã ký nhận"),
            Map.entry("freight", "Tiền cước PP_PM"), Map.entry("codFee", "Phí thu hộ COD"),
            Map.entry("returnFee", "Phí chuyển hoàn"), Map.entry("partial", "Phí giao một phần"),
            Map.entry("adjustment", "Điều chỉnh"), Map.entry("net", "Số tiền phải trả sau cấn trừ"));

    /** Cột bắt buộc phải có; còn lại (mã đơn KH, SĐT, ngày, ghi chú) thiếu thì bỏ qua. */
    private static Map<String, Integer> headerColumns(List<String> headerRow) {
        Map<String, Integer> col = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> e : HEADERS.entrySet()) {
            for (int i = 0; i < headerRow.size(); i++) {
                if (e.getValue().contains(key(headerRow.get(i)))) {
                    col.putIfAbsent(e.getKey(), i);
                }
            }
        }
        List<String> missing = HEADER_LABELS.entrySet().stream().filter(e -> !col.containsKey(e.getKey()))
                .map(Map.Entry::getValue).sorted().toList();
        if (!missing.isEmpty()) {
            throw new ImportException("File Lịch sử đối soát thiếu cột: " + String.join(", ", missing)
                    + ". J&T có thể đã đổi định dạng file; hãy báo để cập nhật.");
        }
        return col;
    }

    private static BigDecimal money(List<String> row, Map<String, Integer> col, String field) {
        String text = cell(row, col.get(field));
        if (text.isEmpty()) {
            return BigDecimal.ZERO;
        }
        BigDecimal v = lenientMoney(text);
        if (v == null) {
            throw new ImportException("Số tiền không hợp lệ ở cột \"" + HEADER_LABELS.getOrDefault(field, field)
                    + "\": \"" + ImportService.clean(text, 30) + "\".");
        }
        return v;
    }

    /** "35000", "-35000.0", "1.234.567" (dấu chấm hoặc phẩy ngăn nhóm nghìn); không đọc được thì null. */
    static BigDecimal lenientMoney(String text) {
        if (text == null) {
            return null;
        }
        String t = text.strip().replace(" ", "").replace(" ", "");
        if (t.isEmpty()) {
            return null;
        }
        if (PLAIN_NUMBER.matcher(t).matches()) {
            return new BigDecimal(t);
        }
        if (GROUPED_NUMBER.matcher(t).matches()) {
            return new BigDecimal(t.replace(".", "").replace(",", ""));
        }
        return null;
    }

    // ================= đối chiếu với đơn đã có =================

    private Set<String> existingPeriodCodes() {
        return new HashSet<>(jdbc.sql("SELECT period_code FROM settlement_period WHERE carrier = :c")
                .param("c", CARRIER.name()).query(String.class).list());
    }

    private Report buildReport(Parsed parsed, Map<String, ShipmentRow> shipments, int recalculated,
                               Set<String> existing) {
        Map<String, Merged> merged = mergeByTracking(parsed.lines());
        int matched = 0;
        int unknown = 0;
        int refFill = 0;
        int deliveredFill = 0;
        int signedNotDelivered = 0;
        int upgraded = 0;
        Map<String, Long> upgradedBy = new LinkedHashMap<>();
        Map<String, Long> byStatus = new LinkedHashMap<>();
        for (Map.Entry<String, Merged> e : merged.entrySet()) {
            ShipmentRow s = shipments.get(e.getKey());
            if (s == null) {
                unknown++;
                continue;
            }
            matched++;
            Merged m = e.getValue();
            if (m.orderRef() != null && s.orderRef() == null) {
                refFill++;
            }
            if (upgradable(s, m)) {
                upgraded++;
                upgradedBy.merge(s.rawStatus(), 1L, Long::sum);
            } else if (m.signedAt() != null) {
                if (isDelivered(s)) {
                    if (s.deliveredOn() == null) {
                        deliveredFill++;
                    }
                } else {
                    signedNotDelivered++;
                    byStatus.merge(s.rawStatus(), 1L, Long::sum);
                }
            }
        }
        List<PeriodInfo> periods = new ArrayList<>();
        for (Head h : parsed.heads().values()) {
            List<Line> own = parsed.lines().stream().filter(l -> l.periodCode().equals(h.code())).toList();
            if (own.isEmpty()) {
                continue;
            }
            LocalDate[] range = periodRange(h.code());
            periods.add(new PeriodInfo(h.code(), range[0], range[1], own.size(),
                    own.stream().map(Line::cod).reduce(BigDecimal.ZERO, BigDecimal::add),
                    own.stream().map(Line::net).reduce(BigDecimal.ZERO, BigDecimal::add), h.footerCod(),
                    h.footerNet(), existing.contains(h.code()), null));
        }
        periods.sort(Comparator.comparing(PeriodInfo::from, Comparator.nullsLast(Comparator.naturalOrder())));
        List<ImportService.RowError> sample = parsed.errors().size() > SAMPLE_ERRORS
                ? parsed.errors().subList(0, SAMPLE_ERRORS) : parsed.errors();
        return new Report(parsed.totalRows(), parsed.lines().size(), parsed.errors().size(), parsed.duplicates(),
                parsed.erased(), parsed.netMismatch(), List.copyOf(periods), matched, unknown, refFill, deliveredFill,
                signedNotDelivered, byStatus, List.copyOf(sample), recalculated, upgraded, upgradedBy);
    }

    private static boolean isDelivered(ShipmentRow s) {
        return CanonicalStatus.DELIVERED.name().equals(s.canonical());
    }

    /** Một mã vận đơn có thể nằm ở nhiều kỳ: lấy giá trị khác rỗng của dòng thuộc kỳ mới nhất. */
    private static Map<String, Merged> mergeByTracking(List<Line> lines) {
        List<Line> ordered = lines.stream()
                .sorted(Comparator.comparing((Line l) -> periodRange(l.periodCode())[1],
                        Comparator.nullsFirst(Comparator.naturalOrder())).thenComparing(Line::periodCode)).toList();
        Map<String, Merged> result = new LinkedHashMap<>();
        Set<String> returned = new HashSet<>();
        Set<String> collected = new HashSet<>();
        for (Line l : ordered) {
            Merged old = result.get(l.trackingNo());
            String ref = l.orderRef() != null ? l.orderRef() : old == null ? null : old.orderRef();
            LocalDateTime signed = l.signedAt() != null ? l.signedAt() : old == null ? null : old.signedAt();
            if (l.returnFee().signum() > 0) {
                returned.add(l.trackingNo());
            }
            if (l.signedAt() != null && l.cod().signum() > 0 && l.returnFee().signum() == 0) {
                collected.add(l.trackingNo());
            }
            result.put(l.trackingNo(), new Merged(ref, signed, false));
        }
        // Bằng chứng giao thành công: có ký nhận, J&T đã thu COD và không có phí hoàn ở bất kỳ kỳ nào.
        result.replaceAll((tracking, m) -> new Merged(m.orderRef(), m.signedAt(),
                collected.contains(tracking) && !returned.contains(tracking)));
        return result;
    }

    /** Đơn có thể được nâng lên Giao thành công nhờ file đối soát: chưa rõ, chờ lấy hoặc đang chạy. Hủy/hoàn thì không. */
    private static boolean upgradable(ShipmentRow s, Merged m) {
        return m.proven() && (CanonicalStatus.UNKNOWN.name().equals(s.canonical())
                || CanonicalStatus.CREATED.name().equals(s.canonical())
                || CanonicalStatus.IN_TRANSIT.name().equals(s.canonical()));
    }

    private Map<String, ShipmentRow> loadShipments(Collection<Line> lines) {
        return loadShipmentsByTracking(lines.stream().map(Line::trackingNo).distinct().toList());
    }

    private Map<String, ShipmentRow> loadShipmentsByTracking(List<String> trackings) {
        Map<String, ShipmentRow> result = new HashMap<>();
        for (int from = 0; from < trackings.size(); from += CHUNK) {
            List<String> chunk = trackings.subList(from, Math.min(from + CHUNK, trackings.size()));
            jdbc.sql("""
                            SELECT id, tracking_no, order_ref, delivered_on, canonical_status, raw_status, receiver_phone
                            FROM shipment WHERE carrier = :carrier AND tracking_no IN (:list)""")
                    .param("carrier", CARRIER.name()).param("list", chunk)
                    .query((rs, i) -> Map.entry(rs.getString("tracking_no"), new ShipmentRow(rs.getLong("id"),
                            rs.getString("order_ref"),
                            rs.getTimestamp("delivered_on") == null ? null : rs.getTimestamp("delivered_on").toLocalDateTime(),
                            rs.getString("canonical_status"), rs.getString("raw_status"), rs.getString("receiver_phone"))))
                    .list().forEach(e -> result.put(e.getKey(), e.getValue()));
        }
        return result;
    }

    // ================= tiện ích =================

    /** "KH-20261021-1022": từ ngày (đủ năm) tới ngày (chỉ tháng-ngày, sang năm sau nếu nhỏ hơn). Không đúng dạng thì null. */
    static LocalDate[] periodRange(String code) {
        Matcher m = PERIOD_CODE.matcher(code == null ? "" : code);
        if (!m.matches()) {
            return new LocalDate[]{null, null};
        }
        try {
            String from = m.group(2);
            LocalDate start = LocalDate.of(Integer.parseInt(from.substring(0, 4)), Integer.parseInt(from.substring(4, 6)),
                    Integer.parseInt(from.substring(6, 8)));
            LocalDate end = LocalDate.of(start.getYear(), Integer.parseInt(m.group(3).substring(0, 2)),
                    Integer.parseInt(m.group(3).substring(2, 4)));
            if (end.isBefore(start)) {
                end = end.plusYears(1);
            }
            return new LocalDate[]{start, end};
        } catch (java.time.DateTimeException e) {
            return new LocalDate[]{null, null};
        }
    }

    private Staged load(long id, boolean lock) {
        return jdbc.sql("SELECT id, filename, status, file_content FROM import_batch "
                        + "WHERE id = :id AND kind = 'SETTLEMENT'" + (lock ? " FOR UPDATE" : "")).param("id", id)
                .query((rs, i) -> new Staged(rs.getLong("id"), rs.getString("filename"), rs.getString("status"),
                        rs.getBytes("file_content")))
                .optional().orElseThrow(() -> new ImportException("Không tìm thấy lô nhập đối soát."));
    }

    private static String first(List<String> row) {
        return row.isEmpty() ? "" : row.getFirst();
    }

    private static String key(String text) {
        return StatusKey.of(text == null ? "" : text);
    }

    private static String cell(List<String> row, Integer i) {
        if (i == null || i < 0 || i >= row.size() || row.get(i) == null) {
            return "";
        }
        return row.get(i).strip();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static String shortName(String filename) {
        String n = filename == null || filename.isBlank() ? "file" : filename.strip();
        return n.length() > 40 ? n.substring(0, 40) : n;
    }

    private static Timestamp ts(LocalDateTime t) {
        return t == null ? null : Timestamp.valueOf(t);
    }

    private static LocalDate toDate(java.sql.Date d) {
        return d == null ? null : d.toLocalDate();
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
