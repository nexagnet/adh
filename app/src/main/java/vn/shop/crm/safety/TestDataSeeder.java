package vn.shop.crm.safety;

import org.springframework.stereotype.Service;
import vn.shop.crm.common.ImportException;
import vn.shop.crm.common.PhoneNormalizer;
import vn.shop.crm.imports.ImportService;
import vn.shop.crm.shipping.Carrier;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Dựng khách và đơn J&T GIẢ từ các số thử đã khai báo ở chế độ thử, để chạy thử "như thật" mà không bắt người dùng nhập đơn tay.
 *
 * <p>Mỗi số thử chưa là khách được một đơn giả "Đã ký nhận" đi qua đúng đường nhập đơn J&T thật ({@link ImportService}), cùng một
 * tỉnh (để việc xếp nhóm theo khu vực có đủ người). Dữ liệu giả được đánh dấu rõ: mã vận đơn bắt đầu bằng {@value #TRACKING_PREFIX} và tên
 * người nhận là "Khách thử N". Chỉ chạy khi đang ở chế độ thử (không bao giờ ở chế độ chạy thật) và chỉ cho số trong danh sách thử. Không
 * xóa, không sửa khách nào đã có; số đã là khách thì bỏ qua và báo lại.
 *
 * <p>Số điện thoại KHÔNG nằm trong mã: lấy từ danh sách thử do người dùng khai báo trên giao diện.
 */
@Service
public class TestDataSeeder {

    public static final String TRACKING_PREFIX = "JTTEST";
    static final String PROVINCE_ADDRESS = "Dữ liệu thử, Ninh Kiều, Cần Thơ";
    private static final String HEADER = "Mã vận đơn,Mã đơn hàng,Thời gian tạo,Người nhận,Số điện thoại người nhận,Địa chỉ nhận,Trạng thái\n";
    private static final DateTimeFormatter CODE = DateTimeFormatter.ofPattern("yyMMddHHmm");
    private static final DateTimeFormatter CREATED = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");

    /** @param created số đơn giả đã dựng; @param skipped số thử đã là khách nên bỏ qua. */
    public record Result(int created, int skipped) {
        public String message() {
            if (created == 0 && skipped == 0) {
                return "Chưa có số thử nào để dựng khách thử. Hãy khai báo số thử trước.";
            }
            return "Đã dựng " + created + " khách thử (đơn J&T giả, mã vận đơn bắt đầu bằng " + TRACKING_PREFIX + ")"
                    + (skipped > 0 ? "; bỏ qua " + skipped + " số vì đã là khách trong hệ thống." : ".");
        }
    }

    private final TestMode mode;
    private final ImportService imports;
    private final Clock clock;

    public TestDataSeeder(TestMode mode, ImportService imports, Clock clock) {
        this.mode = mode;
        this.imports = imports;
        this.clock = clock;
    }

    public Result seed() {
        if (!mode.testOnly()) {
            throw new ImportException("Chỉ dựng được khách thử khi đang ở chế độ thử (khi chạy thật thì không).");
        }
        List<String> phones = new ArrayList<>();
        int skipped = 0;
        for (TestMode.Entry e : mode.numbers()) {
            if (e.customer()) {
                skipped++;
            } else {
                phones.add(e.phone());
            }
        }
        if (phones.isEmpty()) {
            return new Result(0, skipped);
        }
        LocalDateTime now = LocalDateTime.now(clock);
        StringBuilder csv = new StringBuilder(HEADER);
        int i = 0;
        for (String phone : phones) {
            i++;
            String tracking = TRACKING_PREFIX + now.format(CODE) + String.format("%02d", i);
            csv.append(tracking).append(',').append("TEST").append(now.format(CODE)).append(String.format("%02d", i)).append(',')
                    .append(now.minusDays(3).format(CREATED)).append(",Khách thử ").append(i).append(',')
                    .append(PhoneNormalizer.toDisplay(phone).replaceAll("\\s", "")).append(",\"").append(PROVINCE_ADDRESS)
                    .append("\",Đã ký nhận\n");
        }
        long batch = imports.createBatch(Carrier.JT_EXPRESS, "du-lieu-thu.csv", csv.toString().getBytes(StandardCharsets.UTF_8));
        var view = imports.open(batch, null);
        imports.commit(batch, new ImportService.Mapping(view.headerRow(), view.suggested()));
        return new Result(phones.size(), skipped);
    }
}
