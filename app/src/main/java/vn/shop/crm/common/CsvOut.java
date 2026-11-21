package vn.shop.crm.common;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Xuất CSV UTF-8 có BOM (để Excel hiển thị đúng tiếng Việt) và chống "CSV injection". */
public final class CsvOut {

    private static final byte[] BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    private CsvOut() {
    }

    public static byte[] write(List<String> header, List<List<String>> rows) {
        try {
            StringWriter out = new StringWriter();
            try (CSVPrinter printer = new CSVPrinter(out, CSVFormat.DEFAULT)) {
                printer.printRecord(header);
                for (List<String> row : rows) {
                    printer.printRecord(row.stream().map(CsvOut::safe).toList());
                }
            }
            byte[] body = out.toString().getBytes(StandardCharsets.UTF_8);
            byte[] result = new byte[BOM.length + body.length];
            System.arraycopy(BOM, 0, result, 0, BOM.length);
            System.arraycopy(body, 0, result, BOM.length, body.length);
            return result;
        } catch (IOException e) {
            throw new IllegalStateException("Không thể tạo CSV", e);
        }
    }

    /** Ô bắt đầu bằng = + - @ có thể bị Excel hiểu là công thức; thêm dấu nháy để vô hiệu hóa. */
    static String safe(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        char first = value.charAt(0);
        if (first == '=' || first == '+' || first == '-' || first == '@' || first == '\t' || first == '\r') {
            return "'" + value;
        }
        return value;
    }
}
