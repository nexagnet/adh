package vn.shop.crm.invite;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import vn.shop.crm.AbstractPostgresTest;
import vn.shop.crm.common.ImportException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Mã QR/link chung in trên giấy: tự chọn nhóm còn chỗ, nhớ trình duyệt, chặn quét lặp. Postgres thật. */
class EntryAcceptanceTest extends AbstractPostgresTest {

    @Autowired
    EntryService entries;
    @Autowired
    GroupService groups;
    @Autowired
    PrintTexts texts;
    @Autowired
    vn.shop.crm.contact.ContactService contacts;
    @Autowired
    JdbcClient jdbc;

    private long openGroup(String name, String link, int target) {
        long id = groups.create(name, link, target);
        groups.setStatus(id, GroupStatus.OPEN);
        return id;
    }

    private long entryCount() {
        return jdbc.sql("SELECT count(*) FROM open_entry").query(Long.class).single();
    }

    @Test
    void quetLanDauVaoNhomConChoVaNhoTrinhDuyet() {
        openGroup("Nhóm A", "https://zalo.me/g/aaa", 5);

        EntryService.Result first = entries.enter(null, "1.1.1.1");
        assertThat(first.outcome()).isEqualTo(EntryService.Outcome.REDIRECT);
        assertThat(first.url()).isEqualTo("https://zalo.me/g/aaa");
        assertThat(first.visitor()).matches("[0-9a-f]{32}");
        assertThat(entryCount()).isEqualTo(1);

        // quét lại bằng cùng trình duyệt: về đúng nhóm cũ, không đếm thêm
        EntryService.Result again = entries.enter(first.visitor(), "1.1.1.1");
        assertThat(again.url()).isEqualTo("https://zalo.me/g/aaa");
        assertThat(again.visitor()).isEqualTo(first.visitor());
        assertThat(entryCount()).isEqualTo(1);
        assertThat(groups.list().get(0).pendingClicks()).isEqualTo(1);
    }

    @Test
    void chiaDeuGiuaCacNhomVaDungKhiDay() {
        // mục tiêu 1 khách, mỗi lượt chờ tính 0,5 người: mỗi nhóm nhận 2 lượt là đầy
        openGroup("Nhóm A", "https://zalo.me/g/aaa", 1);
        openGroup("Nhóm B", "https://zalo.me/g/bbb", 1);

        var urls = new java.util.ArrayList<String>();
        for (int i = 0; i < 4; i++) {
            var r = entries.enter(null, "10.0.0." + i);
            assertThat(r.outcome()).isEqualTo(EntryService.Outcome.REDIRECT);
            urls.add(r.url());
        }
        assertThat(urls).containsExactly("https://zalo.me/g/aaa", "https://zalo.me/g/bbb",
                "https://zalo.me/g/aaa", "https://zalo.me/g/bbb");

        var full = entries.enter(null, "10.0.0.9");
        assertThat(full.outcome()).isEqualTo(EntryService.Outcome.NO_GROUP);
        assertThat(entryCount()).isEqualTo(4);
        assertThat(groups.pickGroup()).isEmpty();
    }

    @Test
    void meDemLaiThiLuotChoDuocTinhLai() {
        long a = openGroup("Nhóm A", "https://zalo.me/g/aaa", 2);
        entries.enter(null, "2.2.2.2");
        entries.enter(null, "2.2.2.3");
        assertThat(groups.find(a).orElseThrow().pendingClicks()).isEqualTo(2);

        groups.recount(a, 2);
        var g = groups.find(a).orElseThrow();
        assertThat(g.pendingClicks()).isZero();
        assertThat(g.actual()).isEqualTo(2);
        assertThat(g.full()).isTrue();
        assertThat(entries.enter(null, "2.2.2.4").outcome()).isEqualTo(EntryService.Outcome.NO_GROUP);
    }

    @Test
    void khongCoNhomNaoThiBaoChuaCoChoVaKhongGhiGi() {
        groups.create("Nhóm chưa mở", "https://zalo.me/g/ccc", 5);
        var r = entries.enter(null, "3.3.3.3");
        assertThat(r.outcome()).isEqualTo(EntryService.Outcome.NO_GROUP);
        assertThat(entryCount()).isZero();
    }

    @Test
    void nhomBiDongThiKhachCuSangNhomKhac() {
        long a = openGroup("Nhóm A", "https://zalo.me/g/aaa", 5);
        var first = entries.enter(null, "4.4.4.4");
        assertThat(first.url()).isEqualTo("https://zalo.me/g/aaa");

        groups.setStatus(a, GroupStatus.CLOSED);
        // chưa có nhóm khác: báo chưa có chỗ
        assertThat(entries.enter(first.visitor(), "4.4.4.4").outcome()).isEqualTo(EntryService.Outcome.NO_GROUP);

        openGroup("Nhóm B", "https://zalo.me/g/bbb", 5);
        var moved = entries.enter(first.visitor(), "4.4.4.4");
        assertThat(moved.url()).isEqualTo("https://zalo.me/g/bbb");
        assertThat(entryCount()).isEqualTo(1);
    }

    @Test
    void maTrinhDuyetLaKhongHopLeThiTinhLaKhachMoi() {
        openGroup("Nhóm A", "https://zalo.me/g/aaa", 5);
        var r = entries.enter("'; DROP TABLE open_entry; --", "5.5.5.5");
        assertThat(r.outcome()).isEqualTo(EntryService.Outcome.REDIRECT);
        assertThat(r.visitor()).matches("[0-9a-f]{32}");
        assertThat(entryCount()).isEqualTo(1);
    }

    @Test
    void mucNhieuLuotMoiTuMotDiaChiBiChan() {
        openGroup("Nhóm lớn", "https://zalo.me/g/big", 500);
        String firstVisitor = null;
        for (int i = 0; i < 20; i++) {
            var r = entries.enter(null, "9.9.9.9");
            assertThat(r.outcome()).isEqualTo(EntryService.Outcome.REDIRECT);
            firstVisitor = r.visitor();
        }
        assertThat(entries.enter(null, "9.9.9.9").outcome()).isEqualTo(EntryService.Outcome.TOO_MANY);
        // địa chỉ khác vẫn vào được; trình duyệt đã biết thì quét lại không bị tính vào giới hạn
        assertThat(entries.enter(null, "8.8.8.8").outcome()).isEqualTo(EntryService.Outcome.REDIRECT);
        assertThat(entries.enter(firstVisitor, "9.9.9.9").outcome()).isEqualTo(EntryService.Outcome.REDIRECT);
        assertThat(entryCount()).isEqualTo(21);
    }

    @Test
    void chuTrenGiayCoMacDinhSuaDuocVaKiemTraDoDai() {
        assertThat(texts.rendered("Cửa hàng An").get("intro")).startsWith("Cửa hàng An có một nhóm Zalo nhỏ");
        assertThat(texts.current().get("title")).isEqualTo("Cảm ơn bạn đã mua hàng!");

        texts.save(java.util.Map.of("title", "  Cảm ơn quý khách  ", "footer", "Hẹn gặp lại"), "mẹ");
        assertThat(texts.current().get("title")).isEqualTo("Cảm ơn quý khách");
        assertThat(texts.current().get("footer")).isEqualTo("Hẹn gặp lại");
        assertThat(texts.current().get("note")).contains("không bắt buộc");

        assertThatThrownBy(() -> texts.save(java.util.Map.of("title", "x".repeat(81)), "mẹ"))
                .isInstanceOf(ImportException.class).hasMessageContaining("quá dài");
        assertThatThrownBy(() -> texts.save(java.util.Map.of("title", "   "), "mẹ"))
                .isInstanceOf(ImportException.class).hasMessageContaining("không được để trống");
        // lỗi một ô thì không ghi ô nào
        assertThatThrownBy(() -> texts.save(java.util.Map.of("title", "Mới", "intro", "y".repeat(301)), "mẹ"))
                .isInstanceOf(ImportException.class);
        assertThat(texts.current().get("title")).isEqualTo("Cảm ơn quý khách");

        texts.reset("mẹ");
        assertThat(texts.current().get("title")).isEqualTo("Cảm ơn bạn đã mua hàng!");
    }

    @Test
    void khachTuXinNgungNhanTinBangSoDienThoai() {
        assertThatThrownBy(() -> contacts.optOutByPhone("12345", "thử"))
                .isInstanceOf(ImportException.class).hasMessageContaining("Số điện thoại chưa đúng");
        assertThat(contacts.isSuppressed("+84912345678")).isFalse();

        contacts.optOutByPhone("0912 345 678", "trang giấy");
        assertThat(contacts.isSuppressed("+84912345678")).isTrue();
        contacts.optOutByPhone("0912345678", "trang giấy"); // gọi lặp lại không sao
        assertThat(jdbc.sql("SELECT count(*) FROM suppression").query(Long.class).single()).isEqualTo(1);
        // danh sách từ chối chỉ lưu mã băm, không có số điện thoại
        assertThat(jdbc.sql("SELECT phone_hash FROM suppression").query(String.class).single()).doesNotContain("912");
    }

    @Test
    void nhomCanDemLaiSauBayNgay() {
        long a = openGroup("Nhóm A", "https://zalo.me/g/aaa", 5);
        assertThat(groups.find(a).orElseThrow().needsRecount()).isTrue(); // chưa từng đếm
        groups.recount(a, 3);
        assertThat(groups.find(a).orElseThrow().needsRecount()).isFalse();
        jdbc.sql("UPDATE zalo_group SET counted_at = now() - interval '8 days' WHERE id = :id").param("id", a).update();
        assertThat(groups.find(a).orElseThrow().needsRecount()).isTrue();
        assertThat(groups.find(a).orElseThrow().spotsLeft()).isEqualTo(2);
    }
}
