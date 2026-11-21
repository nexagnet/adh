package vn.shop.crm.imports;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.dhatim.fastexcel.reader.Cell;
import org.dhatim.fastexcel.reader.CellType;
import org.dhatim.fastexcel.reader.ReadableWorkbook;
import org.dhatim.fastexcel.reader.Row;
import org.dhatim.fastexcel.reader.Sheet;
import vn.shop.crm.common.ImportException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Đọc CSV (UTF-8) hoặc XLSX thành bảng chuỗi. Không đọc .xls cũ, không thực thi macro. */
public final class TabularReader {

    private static final DateTimeFormatter EXCEL_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");
    private static final long MAX_RATIO = 100;

    private TabularReader() {
    }

    public static List<List<String>> read(byte[] content, long maxUncompressedBytes) {
        if (content == null || content.length == 0) {
            throw new ImportException("File rỗng.");
        }
        if (isZip(content)) {
            return readXlsx(content, maxUncompressedBytes);
        }
        if (isOle2(content)) {
            throw new ImportException("File Excel cũ (.xls) chưa được hỗ trợ. Hãy mở file bằng Excel, "
                    + "chọn Lưu thành (Save As) và chọn định dạng .xlsx hoặc CSV UTF-8.");
        }
        return readCsv(content);
    }

    private static boolean isZip(byte[] c) {
        return c.length > 4 && c[0] == 'P' && c[1] == 'K' && c[2] == 3 && c[3] == 4;
    }

    private static boolean isOle2(byte[] c) {
        return c.length > 4 && (c[0] & 0xFF) == 0xD0 && (c[1] & 0xFF) == 0xCF
                && (c[2] & 0xFF) == 0x11 && (c[3] & 0xFF) == 0xE0;
    }

    // ---------- CSV ----------

    private static List<List<String>> readCsv(byte[] content) {
        String text = decodeUtf8(content);
        if (!text.isEmpty() && text.charAt(0) == '﻿') {
            text = text.substring(1);
        }
        char delimiter = detectDelimiter(text);
        CSVFormat format = CSVFormat.DEFAULT.builder()
                .setDelimiter(delimiter)
                .setIgnoreEmptyLines(false)
                .setTrim(false)
                .get();
        List<List<String>> rows = new ArrayList<>();
        try (CSVParser parser = format.parse(new StringReader(text))) {
            for (CSVRecord record : parser) {
                List<String> row = new ArrayList<>(record.size());
                record.forEach(row::add);
                rows.add(row);
            }
        } catch (IOException | RuntimeException e) {
            throw new ImportException("Không đọc được file CSV: " + e.getMessage());
        }
        return rows;
    }

    private static String decodeUtf8(byte[] content) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(content)).toString();
        } catch (CharacterCodingException e) {
            throw new ImportException("File CSV không phải mã hóa UTF-8. Hãy lưu lại dưới dạng "
                    + "\"CSV UTF-8\" hoặc dùng file .xlsx.");
        }
    }

    private static char detectDelimiter(String text) {
        String firstLine = text.lines().filter(l -> !l.isBlank()).findFirst().orElse("");
        char best = ',';
        int bestCount = -1;
        for (char candidate : new char[]{',', ';', '\t', '|'}) {
            int count = (int) firstLine.chars().filter(ch -> ch == candidate).count();
            if (count > bestCount) {
                best = candidate;
                bestCount = count;
            }
        }
        return best;
    }

    // ---------- XLSX ----------

    private static List<List<String>> readXlsx(byte[] content, long maxUncompressedBytes) {
        checkZipSafety(content, maxUncompressedBytes);
        List<List<String>> rows = new ArrayList<>();
        try (ReadableWorkbook workbook = new ReadableWorkbook(new ByteArrayInputStream(content))) {
            Sheet sheet = workbook.getFirstSheet();
            try (Stream<Row> stream = sheet.openStream()) {
                stream.forEach(row -> {
                    int size = row.getCellCount();
                    List<String> cells = new ArrayList<>(size);
                    for (int i = 0; i < size; i++) {
                        cells.add(cellText(row.getCell(i)));
                    }
                    // fastexcel bỏ qua dòng trống ở giữa; giữ chỉ số dòng bằng cách chèn dòng rỗng
                    int target = row.getRowNum();
                    while (rows.size() < target - 1) {
                        rows.add(List.of());
                    }
                    rows.add(cells);
                });
            }
        } catch (IOException | RuntimeException e) {
            throw new ImportException("Không đọc được file Excel (.xlsx): " + e.getMessage());
        }
        return rows;
    }

    private static String cellText(Cell cell) {
        if (cell == null || cell.getType() == CellType.EMPTY) {
            return "";
        }
        try {
            switch (cell.getType()) {
                case NUMBER -> {
                    String fmt = cell.getDataFormatString();
                    if (fmt != null && looksLikeDateFormat(fmt)) {
                        return cell.asDate().format(EXCEL_DATE);
                    }
                    BigDecimal number = cell.asNumber();
                    return number.stripTrailingZeros().toPlainString();
                }
                case BOOLEAN -> {
                    return String.valueOf(cell.asBoolean());
                }
                case ERROR -> {
                    return "";
                }
                default -> {
                    return cell.asString() == null ? "" : cell.asString();
                }
            }
        } catch (RuntimeException e) {
            return cell.getText() == null ? "" : cell.getText();
        }
    }

    private static boolean looksLikeDateFormat(String format) {
        String f = format.toLowerCase();
        return !f.equals("general") && (f.contains("yy") || f.contains("dd") || f.contains("mm") && f.contains("d"));
    }

    /** Chặn "zip bomb": tổng dung lượng giải nén và tỉ lệ nén bất thường. */
    private static void checkZipSafety(byte[] content, long maxUncompressedBytes) {
        long total = 0;
        byte[] buffer = new byte[8192];
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(content))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                int n;
                while ((n = zip.read(buffer)) > 0) {
                    total += n;
                    if (total > maxUncompressedBytes) {
                        throw new ImportException("File Excel quá lớn sau khi giải nén.");
                    }
                }
            }
        } catch (IOException e) {
            throw new ImportException("File Excel không hợp lệ.");
        }
        if (total > content.length * MAX_RATIO && total > 10_000_000) {
            throw new ImportException("File Excel có tỉ lệ nén bất thường, từ chối đọc.");
        }
    }
}
