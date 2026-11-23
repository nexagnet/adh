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
import vn.shop.crm.shipping.StatusKey;
import vn.shop.crm.shipping.StatusMappingService;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Nhập file Excel/CSV của hãng vận chuyển: tải lên -> ánh xạ cột -> xem trước -> xác nhận.
 * Nhập lại cùng một file không làm tăng số lần mua (khóa duy nhất: hãng + mã vận đơn).
 */
@Service
public class ImportService {

    public record Mapping(int headerRow, Map<FieldKey, String> columns) {
    }

    public record BatchSummary(long id, Carrier carrier, String filename, String status,
                               LocalDateTime uploadedAt, LocalDateTime committedAt, Integer totalRows,
                               Integer validRows, Integer newShipments, Integer updatedShipments,
                               Integer statusChanges, Integer errorRows, Integer unmappedStatuses,
                               Integer segmentChanges) {
        public boolean committed() {
            return "COMMITTED".equals(status);
        }
    }

    public record FileView(BatchSummary batch, int headerRow, List<String> headers,
                           List<List<String>> sampleRows, Map<FieldKey, String> suggested,
                           boolean sameFileAlreadyImported) {
    }

    public record RowError(int rowNumber, String code, String message, String rawRow)
            implements java.io.Serializable {
    }

    public record Report(int totalRows, int validRows, int errorRows, int duplicatesInFile, int newShipments,
                         int existingShipments, int unchangedShipments, int statusChanges,
                         Map<String, Long> unmappedStatuses, List<RowError> sampleErrors, int fileLimit) {
        /** File có đúng bằng hạn mức xuất của hãng (J&T: 500): nhiều khả năng cổng đã cắt bớt đơn. */
        public boolean atFileLimit() {
            return fileLimit > 0 && totalRows == fileLimit;
        }
    }

    private record ParsedRow(int rowNumber, String trackingNo, String orderRef, String phoneRaw, String phone,
                             String name, String address, LocalDateTime createdOn, LocalDateTime deliveredOn,
                             String rawStatus, String goods, Map<String, String> payload) {
    }

    private record ParseResult(List<ParsedRow> rows, List<RowError> errors, int totalRows, int duplicatesInFile) {
    }

    private record Existing(long id, String statusKey, String phone) {
    }

    private record BatchData(long id, Carrier carrier, String filename, String status, byte[] content) {
    }

    private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final Pattern TRAILING_ZERO_DECIMAL = Pattern.compile("^(\\d+)\\.0+$");
    private static final int SAMPLE_ROWS = 5;
    private static final int SAMPLE_ERRORS = 50;
    private static final int CHUNK = 1000;
    private static final List<DateTimeFormatter> DATE_TIME_FORMATS = List.of(
            DateTimeFormatter.ofPattern("dd/MM/uuuu HH:mm:ss"),
            DateTimeFormatter.ofPattern("dd/MM/uuuu HH:mm"),
            DateTimeFormatter.ofPattern("d/M/uuuu H:mm:ss"),
            DateTimeFormatter.ofPattern("d/M/uuuu H:mm"),
            DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm"),
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss"),
            DateTimeFormatter.ofPattern("dd-MM-uuuu HH:mm:ss"));
    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            DateTimeFormatter.ofPattern("dd/MM/uuuu"),
            DateTimeFormatter.ofPattern("d/M/uuuu"),
            DateTimeFormatter.ofPattern("uuuu-MM-dd"),
            DateTimeFormatter.ofPattern("dd-MM-uuuu"));

    private final JdbcClient jdbc;
    private final StatusMappingService statuses;
    private final CustomerService customers;
    private final int maxRows;
    private final long maxUncompressedBytes;

    public ImportService(JdbcClient jdbc, StatusMappingService statuses, CustomerService customers,
                         @Value("${app.imports.max-rows:50000}") int maxRows,
                         @Value("${app.imports.max-uncompressed-bytes:104857600}") long maxUncompressedBytes) {
        this.jdbc = jdbc;
        this.statuses = statuses;
        this.customers = customers;
        this.maxRows = maxRows;
        this.maxUncompressedBytes = maxUncompressedBytes;
    }

    // ================= tải lên & xem file =================

    /** Một file người dùng tải lên (tên gốc + nội dung). */
    public record UploadedFile(String filename, byte[] content) {
    }

    private static final Pattern EXPORT_STAMP = Pattern.compile("(\\d{2})-(\\d{2})-(\\d{4})-(\\d{2})-(\\d{2})-(\\d{2})");
    private static final int MAX_FILES_PER_BATCH = 20;

    /**
     * Nhận nhiều file của cùng một hãng trong một lô (J&T chỉ cho xuất tối đa 500 dòng mỗi lần). Các file được gộp
     * theo thứ tự thời điểm xuất ghi trong tên file (file không có dấu thời gian giữ thứ tự tải lên), nên cùng một
     * vận đơn nằm ở nhiều file thì file mới hơn thắng. Mọi file phải có cùng các cột tiêu đề.
     */
    @Transactional
    public long createBatch(Carrier carrier, List<UploadedFile> files) {
        List<UploadedFile> given = files == null ? List.of() : files.stream()
                .filter(f -> f != null && f.content() != null && f.content().length > 0).toList();
        if (given.isEmpty()) {
            throw new ImportException("Chưa chọn file hoặc file rỗng.");
        }
        if (given.size() == 1) {
            return createBatch(carrier, given.getFirst().filename(), given.getFirst().content());
        }
        if (carrier == null) {
            throw new ImportException("Hãy chọn hãng vận chuyển.");
        }
        if (given.size() > MAX_FILES_PER_BATCH) {
            throw new ImportException("Mỗi lần chỉ tải tối đa " + MAX_FILES_PER_BATCH + " file. Hãy chia thành nhiều lần.");
        }
        List<UploadedFile> ordered = given.stream()
                .sorted(java.util.Comparator.comparing(f -> exportStamp(f.filename()),
                        java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder())))
                .toList();
        List<List<String>> merged = new ArrayList<>();
        List<String> headerKey = null;
        for (UploadedFile file : ordered) {
            List<List<String>> rows = TabularReader.read(file.content(), maxUncompressedBytes);
            int headerRow = detectHeaderRow(rows);
            List<String> headers = rows.get(headerRow - 1);
            List<String> key = headers.stream().map(h -> StatusKey.of(h == null ? "" : h)).toList();
            if (headerKey == null) {
                headerKey = key;
                merged.addAll(rows.subList(0, headerRow)); // giữ nguyên phần đầu file (nếu có) của file đầu tiên
            } else if (!key.equals(headerKey)) {
                throw new ImportException("File \"" + shortName(file.filename()) + "\" có các cột tiêu đề khác với "
                        + "các file còn lại. Chỉ gộp được các file xuất cùng một kiểu từ cùng một hãng.");
            }
            for (int i = headerRow; i < rows.size(); i++) {
                if (!isBlankRow(rows.get(i))) {
                    merged.add(rows.get(i));
                }
            }
            if (merged.size() > maxRows + headerRow + 1) {
                throw new ImportException("Các file gộp lại có quá nhiều dòng (tối đa " + maxRows
                        + " dòng dữ liệu). Hãy nhập thành nhiều lần.");
            }
        }
        byte[] content = toCsv(merged);
        String name = ordered.size() + " file: " + ordered.stream().map(f -> shortName(f.filename()))
                .collect(java.util.stream.Collectors.joining(", "));
        return createBatch(carrier, name, content);
    }

    private static String shortName(String filename) {
        String n = filename == null || filename.isBlank() ? "file" : filename.strip();
        return n.length() > 40 ? n.substring(0, 40) : n;
    }

    /** Thời điểm xuất lấy từ tên file kiểu "Data 21-11-2026-09-30-15 ... ĐƠN HÀNG.xlsx"; không có thì null. */
    static LocalDateTime exportStamp(String filename) {
        if (filename == null) {
            return null;
        }
        var m = EXPORT_STAMP.matcher(filename);
        if (!m.find()) {
            return null;
        }
        try {
            return LocalDateTime.of(Integer.parseInt(m.group(3)), Integer.parseInt(m.group(2)),
                    Integer.parseInt(m.group(1)), Integer.parseInt(m.group(4)), Integer.parseInt(m.group(5)),
                    Integer.parseInt(m.group(6)));
        } catch (java.time.DateTimeException e) {
            return null;
        }
    }

    /** CSV nội bộ để gộp nhiều file; KHÔNG thêm dấu nháy chống công thức (khác {@code CsvOut}) vì đây không phải file xuất ra cho người dùng. */
    static byte[] toCsv(List<List<String>> rows) {
        try {
            java.io.StringWriter out = new java.io.StringWriter();
            try (var printer = new org.apache.commons.csv.CSVPrinter(out, org.apache.commons.csv.CSVFormat.DEFAULT)) {
                for (List<String> row : rows) {
                    printer.printRecord(row);
                }
            }
            return out.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Không thể gộp file", e);
        }
    }

    @Transactional
    public long createBatch(Carrier carrier, String filename, byte[] content) {
        if (carrier == null) {
            throw new ImportException("Hãy chọn hãng vận chuyển.");
        }
        if (content == null || content.length == 0) {
            throw new ImportException("Chưa chọn file hoặc file rỗng.");
        }
        rejectSettlementFile(TabularReader.read(content, maxUncompressedBytes)); // báo lỗi sớm nếu không đọc được
        String safeName = filename == null || filename.isBlank() ? "file" : filename.strip();
        if (safeName.length() > 200) {
            safeName = safeName.substring(safeName.length() - 200);
        }
        return jdbc.sql("""
                        INSERT INTO import_batch (carrier, filename, sha256, file_content)
                        VALUES (:carrier, :filename, :sha, :content) RETURNING id""")
                .param("carrier", carrier.name())
                .param("filename", safeName)
                .param("sha", sha256(content))
                .param("content", content)
                .query(Long.class).single();
    }

    /** File "Lịch sử đối soát" (có dòng "Mã kỳ đối soát") không phải danh sách đơn: chỉ cho nhập ở mục riêng. */
    private static void rejectSettlementFile(List<List<String>> rows) {
        if (SettlementImportService.looksLikeSettlement(rows)) {
            throw new ImportException("Đây là file Lịch sử đối soát của J&T (có dòng \"Mã kỳ đối soát\"), không phải "
                    + "danh sách đơn hàng. Hãy nhập ở mục \"Nhập file đối soát\" (đường dẫn /imports/settlement).");
        }
    }

    public FileView open(long batchId, Integer headerRowOverride) {
        BatchData batch = loadBatch(batchId);
        List<List<String>> all = TabularReader.read(batch.content(), maxUncompressedBytes);
        int headerRow = headerRowOverride != null ? headerRowOverride
                : savedHeaderRow(batch.carrier()).orElseGet(() -> detectHeaderRow(all));
        if (headerRow < 1 || headerRow > all.size()) {
            throw new ImportException("Dòng tiêu đề không hợp lệ (file có " + all.size() + " dòng).");
        }
        List<String> headers = all.get(headerRow - 1).stream().map(String::strip)
                .filter(h -> !h.isEmpty()).toList();
        List<List<String>> sample = new ArrayList<>();
        for (int i = headerRow; i < all.size() && sample.size() < SAMPLE_ROWS; i++) {
            if (!isBlankRow(all.get(i))) {
                sample.add(all.get(i));
            }
        }
        Map<FieldKey, String> suggested = new EnumMap<>(FieldKey.class);
        Map<FieldKey, String> guessed = FieldKey.guess(headers);
        Map<FieldKey, String> saved = savedMapping(batch.carrier());
        for (FieldKey field : FieldKey.values()) {
            String savedHeader = saved.get(field);
            if (savedHeader != null && containsHeader(headers, savedHeader)) {
                suggested.put(field, savedHeader);
            } else if (guessed.containsKey(field)) {
                suggested.put(field, guessed.get(field));
            }
        }
        boolean same = jdbc.sql("SELECT count(*) FROM import_batch WHERE sha256 = :sha AND status = 'COMMITTED' "
                        + "AND id <> :id")
                .param("sha", sha256(batch.content())).param("id", batchId).query(Long.class).single() > 0;
        return new FileView(summary(batchId), headerRow, headers, sample, suggested, same);
    }

    // ================= xem trước & xác nhận =================

    public Report preview(long batchId, Mapping mapping) {
        BatchData batch = loadBatch(batchId);
        ParseResult parsed = parse(batch, mapping);
        Map<String, Existing> existing = loadExisting(batch.carrier(), parsed.rows());
        return buildReport(batch.carrier(), parsed, existing);
    }

    @Transactional
    public Report commit(long batchId, Mapping mapping) {
        BatchData batch = loadBatch(batchId);
        if ("COMMITTED".equals(batch.status())) {
            throw new ImportException("Lô nhập này đã được xác nhận trước đó. Hãy tải file lên lại nếu muốn nhập tiếp.");
        }
        ParseResult parsed = parse(batch, mapping);
        Map<String, Existing> existing = loadExisting(batch.carrier(), parsed.rows());
        Report report = buildReport(batch.carrier(), parsed, existing);
        Map<String, CanonicalStatus> statusMap = statuses.mappingsFor(batch.carrier());

        Set<String> touchedPhones = new HashSet<>();
        for (RowError error : parsed.errors()) {
            jdbc.sql("""
                            INSERT INTO import_error (batch_id, row_number, code, message, raw_row)
                            VALUES (:batch, :row, :code, :message, :raw)""")
                    .param("batch", batchId).param("row", error.rowNumber()).param("code", error.code())
                    .param("message", error.message()).param("raw", clean(error.rawRow(), 2000))
                    .update();
        }
        for (ParsedRow row : parsed.rows()) {
            String statusKey = StatusKey.of(row.rawStatus());
            CanonicalStatus canonical = statusMap.getOrDefault(statusKey, CanonicalStatus.UNKNOWN);
            Existing old = existing.get(row.trackingNo());
            Map<String, Object> p = params(row, batch, statusKey, canonical);
            long shipmentId;
            boolean writeHistory;
            if (old == null) {
                shipmentId = jdbc.sql("""
                                INSERT INTO shipment (carrier, tracking_no, order_ref, receiver_phone_raw, receiver_phone,
                                    receiver_name, receiver_address, created_on, delivered_on, raw_status, status_key,
                                    canonical_status, goods, raw_payload, first_batch_id, last_batch_id)
                                VALUES (:carrier, :tracking, :orderRef, :phoneRaw, :phone, :name, :address, :createdOn,
                                    :deliveredOn, :rawStatus, :statusKey, :canonical, :goods, CAST(:payload AS jsonb),
                                    :batch, :batch)
                                RETURNING id""")
                        .paramSource(p).query(Long.class).single();
                writeHistory = true;
            } else {
                p.put("id", old.id());
                jdbc.sql("""
                                UPDATE shipment SET
                                    order_ref = COALESCE(:orderRef, order_ref),
                                    receiver_phone_raw = :phoneRaw, receiver_phone = :phone,
                                    receiver_name = COALESCE(:name, receiver_name),
                                    receiver_address = COALESCE(:address, receiver_address),
                                    created_on = COALESCE(:createdOn, created_on),
                                    delivered_on = COALESCE(:deliveredOn, delivered_on),
                                    goods = COALESCE(:goods, goods),
                                    raw_status = :rawStatus, status_key = :statusKey,
                                    -- đơn đã được file đối soát J&T chứng minh giao thành công thì giữ nguyên, trừ khi hãng báo hủy/hoàn
                                    canonical_status = CASE WHEN settled_delivered
                                        AND :canonical NOT IN ('CANCELLED', 'RETURNING', 'RETURNED')
                                        THEN 'DELIVERED' ELSE :canonical END,
                                    settled_delivered = settled_delivered
                                        AND :canonical NOT IN ('CANCELLED', 'RETURNING', 'RETURNED'),
                                    raw_payload = CAST(:payload AS jsonb), last_batch_id = :batch, updated_at = now()
                                WHERE id = :id""")
                        .paramSource(p).update();
                shipmentId = old.id();
                writeHistory = !old.statusKey().equals(statusKey);
                touchedPhones.add(old.phone());
            }
            if (writeHistory) {
                jdbc.sql("""
                                INSERT INTO shipment_status_history (shipment_id, batch_id, raw_status, canonical_status)
                                VALUES (:shipment, :batch, :rawStatus, :canonical)""")
                        .param("shipment", shipmentId).param("batch", batchId)
                        .param("rawStatus", clean(row.rawStatus(), 200)).param("canonical", canonical.name())
                        .update();
            }
            touchedPhones.add(row.phone());
        }

        saveMapping(batch.carrier(), mapping);
        jdbc.sql("""
                        UPDATE import_batch SET status = 'COMMITTED', committed_at = now(),
                            total_rows = :total, valid_rows = :valid, new_shipments = :newCount,
                            updated_shipments = :existing, unchanged_shipments = :unchanged,
                            status_changes = :changes, error_rows = :errors, duplicate_in_file = :dups,
                            unmapped_statuses = :unmapped,
                            file_content = decode('', 'hex')   -- đã nhập xong: không giữ file gốc (chứa SĐT/địa chỉ khách)
                        WHERE id = :id""")
                .param("total", report.totalRows()).param("valid", report.validRows())
                .param("newCount", report.newShipments()).param("existing", report.existingShipments())
                .param("unchanged", report.unchangedShipments()).param("changes", report.statusChanges())
                .param("errors", report.errorRows()).param("dups", report.duplicatesInFile())
                .param("unmapped", report.unmappedStatuses().size()).param("id", batchId).update();

        Map<String, String> before = segmentsOf(touchedPhones);
        customers.recalculate(touchedPhones);
        Map<String, String> after = segmentsOf(touchedPhones);
        int segmentChanges = 0;
        for (Map.Entry<String, String> e : before.entrySet()) {
            String now = after.get(e.getKey());
            if (isBuyer(e.getValue()) && isBuyer(now) && !e.getValue().equals(now)) {
                segmentChanges++;
            }
        }
        jdbc.sql("UPDATE import_batch SET segment_changes = :n WHERE id = :id")
                .param("n", segmentChanges).param("id", batchId).update();
        return report;
    }

    private static boolean isBuyer(String segment) {
        return "ONE_TIME".equals(segment) || "REPEAT".equals(segment);
    }

    /** Nhóm hiện tại của các SĐT (chỉ những khách đã có hồ sơ). */
    private Map<String, String> segmentsOf(Collection<String> phones) {
        List<String> list = phones.stream().filter(p -> p != null && !p.isBlank()).distinct().toList();
        Map<String, String> result = new HashMap<>();
        for (int from = 0; from < list.size(); from += CHUNK) {
            List<String> chunk = list.subList(from, Math.min(from + CHUNK, list.size()));
            jdbc.sql("SELECT phone, segment FROM customer WHERE phone IN (:list)").param("list", chunk)
                    .query((rs, i) -> Map.entry(rs.getString("phone"), rs.getString("segment")))
                    .list().forEach(e -> result.put(e.getKey(), e.getValue()));
        }
        return result;
    }

    // ================= nhắc nhập file định kỳ =================

    public record StaleImport(Carrier carrier, long daysSince) {
    }

    /**
     * Hãng đã từng nhập file mà quá {@code days} ngày chưa có lô nhập mới. Hệ thống không tự đăng nhập cổng của hãng,
     * nên đây là cách nhắc mẹ xuất và tải file mới (thay cho tự động hóa).
     */
    public List<StaleImport> staleCarriers(int days) {
        return jdbc.sql("""
                        SELECT carrier, floor(extract(epoch FROM (now() - max(committed_at))) / 86400)::bigint AS days
                        FROM import_batch WHERE status = 'COMMITTED' AND kind = 'SHIPMENTS'
                        GROUP BY carrier HAVING max(committed_at) < now() - make_interval(days => :days)
                        ORDER BY carrier""")
                .param("days", Math.max(1, days))
                .query((rs, i) -> new StaleImport(Carrier.valueOf(rs.getString("carrier")), rs.getLong("days")))
                .list();
    }

    // ================= truy vấn lô nhập =================

    public List<BatchSummary> list() {
        return jdbc.sql(SUMMARY_SQL + " WHERE kind = 'SHIPMENTS' ORDER BY id DESC LIMIT 200")
                .query(ImportService::mapSummary).list();
    }

    public BatchSummary summary(long id) {
        return jdbc.sql(SUMMARY_SQL + " WHERE id = :id AND kind = 'SHIPMENTS'").param("id", id)
                .query(ImportService::mapSummary).optional()
                .orElseThrow(() -> new ImportException("Không tìm thấy lô nhập."));
    }

    public List<RowError> errors(long batchId, int limit) {
        return jdbc.sql("SELECT row_number, code, message, raw_row FROM import_error WHERE batch_id = :id "
                        + "ORDER BY row_number LIMIT " + Math.max(1, limit))
                .param("id", batchId)
                .query((rs, i) -> new RowError(rs.getInt("row_number"), rs.getString("code"),
                        rs.getString("message"), rs.getString("raw_row")))
                .list();
    }

    private static final String SUMMARY_SQL = """
            SELECT id, carrier, filename, status, uploaded_at, committed_at, total_rows, valid_rows,
                   new_shipments, updated_shipments, status_changes, error_rows, unmapped_statuses, segment_changes
            FROM import_batch""";

    private static BatchSummary mapSummary(ResultSet rs, int i) throws SQLException {
        return new BatchSummary(rs.getLong("id"), Carrier.valueOf(rs.getString("carrier")),
                rs.getString("filename"), rs.getString("status"),
                toLocal(rs.getObject("uploaded_at", OffsetDateTime.class)),
                toLocal(rs.getObject("committed_at", OffsetDateTime.class)),
                rs.getObject("total_rows", Integer.class), rs.getObject("valid_rows", Integer.class),
                rs.getObject("new_shipments", Integer.class), rs.getObject("updated_shipments", Integer.class),
                rs.getObject("status_changes", Integer.class), rs.getObject("error_rows", Integer.class),
                rs.getObject("unmapped_statuses", Integer.class), rs.getObject("segment_changes", Integer.class));
    }

    // ================= phân tích file =================

    private ParseResult parse(BatchData batch, Mapping mapping) {
        List<List<String>> all = TabularReader.read(batch.content(), maxUncompressedBytes);
        int headerRow = mapping.headerRow();
        if (headerRow < 1 || headerRow > all.size()) {
            throw new ImportException("Dòng tiêu đề không hợp lệ (file có " + all.size() + " dòng).");
        }
        if (all.size() - headerRow > maxRows) {
            throw new ImportException("File có quá nhiều dòng (tối đa " + maxRows + " dòng dữ liệu).");
        }
        List<String> headers = all.get(headerRow - 1);
        Map<FieldKey, Integer> index = new EnumMap<>(FieldKey.class);
        for (FieldKey field : FieldKey.values()) {
            String header = mapping.columns().get(field);
            if (header == null || header.isBlank()) {
                if (field.required()) {
                    throw new ImportException("Chưa chọn cột cho \"" + field.label() + "\".");
                }
                continue;
            }
            int i = indexOfHeader(headers, header);
            if (i < 0) {
                throw new ImportException("Không tìm thấy cột \"" + header + "\" trong file.");
            }
            index.put(field, i);
        }

        Map<String, ParsedRow> byTracking = new LinkedHashMap<>();
        List<RowError> errors = new ArrayList<>();
        Set<String> erased = new HashSet<>(jdbc.sql("SELECT phone_hash FROM suppression WHERE reason = 'ERASED'")
                .query(String.class).list());
        int total = 0;
        int duplicates = 0;
        for (int r = headerRow; r < all.size(); r++) {
            List<String> row = all.get(r);
            if (isBlankRow(row)) {
                continue;
            }
            total++;
            int rowNumber = r + 1;
            String tracking = normalizeCode(cell(row, index, FieldKey.TRACKING_NO));
            String phoneRaw = cell(row, index, FieldKey.RECEIVER_PHONE);
            String rawStatus = cell(row, index, FieldKey.STATUS);
            String rawRow = String.join(" | ", row);
            if (tracking.isEmpty()) {
                errors.add(new RowError(rowNumber, "MISSING_TRACKING", "Thiếu mã vận đơn.", rawRow));
                continue;
            }
            if (tracking.length() > 100) {
                errors.add(new RowError(rowNumber, "INVALID_TRACKING", "Mã vận đơn quá dài.", rawRow));
                continue;
            }
            if (phoneRaw.isEmpty()) {
                errors.add(new RowError(rowNumber, "MISSING_PHONE", "Thiếu số điện thoại người nhận.", rawRow));
                continue;
            }
            var phone = PhoneNormalizer.firstValid(phoneRaw);
            if (phone.isEmpty()) {
                errors.add(new RowError(rowNumber, "INVALID_PHONE",
                        "Số điện thoại không hợp lệ: \"" + clean(phoneRaw, 40) + "\".", rawRow));
                continue;
            }
            if (erased.contains(PhoneHash.of(phone.get()))) {
                // khách đã yêu cầu xóa dữ liệu: không nhập lại, và không lưu dòng gốc vào danh sách lỗi
                errors.add(new RowError(rowNumber, "ERASED", "Khách đã yêu cầu xóa dữ liệu, bỏ qua dòng này.", ""));
                continue;
            }
            if (rawStatus.isEmpty()) {
                errors.add(new RowError(rowNumber, "MISSING_STATUS", "Thiếu trạng thái đơn.", rawRow));
                continue;
            }
            Map<String, String> payload = new LinkedHashMap<>();
            for (int c = 0; c < row.size() && c < headers.size(); c++) {
                String header = headers.get(c).strip();
                if (!header.isEmpty() && !row.get(c).isBlank()) {
                    payload.put(header, row.get(c).strip());
                }
            }
            ParsedRow parsed = new ParsedRow(rowNumber, tracking,
                    blankToNull(normalizeCode(cell(row, index, FieldKey.ORDER_REF))), clean(phoneRaw, 40),
                    phone.get(), blankToNull(clean(cell(row, index, FieldKey.RECEIVER_NAME), 200)),
                    blankToNull(joinAddress(cell(row, index, FieldKey.RECEIVER_ADDRESS),
                            cell(row, index, FieldKey.ADMIN_AREA))),
                    parseDate(cell(row, index, FieldKey.CREATED_ON)),
                    parseDate(cell(row, index, FieldKey.DELIVERED_ON)), clean(rawStatus, 200),
                    blankToNull(clean(cell(row, index, FieldKey.GOODS), 500)), payload);
            if (byTracking.put(tracking, parsed) != null) {
                duplicates++;
            }
        }
        return new ParseResult(new ArrayList<>(byTracking.values()), errors, total, duplicates);
    }

    private Report buildReport(Carrier carrier, ParseResult parsed, Map<String, Existing> existing) {
        Map<String, CanonicalStatus> statusMap = statuses.mappingsFor(carrier);
        int newCount = 0;
        int changes = 0;
        int existingCount = 0;
        Map<String, Long> unmappedByKey = new LinkedHashMap<>();
        Map<String, String> sampleByKey = new HashMap<>();
        for (ParsedRow row : parsed.rows()) {
            String key = StatusKey.of(row.rawStatus());
            Existing old = existing.get(row.trackingNo());
            if (old == null) {
                newCount++;
            } else {
                existingCount++;
                if (!old.statusKey().equals(key)) {
                    changes++;
                }
            }
            if (!statusMap.containsKey(key)) {
                unmappedByKey.merge(key, 1L, Long::sum);
                sampleByKey.putIfAbsent(key, row.rawStatus());
            }
        }
        Map<String, Long> unmapped = new LinkedHashMap<>();
        unmappedByKey.forEach((key, count) -> unmapped.put(sampleByKey.get(key), count));
        List<RowError> sample = parsed.errors().size() > SAMPLE_ERRORS
                ? parsed.errors().subList(0, SAMPLE_ERRORS) : parsed.errors();
        return new Report(parsed.totalRows(), parsed.rows().size(), parsed.errors().size(),
                parsed.duplicatesInFile(), newCount, existingCount, existingCount - changes, changes,
                unmapped, List.copyOf(sample), ExportWindowService.fileLimit(carrier));
    }

    private Map<String, Existing> loadExisting(Carrier carrier, Collection<ParsedRow> rows) {
        List<String> trackings = rows.stream().map(ParsedRow::trackingNo).toList();
        Map<String, Existing> result = new HashMap<>();
        for (int from = 0; from < trackings.size(); from += CHUNK) {
            List<String> chunk = trackings.subList(from, Math.min(from + CHUNK, trackings.size()));
            jdbc.sql("SELECT id, tracking_no, status_key, receiver_phone FROM shipment "
                            + "WHERE carrier = :carrier AND tracking_no IN (:list)")
                    .param("carrier", carrier.name()).param("list", chunk)
                    .query((rs, i) -> Map.entry(rs.getString("tracking_no"),
                            new Existing(rs.getLong("id"), rs.getString("status_key"),
                                    rs.getString("receiver_phone"))))
                    .list().forEach(e -> result.put(e.getKey(), e.getValue()));
        }
        return result;
    }

    private static Map<String, Object> params(ParsedRow row, BatchData batch, String statusKey,
                                              CanonicalStatus canonical) {
        Map<String, Object> p = new HashMap<>();
        p.put("carrier", batch.carrier().name());
        p.put("tracking", row.trackingNo());
        p.put("orderRef", row.orderRef());
        p.put("phoneRaw", row.phoneRaw());
        p.put("phone", row.phone());
        p.put("name", row.name());
        p.put("address", row.address());
        p.put("createdOn", row.createdOn() == null ? null : Timestamp.valueOf(row.createdOn()));
        p.put("deliveredOn", row.deliveredOn() == null ? null : Timestamp.valueOf(row.deliveredOn()));
        p.put("rawStatus", row.rawStatus());
        p.put("goods", row.goods());
        p.put("statusKey", statusKey);
        p.put("canonical", canonical.name());
        p.put("payload", toJson(row.payload()));
        p.put("batch", batch.id());
        return p;
    }

    // ================= mapping đã lưu =================

    private java.util.Optional<Integer> savedHeaderRow(Carrier carrier) {
        return jdbc.sql("SELECT header_row FROM import_setting WHERE carrier = :c")
                .param("c", carrier.name()).query(Integer.class).optional();
    }

    private Map<FieldKey, String> savedMapping(Carrier carrier) {
        Map<FieldKey, String> result = new EnumMap<>(FieldKey.class);
        jdbc.sql("SELECT field, header FROM column_mapping WHERE carrier = :c").param("c", carrier.name())
                .query((rs, i) -> Map.entry(rs.getString("field"), rs.getString("header")))
                .list().forEach(e -> {
                    try {
                        result.put(FieldKey.valueOf(e.getKey()), e.getValue());
                    } catch (IllegalArgumentException ignored) {
                        // trường đã bị bỏ khỏi phiên bản mới
                    }
                });
        return result;
    }

    private void saveMapping(Carrier carrier, Mapping mapping) {
        jdbc.sql("DELETE FROM column_mapping WHERE carrier = :c").param("c", carrier.name()).update();
        mapping.columns().forEach((field, header) -> {
            if (header != null && !header.isBlank()) {
                jdbc.sql("INSERT INTO column_mapping (carrier, field, header) VALUES (:c, :f, :h)")
                        .param("c", carrier.name()).param("f", field.name()).param("h", header.strip()).update();
            }
        });
        jdbc.sql("""
                        INSERT INTO import_setting (carrier, header_row) VALUES (:c, :r)
                        ON CONFLICT (carrier) DO UPDATE SET header_row = EXCLUDED.header_row""")
                .param("c", carrier.name()).param("r", mapping.headerRow()).update();
    }

    private BatchData loadBatch(long id) {
        return jdbc.sql("SELECT id, carrier, filename, status, file_content FROM import_batch WHERE id = :id AND kind = 'SHIPMENTS'")
                .param("id", id)
                .query((rs, i) -> new BatchData(rs.getLong("id"), Carrier.valueOf(rs.getString("carrier")),
                        rs.getString("filename"), rs.getString("status"), rs.getBytes("file_content")))
                .optional().orElseThrow(() -> new ImportException("Không tìm thấy lô nhập."));
    }

    // ================= tiện ích =================

    private static String cell(List<String> row, Map<FieldKey, Integer> index, FieldKey field) {
        Integer i = index.get(field);
        if (i == null || i >= row.size() || row.get(i) == null) {
            return "";
        }
        return row.get(i).strip();
    }

    static String normalizeCode(String value) {
        String v = value == null ? "" : value.strip();
        var m = TRAILING_ZERO_DECIMAL.matcher(v);
        if (m.matches()) {
            v = m.group(1);
        }
        return clean(v, 200).toUpperCase(java.util.Locale.ROOT);
    }

    static String clean(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        String v = value.replace("\u0000", "").strip();
        return v.length() > maxLength ? v.substring(0, maxLength) : v;
    }

    /**
     * Ghép "Địa chỉ người nhận" với "Địa chỉ hành chính" (xã, huyện, tỉnh) của hãng có cột riêng như J&T, để tỉnh nằm
     * cuối địa chỉ (cách đoán tỉnh ở {@code VnGeo} lấy tên tỉnh xuất hiện cuối). Không ghép lần hai nếu địa chỉ đã chứa
     * đủ phần hành chính; nếu quá dài thì cắt phần số nhà, giữ phần hành chính.
     */
    static String joinAddress(String address, String adminArea) {
        String a = clean(address, 500);
        String area = clean(adminArea, 300);
        if (area.isEmpty() || StatusKey.of(a).contains(StatusKey.of(area))) {
            return a;
        }
        if (a.isEmpty()) {
            return area;
        }
        String joined = a + ", " + area;
        if (joined.length() <= 500) {
            return joined;
        }
        return a.substring(0, Math.max(0, 500 - area.length() - 2)) + ", " + area;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    static boolean isBlankRow(List<String> row) {
        return row.stream().allMatch(c -> c == null || c.isBlank());
    }

    private static int indexOfHeader(List<String> headers, String wanted) {
        String key = StatusKey.of(wanted);
        for (int i = 0; i < headers.size(); i++) {
            if (StatusKey.of(headers.get(i)).equals(key)) {
                return i;
            }
        }
        return -1;
    }

    private static boolean containsHeader(List<String> headers, String wanted) {
        return indexOfHeader(headers, wanted) >= 0;
    }

    /** Nếu dòng 1 ít cột có chữ (tiêu đề báo cáo), tìm dòng đầu tiên có từ 3 ô trở lên trong 20 dòng đầu. */
    private static int detectHeaderRow(List<List<String>> all) {
        for (int i = 0; i < Math.min(all.size(), 20); i++) {
            long filled = all.get(i).stream().filter(c -> c != null && !c.isBlank()).count();
            if (filled >= 3) {
                return i + 1;
            }
        }
        return 1;
    }

    static LocalDateTime parseDate(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String t = text.strip();
        for (DateTimeFormatter f : DATE_TIME_FORMATS) {
            try {
                return LocalDateTime.parse(t, f);
            } catch (DateTimeParseException ignored) {
                // thử định dạng kế tiếp
            }
        }
        for (DateTimeFormatter f : DATE_FORMATS) {
            try {
                return LocalDate.parse(t, f).atStartOfDay();
            } catch (DateTimeParseException ignored) {
                // thử định dạng kế tiếp
            }
        }
        return null; // ngày chỉ để tham khảo, không làm hỏng dòng dữ liệu
    }

    private static LocalDateTime toLocal(OffsetDateTime t) {
        return t == null ? null : t.atZoneSameInstant(ZONE).toLocalDateTime();
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** JSON tối giản cho cột jsonb (khóa/giá trị đều là chuỗi). */
    static String toJson(Map<String, String> map) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> e : map.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append('"').append(escape(e.getKey())).append("\":\"").append(escape(e.getValue())).append('"');
        }
        return sb.append('}').toString();
    }

    private static String escape(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (char c : s.replace("\u0000", "").toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}
