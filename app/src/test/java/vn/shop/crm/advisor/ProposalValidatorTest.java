package vn.shop.crm.advisor;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Kiểm cứng đầu ra AI đề xuất chiến dịch: đề xuất không bao giờ vượt giới hạn hệ thống, độ tin cậy bị chặn trần, chữ không có link/số điện thoại/lời hứa. */
class ProposalValidatorTest {

    static final LocalDate TODAY = LocalDate.of(2026, 11, 23);

    static AdvisorSnapshot snapshot(long sent, boolean weather) {
        var funnel = new AdvisorSnapshot.Funnel(sent, 0, 0, 0);
        var area = new AdvisorSnapshot.Area("S:DONG_NAM_BO", "Đông Nam Bộ", "Miền Nam", 100, 100, 40, 10, 5,
                new AdvisorSnapshot.Recency(10, 20, 30, 40, 0), funnel,
                weather ? new AdvisorSnapshot.Weather(25, 35, 10, 100) : null, List.of());
        var area2 = new AdvisorSnapshot.Area("S:DB_SONG_HONG", "Đồng bằng sông Hồng", "Miền Bắc", 100, 100, 20, 10, 5,
                new AdvisorSnapshot.Recency(10, 20, 30, 40, 0), funnel, null, List.of());
        return new AdvisorSnapshot("2026-11-23", new AdvisorSnapshot.Calendar(11, List.of(), List.of()),
                new AdvisorSnapshot.Limits(10, 120, "08:00", "20:00", 300),
                new AdvisorSnapshot.Overall(200, 200, 60, 20, 0, 0, funnel), List.of(area, area2), List.of());
    }

    static String one(String... overrides) {
        java.util.Map<String, String> m = new java.util.LinkedHashMap<>();
        m.put("title", "\"Đợt nóng, Đông Nam Bộ\"");
        m.put("area", "\"S:DONG_NAM_BO\"");
        m.put("batch_size", "30");
        m.put("start_date", "\"2026-11-25\"");
        m.put("end_date", "\"2026-12-02\"");
        m.put("window_from", "\"09:00\"");
        m.put("window_to", "\"11:00\"");
        m.put("daily_cap", "10");
        m.put("message_angle", "\"Nhấn mạnh mát khi đi làm ngoài trời\"");
        m.put("reasons", "[\"Dự báo nóng 35 độ ở vùng có 100 khách\"]");
        m.put("risks", "[\"Chưa có lịch sử phản hồi\"]");
        m.put("confidence", "0.55");
        for (int i = 0; i < overrides.length; i += 2) {
            m.put(overrides[i], overrides[i + 1]);
        }
        StringBuilder sb = new StringBuilder("{");
        m.forEach((k, v) -> sb.append(sb.length() > 1 ? "," : "").append('"').append(k).append("\":").append(v));
        return sb.append('}').toString();
    }

    static String wrap(String... proposals) {
        return "{\"proposals\":[" + String.join(",", proposals) + "],\"skip_reason\":null}";
    }

    static ProposalValidator.Outcome check(String json) {
        return ProposalValidator.validate(json, snapshot(0, true), TODAY);
    }

    private static void rejected(String json, String partOfReason) {
        var o = check(wrap(json));
        assertThat(o.invalidFormat()).isFalse();
        assertThat(o.accepted()).isEmpty();
        assertThat(o.rejected()).singleElement().asString().contains(partOfReason);
    }

    @Test
    void deXuatHopLeDuocNhan() {
        var o = check(wrap(one()));
        assertThat(o.accepted()).hasSize(1);
        AdvisorProposal p = o.accepted().getFirst();
        assertThat(p.area()).isEqualTo("S:DONG_NAM_BO");
        assertThat(p.batchSize()).isEqualTo(30);
        assertThat(p.startOn()).isEqualTo(LocalDate.of(2026, 11, 25));
        assertThat(p.confidence()).isEqualTo(0.55);
        assertThat(p.confidenceCapped()).isFalse();
    }

    @Test
    void doTinCayBiChanTranKhiChuaCoLichSuHoacThoiTiet() {
        // chưa đủ 30 tin đã nhắn: tối đa 0,6
        var p = check(wrap(one("confidence", "0.95"))).accepted().getFirst();
        assertThat(p.confidence()).isEqualTo(0.6);
        assertThat(p.confidenceCapped()).isTrue();
        // đủ lịch sử và có thời tiết: giữ nguyên
        // (bắt đầu ngày mai, còn trong dự báo 48 giờ; bắt đầu xa hơn thì còn bị trần dự báo, xem test riêng)
        var ok = ProposalValidator.validate(wrap(one("start_date", "\"2026-11-24\"", "confidence", "0.95")), snapshot(30, true), TODAY).accepted().getFirst();
        assertThat(ok.confidence()).isEqualTo(0.95);
        assertThat(ok.confidenceCapped()).isFalse();
        // đủ lịch sử nhưng khu vực chưa có dự báo: tối đa 0,7
        var noWeather = ProposalValidator.validate(wrap(one("area", "\"S:DB_SONG_HONG\"", "batch_size", "10", "start_date", "\"2026-11-24\"", "confidence", "0.95")),
                snapshot(30, true), TODAY).accepted().getFirst();
        assertThat(noWeather.confidence()).isEqualTo(0.7);
    }

    @Test
    void khongBaoGioVuotGioiHanHeThong() {
        rejected(one("area", "\"S:TAY_BAC\""), "khu vực không có trong số liệu");
        rejected(one("batch_size", "41"), "batch_size phải từ 1 đến 40"); // chỉ 40 khách đủ điều kiện
        rejected(one("batch_size", "0"), "batch_size");
        rejected(one("daily_cap", "11"), "daily_cap phải từ 1 đến 10");
        rejected(one("window_from", "\"07:00\""), "khung giờ phải nằm trong 08:00 đến 20:00");
        rejected(one("window_to", "\"20:30\""), "khung giờ phải nằm trong");
        rejected(one("window_from", "\"09:00\"", "window_to", "\"09:30\""), "rộng ít nhất 60 phút");
        rejected(one("start_date", "\"2026-11-22\""), "ngày bắt đầu");
        rejected(one("start_date", "\"2027-01-01\"", "end_date", "\"2027-01-05\""), "ngày bắt đầu");
        rejected(one("end_date", "\"2026-12-10\""), "tối đa 14 ngày");
        rejected(one("end_date", "\"2026-11-24\""), "không trước ngày bắt đầu");
        rejected(one("start_date", "\"25/11/2026\""), "YYYY-MM-DD");
    }

    @Test
    void batchKhongQuaTranChienDich() {
        // trần chiến dịch nhỏ hơn số khách đủ điều kiện thì lấy trần chiến dịch
        AdvisorSnapshot s = snapshot(0, true);
        AdvisorSnapshot small = new AdvisorSnapshot(s.generatedOn(), s.calendar(),
                new AdvisorSnapshot.Limits(10, 120, "08:00", "20:00", 25), s.overall(), s.areas(), s.sendSlots());
        var o = ProposalValidator.validate(wrap(one("batch_size", "30")), small, TODAY);
        assertThat(o.rejected()).singleElement().asString().contains("1 đến 25");
    }

    @Test
    void chuKhongCoLinkSoDienThoaiHayLoiHua() {
        rejected(one("reasons", "[\"Xem https://example.com\"]"), "đường dẫn");
        rejected(one("reasons", "[\"Xem shop.vn\"]"), "đường dẫn");
        rejected(one("reasons", "[\"Gọi 0912345678 để hỏi\"]"), "số điện thoại");
        rejected(one("message_angle", "\"Cam kết hoàn tiền nếu không ưng\""), "chữ không được dùng");
        rejected(one("message_angle", "\"Tặng quà cho khách\""), "chữ không được dùng");
        rejected(one("title", "\"Đảm bảo bán chạy\""), "chữ không được dùng");
        rejected(one("risks", "[\"x\\u0007y\"]"), "ký tự điều khiển");
        rejected(one("reasons", "[]"), "reasons cần 1 đến 4");
        rejected(one("title", "\"\""), "title");
    }

    @Test
    void saiKhuonMotDeXuatThiBoRiengDeXuatDo() {
        var o = check(wrap(one(), one("area", "\"S:DONG_NAM_BO\"", "batch_size", "5"), "{\"title\":\"thiếu khóa\"}"));
        assertThat(o.accepted()).hasSize(1);
        assertThat(o.rejected()).hasSize(2);
        assertThat(o.rejected().get(0)).contains("Đề xuất 2").contains("trùng khu vực");
        assertThat(o.rejected().get(1)).contains("Đề xuất 3").contains("sai khuôn");
    }

    @Test
    void khuonSaiThiBoCaLuot() {
        assertThat(check("không phải json").invalidFormat()).isTrue();
        assertThat(check("[]").invalidFormat()).isTrue();
        assertThat(check("{\"proposals\":[]}").invalidFormat()).isTrue();
        assertThat(check("{\"proposals\":[],\"skip_reason\":null,\"extra\":1}").invalidFormat()).isTrue();
        assertThat(check("{\"proposals\":\"x\",\"skip_reason\":null}").invalidFormat()).isTrue();
        assertThat(check("{\"proposals\":[],\"skip_reason\":5}").invalidFormat()).isTrue();
        // quá 3 đề xuất
        assertThat(check(wrap(one(), one(), one(), one())).invalidFormat()).isTrue();
        // kiểu sai: số thành chữ, số thực thành số nguyên
        assertThat(check(wrap(one("batch_size", "\"30\""))).accepted()).isEmpty();
        assertThat(check(wrap(one("daily_cap", "5.5"))).accepted()).isEmpty();
        assertThat(check(wrap(one("confidence", "1.5"))).accepted()).isEmpty();
    }

    @Test
    void khongDeXuatGiKemLyDo() {
        var o = check("{\"proposals\":[],\"skip_reason\":\"Chưa đủ dữ liệu\"}");
        assertThat(o.invalidFormat()).isFalse();
        assertThat(o.accepted()).isEmpty();
        assertThat(o.rejected()).isEmpty();
        assertThat(o.skipReason()).isEqualTo("Chưa đủ dữ liệu");
        // lý do có link thì bị ẩn
        assertThat(check("{\"proposals\":[],\"skip_reason\":\"vào http://x.com\"}").skipReason()).contains("đã ẩn");
    }

    @Test
    void batchKhongDuocVuotHanMucNgayNhanSoNgay() {
        // 8 ngày x 10 khách = 80 nên 30 là hợp lệ; 2 ngày x 10 = 20 nên 30 không được
        assertThat(check(wrap(one())).accepted()).hasSize(1);
        rejected(one("end_date", "\"2026-11-26\""), "vượt số khách gửi được");
    }

    @Test
    void khongDeXuatTrungKhuVucVaThoiGianVoiChienDichDangCho() {
        var base = snapshot(0, true);
        var running = new AdvisorSnapshot.CampaignInfo("R:NAM", "DANG_GUI", "2026-11-22", "2026-11-30", "09:00", "11:00", 10, 40, 25, 15);
        var snap = new AdvisorSnapshot(base.generatedOn(), base.calendar(), base.limits(), base.overall(), base.areas(), base.sendSlots(), List.of(running));
        var o = ProposalValidator.validate(wrap(one()), snap, TODAY);
        assertThat(o.accepted()).isEmpty();
        assertThat(o.rejected().getFirst()).contains("trùng khu vực và thời gian");
        // sau khi chiến dịch kia kết thúc thì được
        var later = ProposalValidator.validate(wrap(one("start_date", "\"2026-12-01\"", "end_date", "\"2026-12-09\"")), snap, TODAY);
        assertThat(later.accepted()).hasSize(1);
        // khu vực khác miền thì không vướng
        var other = ProposalValidator.validate(wrap(one("area", "\"S:DB_SONG_HONG\"", "batch_size", "15")), snap, TODAY);
        assertThat(other.accepted()).hasSize(1);
        // chiến dịch đã xong hoặc không có khách chờ thì không chặn
        var done = new AdvisorSnapshot.CampaignInfo("R:NAM", "XONG", "2026-11-22", "2026-11-30", null, null, 10, 40, 0, 40);
        var snap2 = new AdvisorSnapshot(base.generatedOn(), base.calendar(), base.limits(), base.overall(), base.areas(), base.sendSlots(), List.of(done));
        assertThat(ProposalValidator.validate(wrap(one()), snap2, TODAY).accepted()).hasSize(1);
        // chiến dịch đã lưu trữ, hết hạn hoặc bỏ dở còn khách chờ cũng không chặn
        for (String state : new String[]{"LUU_TRU", "HET_HAN", "BO_DO"}) {
            var dead = new AdvisorSnapshot.CampaignInfo("R:NAM", state, "2026-11-22", "2026-11-30", "09:00", "11:00", 10, 40, 25, 15);
            var snap3 = new AdvisorSnapshot(base.generatedOn(), base.calendar(), base.limits(), base.overall(), base.areas(), base.sendSlots(), List.of(dead));
            assertThat(ProposalValidator.validate(wrap(one()), snap3, TODAY).accepted()).as(state).hasSize(1);
        }
    }

    @Test
    void nhungNumTuyChonDuocNhanVaDoiRaDungGiaTri() {
        var o = check(wrap(one("weekdays", "[1,2,3,4,5]", "pick_order", "\"NEWEST_PURCHASE\"",
                "min_days_since_purchase", "0", "max_days_since_purchase", "90")));
        assertThat(o.accepted()).hasSize(1);
        var p = o.accepted().getFirst();
        assertThat(p.weekdays()).isEqualTo(31);
        assertThat(p.pickOrder()).isEqualTo("NEWEST_PURCHASE");
        assertThat(p.minDays()).isZero();
        assertThat(p.maxDays()).isEqualTo(90);
        // không khai thì mặc định hệ thống; đủ 7 ngày thì là mọi ngày (null)
        var d = check(wrap(one())).accepted().getFirst();
        assertThat(d.weekdays()).isNull();
        assertThat(d.pickOrder()).isEqualTo("OLDEST_APPROVED");
        assertThat(d.minDays()).isNull();
        assertThat(check(wrap(one("weekdays", "[1,2,3,4,5,6,7]"))).accepted().getFirst().weekdays()).isNull();
    }

    @Test
    void numTuyChonSaiThiBoDeXuat() {
        rejected(one("weekdays", "[0]"), "weekdays");
        rejected(one("weekdays", "[8]"), "weekdays");
        rejected(one("weekdays", "[]"), "weekdays");
        rejected(one("weekdays", "\"1\""), "weekdays");
        rejected(one("pick_order", "\"RANDOM\""), "pick_order");
        rejected(one("min_days_since_purchase", "-5"), "số nguyên từ 0");
        rejected(one("max_days_since_purchase", "99999"), "số nguyên từ 0");
        rejected(one("min_days_since_purchase", "100", "max_days_since_purchase", "30"), "không được nhỏ hơn");
        rejected(one("khoa_la", "1"), "sai khuôn");
        // hai ngày được chọn (thứ Tư 26/11 chỉ có 1 ngày trong 25 đến 26/11): batch phải theo số ngày gửi thật
        rejected(one("end_date", "\"2026-11-26\"", "weekdays", "[3]", "batch_size", "15"), "ngày gửi");
        // khoảng ngày không chứa ngày nào được chọn
        rejected(one("start_date", "\"2026-11-25\"", "end_date", "\"2026-11-26\"", "weekdays", "[1]", "batch_size", "5"), "không có ngày nào");
    }

    @Test
    void nhomHangDeXuatChiNhanBaGiaTriHopLe() {
        assertThat(check(wrap(one("product_group", "\"PIN\""))).accepted().getFirst().productGroup()).isEqualTo("PIN");
        assertThat(check(wrap(one("product_group", "null"))).accepted().getFirst().productGroup()).isNull();
        assertThat(check(wrap(one())).accepted().getFirst().productGroup()).isNull();
        rejected(one("product_group", "\"CHUA_RO\""), "product_group");
        rejected(one("product_group", "\"ao\""), "product_group");
        rejected(one("product_group", "1"), "product_group");
    }

    @Test
    void ngayBatDauNgoaiVungCoDuBaoThiDoTinCayBiChanTran() {
        var base = snapshot(100, true); // đủ lịch sử để chỉ còn trần theo dự báo
        var weather = new AdvisorSnapshot.Weather(25, 35, 10, 100, List.of(
                new AdvisorSnapshot.OutlookDay(0, 25, 35, 0), new AdvisorSnapshot.OutlookDay(1, 25, 35, 0),
                new AdvisorSnapshot.OutlookDay(2, 25, 35, 0), new AdvisorSnapshot.OutlookDay(3, 25, 35, 0)));
        var a = base.areas().getFirst();
        var withOutlook = new AdvisorSnapshot.Area(a.code(), a.label(), a.region(), a.customers(), a.oneTime(), a.eligible(),
                a.awaitingApproval(), a.newLast30Days(), a.recency(), a.funnel(), weather, a.topProvinces());
        var snap = new AdvisorSnapshot(base.generatedOn(), base.calendar(), base.limits(), base.overall(),
                List.of(withOutlook, base.areas().get(1)), base.sendSlots());
        // 23/11 + 3 ngày = 26/11 còn trong dự báo (4 ngày: 0 đến 3): AI nói 0,9 và được chặn ở trần chung 1,0
        var inside = ProposalValidator.validate(wrap(one("start_date", "\"2026-11-26\"", "end_date", "\"2026-12-03\"", "confidence", "0.9")), snap, TODAY);
        assertThat(inside.accepted().getFirst().confidence()).isEqualTo(0.9);
        // 27/11 đã ngoài vùng có dự báo: bị chặn ở 0,65
        var outside = ProposalValidator.validate(wrap(one("start_date", "\"2026-11-27\"", "end_date", "\"2026-12-04\"", "confidence", "0.9")), snap, TODAY);
        assertThat(outside.accepted().getFirst().confidence()).isEqualTo(ProposalValidator.CAP_BEYOND_FORECAST);
        assertThat(outside.accepted().getFirst().confidenceCapped()).isTrue();
    }

    /** Bản chụp có mục "audience" (cho phép lọc theo đơn đổi và theo tiền thu được tùy cờ). */
    static AdvisorSnapshot withAudience(boolean exchange, boolean settlement) {
        var b = snapshot(0, true);
        var audience = new AdvisorSnapshot.Audience(exchange, settlement, 12L, 11L, null, null, null, null, null, null, null, null, null);
        return new AdvisorSnapshot(b.generatedOn(), b.calendar(), b.limits(), b.overall(), b.areas(), b.sendSlots(), List.of(), List.of(), List.of(),
                new AdvisorSnapshot.Insights(null, null, null, null, null, null, audience));
    }

    private static ProposalValidator.Outcome checkWith(AdvisorSnapshot snap, String json) {
        return ProposalValidator.validate(wrap(json), snap, TODAY);
    }

    @Test
    void boLocCuaAiDuocDocDungKhuonVaKhongCoThiLaRong() {
        var none = check(wrap(one())).accepted().getFirst();
        assertThat(none.filter().any()).isFalse();
        var p = check(wrap(one("filter", "{\"purchases_max\":3,\"cancelled\":\"NO\",\"no_open_orders\":true,\"bought\":[\"AO_DIEU_HOA\"],"
                + "\"not_bought\":[\"PIN\"],\"max_shipments\":4}"))).accepted().getFirst();
        assertThat(p.filter().maxPurchases()).isEqualTo(3);
        assertThat(p.filter().multi()).isTrue();
        assertThat(p.filter().cancelled()).isEqualTo(vn.shop.crm.contact.CustomerFilter.Tri.NO);
        assertThat(p.filter().noOpenOrders()).isTrue();
        assertThat(p.filter().maxShipments()).isEqualTo(4);
        assertThat(check(wrap(one("filter", "null"))).accepted().getFirst().filter().any()).isFalse();
        assertThat(check(wrap(one("filter", "{}"))).accepted().getFirst().filter().any()).isFalse();
    }

    @Test
    void boLocSaiKhuonBiTuChoiKhongSuaHo() {
        rejected(one("filter", "{\"khoa_la\":1}"), "filter");
        rejected(one("filter", "\"mua nhieu\""), "filter");
        rejected(one("filter", "[1]"), "filter");
        rejected(one("filter", "{\"purchases_max\":\"3\"}"), "purchases");
        rejected(one("filter", "{\"purchases_max\":2.5}"), "purchases");
        rejected(one("filter", "{\"purchases_min\":5,\"purchases_max\":2}"), "filter");
        rejected(one("filter", "{\"purchases_max\":999}"), "filter");
        rejected(one("filter", "{\"cancelled\":\"MAYBE\"}"), "cancelled");
        rejected(one("filter", "{\"no_open_orders\":\"yes\"}"), "no_open_orders");
        rejected(one("filter", "{\"bought\":[\"GIAY_DEP\"]}"), "bought");
        rejected(one("filter", "{\"bought\":\"PIN\"}"), "bought");
        rejected(one("filter", "{\"bought_mode\":\"SOME\"}"), "bought_mode");
        rejected(one("filter", "{\"max_shipments\":\"nhieu\"}"), "max_shipments");
    }

    @Test
    void khongDungChungNhomHangCuVaBoughtMoi() {
        rejected(one("product_group", "\"PIN\"", "filter", "{\"bought\":[\"AO_DIEU_HOA\"]}"), "product_group");
    }

    @Test
    void locTheoDonDoiChiKhiChuShopDaCaiCachNhanBiet() {
        String f = "{\"exchange\":\"NO\"}";
        // chưa cài (không có mục audience, hoặc exchangeReady = false): bị từ chối
        var noAudience = ProposalValidator.validate(wrap(one("filter", f)), snapshot(0, true), TODAY);
        assertThat(noAudience.accepted()).isEmpty();
        assertThat(noAudience.rejected()).singleElement().asString().contains("đơn đổi");
        var notReady = checkWith(withAudience(false, false), one("filter", f));
        assertThat(notReady.accepted()).isEmpty();
        // đã cài: nhận; ANY thì không cần
        assertThat(checkWith(withAudience(true, false), one("filter", f)).accepted()).hasSize(1);
        assertThat(checkWith(withAudience(false, false), one("filter", "{\"exchange\":\"ANY\"}")).accepted()).hasSize(1);
    }

    @Test
    void locTheoTienChiKhiDaCoFileDoiSoat() {
        String f = "{\"cod_min\":300000}";
        assertThat(checkWith(withAudience(false, false), one("filter", f)).accepted()).isEmpty();
        assertThat(checkWith(withAudience(false, false), one("filter", f)).rejected()).singleElement().asString().contains("đối soát");
        assertThat(checkWith(withAudience(false, true), one("filter", f)).accepted()).hasSize(1);
        assertThat(checkWith(withAudience(false, true), one("filter", "{\"cod_min\":\"1\"}")).accepted()).isEmpty();
    }

    @Test
    void chienDichMuaNhieuLanKhongBiChanLoBangSoKhachMotLan() {
        // khu vực chỉ có 100 khách mua đúng 1 lần đủ điều kiện; chiến dịch mua nhiều lần được xin lô lớn hơn (vẫn không quá trần chiến dịch 300)
        assertThat(check(wrap(one("end_date", "\"2026-12-08\"", "batch_size", "130"))).accepted()).isEmpty();
        assertThat(check(wrap(one("end_date", "\"2026-12-08\"", "batch_size", "130", "filter", "{\"purchases_max\":3}"))).accepted()).hasSize(1);
        assertThat(check(wrap(one("end_date", "\"2026-12-08\"", "batch_size", "301", "filter", "{\"purchases_max\":3}"))).accepted()).isEmpty();
        // chỉ đặt purchases_max = 1 thì vẫn bị chặn như thường
        assertThat(check(wrap(one("end_date", "\"2026-12-08\"", "batch_size", "130", "filter", "{\"purchases_max\":1}"))).accepted()).isEmpty();
    }
}
