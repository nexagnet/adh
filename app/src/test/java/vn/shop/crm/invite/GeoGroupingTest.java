package vn.shop.crm.invite;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import vn.shop.crm.AbstractPostgresTest;
import vn.shop.crm.common.ImportException;
import vn.shop.crm.customers.CustomerService;
import vn.shop.crm.geo.VnGeo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Xếp nhóm Zalo theo khu vực, hỏi tỉnh trên trang QR chung, lọc khách theo khu vực. Postgres thật. */
class GeoGroupingTest extends AbstractPostgresTest {

    @Autowired
    GroupService groups;
    @Autowired
    EntryService entries;
    @Autowired
    CustomerService customers;
    @Autowired
    JdbcClient jdbc;

    private long open(String name, String link, String scope) {
        long id = groups.create(name, link, 5, scope);
        groups.setStatus(id, GroupStatus.OPEN);
        return id;
    }

    private String pick(String province) {
        return groups.pickGroup(VnGeo.byCode(province).orElse(null)).map(GroupService.Group::name).orElse(null);
    }

    @Test
    void uuTienCungTinhRoiCungVungRoiCungMienRoiNhomChung() {
        open("Chung", "https://zalo.me/g/chung", null);
        open("Miền Bắc", "https://zalo.me/g/bac", "R:BAC");
        open("Đồng bằng sông Hồng", "https://zalo.me/g/dbsh", "S:DB_SONG_HONG");
        open("Hà Nội", "https://zalo.me/g/hn", "P:ha-noi");
        open("Miền Nam", "https://zalo.me/g/nam", "R:NAM");

        assertThat(pick("ha-noi")).isEqualTo("Hà Nội");
        assertThat(pick("hai-phong")).isEqualTo("Đồng bằng sông Hồng");
        assertThat(pick("lao-cai")).isEqualTo("Miền Bắc");
        assertThat(pick("tphcm")).isEqualTo("Miền Nam");
        // miền Trung không có nhóm riêng: rơi về nhóm chung
        assertThat(pick("da-nang")).isEqualTo("Chung");
        // chưa rõ khu vực: nhóm chung trước
        assertThat(pick(null)).isEqualTo("Chung");
    }

    @Test
    void cungThuTuUuTienThiNhomDaCoMaNhomZaloDungTruoc() {
        // "A" có id nhỏ hơn nên bình thường thắng; "B" có mã nhóm Zalo nên bộ nối tự thêm được, và đứng trước khi cùng thứ tự ưu tiên
        open("A", "https://zalo.me/g/a", "R:NAM");
        long b = open("B", "https://zalo.me/g/b", "R:NAM");
        assertThat(pick("can-tho")).isEqualTo("A");
        groups.setZaloGroupId(b, "555");
        assertThat(pick("can-tho")).isEqualTo("B");
        // khác thứ tự ưu tiên thì vẫn theo khu vực: nhóm cùng tỉnh (không có mã) thắng nhóm cùng miền có mã
        open("Cần Thơ", "https://zalo.me/g/ct", "P:can-tho");
        assertThat(pick("can-tho")).isEqualTo("Cần Thơ");
    }

    @Test
    void khongXepKhachVaoNhomCuaMienKhacTruKhiChoPhep() {
        open("Miền Nam", "https://zalo.me/g/nam", "R:NAM");
        assertThat(pick("ha-noi")).isNull();
        assertThat(pick("can-tho")).isEqualTo("Miền Nam");
        // khách chưa rõ khu vực vẫn được xếp (mọi nhóm đều nhận)
        assertThat(pick(null)).isEqualTo("Miền Nam");

        GroupService permissive = new GroupService(jdbc, new vn.shop.crm.common.AppSettings(jdbc), 0.5, true, 8);
        assertThat(permissive.pickGroup(VnGeo.byCode("ha-noi").orElseThrow())).isPresent();
    }

    @Test
    void trongCungThuTuUuTienChonNhomItNguoiHon() {
        long a = open("Nam A", "https://zalo.me/g/a", "R:NAM");
        open("Nam B", "https://zalo.me/g/b", "R:NAM");
        groups.recount(a, 3);
        assertThat(pick("tphcm")).isEqualTo("Nam B");
    }

    @Test
    void nhomDayThiBoQuaVaRoiVeNhomRongHon() {
        long hn = open("Hà Nội", "https://zalo.me/g/hn", "P:ha-noi");
        open("Chung", "https://zalo.me/g/chung", null);
        groups.recount(hn, 5);
        assertThat(pick("ha-noi")).isEqualTo("Chung");
    }

    @Test
    void khuVucNhomKhongHopLeBiTuChoi() {
        assertThatThrownBy(() -> groups.create("X", null, 5, "R:MARS")).isInstanceOf(ImportException.class);
        long id = groups.create("Y", null, 5, "P:can-tho");
        assertThat(groups.find(id).orElseThrow().scopeLabel()).isEqualTo("Cần Thơ");
        // sửa không truyền khu vực (hàm cũ) thì giữ nguyên
        groups.update(id, "Y2", null, 5, "ghi chú");
        assertThat(groups.find(id).orElseThrow().scopeCode()).isEqualTo("P:can-tho");
        groups.update(id, "Y2", null, 5, "ghi chú", "");
        assertThat(groups.find(id).orElseThrow().scope()).isNull();
    }

    @Test
    void quetMaChungChonTinhThiVaoNhomGan() {
        open("Miền Nam", "https://zalo.me/g/nam", "R:NAM");
        open("Miền Bắc", "https://zalo.me/g/bac", "R:BAC");

        var r = entries.enter(null, "1.1.1.1", "ha-noi");
        assertThat(r.outcome()).isEqualTo(EntryService.Outcome.REDIRECT);
        assertThat(r.url()).isEqualTo("https://zalo.me/g/bac");
        assertThat(jdbc.sql("SELECT province FROM open_entry WHERE visitor = :v").param("v", r.visitor())
                .query(String.class).single()).isEqualTo("ha-noi");

        // tỉnh lạ bị bỏ qua, coi như chưa chọn
        var u = entries.enter(null, "1.1.1.2", "khong-co");
        assertThat(u.outcome()).isEqualTo(EntryService.Outcome.REDIRECT);
    }

    @Test
    void chuaCoNhomHopThiGhiDongChoVaDuocXepKhiCoNhomMoi() {
        long bac = open("Miền Bắc", "https://zalo.me/g/bac", "R:BAC");

        var r = entries.enter(null, "3.3.3.3", "tphcm");
        assertThat(r.outcome()).isEqualTo(EntryService.Outcome.NO_GROUP);
        assertThat(r.stored()).isTrue();
        assertThat(groups.waitingByProvince()).hasSize(1);
        assertThat(groups.waitingByProvince().get(0).provinceName()).isEqualTo("TP. Hồ Chí Minh");
        // dòng chờ không tính vào "lượt bấm chờ" của nhóm nào
        assertThat(groups.find(bac).orElseThrow().pendingClicks()).isZero();

        // quét lại khi vẫn chưa có nhóm: không thêm dòng
        var again = entries.enter(r.visitor(), "3.3.3.3", null);
        assertThat(again.outcome()).isEqualTo(EntryService.Outcome.NO_GROUP);
        assertThat(jdbc.sql("SELECT count(*) FROM open_entry").query(Long.class).single()).isEqualTo(1);

        open("Miền Nam", "https://zalo.me/g/nam", "R:NAM");
        var later = entries.enter(r.visitor(), "3.3.3.3", null);
        assertThat(later.outcome()).isEqualTo(EntryService.Outcome.REDIRECT);
        assertThat(later.url()).isEqualTo("https://zalo.me/g/nam");
        assertThat(groups.waitingByProvince()).isEmpty();
        assertThat(jdbc.sql("SELECT count(*) FROM open_entry").query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void khongChonTinhVaKhongCoNhomThiKhongGhiGi() {
        var r = entries.enter(null, "4.4.4.4", null);
        assertThat(r.outcome()).isEqualTo(EntryService.Outcome.NO_GROUP);
        assertThat(r.stored()).isFalse();
        assertThat(jdbc.sql("SELECT count(*) FROM open_entry").query(Long.class).single()).isZero();
    }

    @Test
    void locKhachTheoKhuVucVaTinhLaiKhuVuc() {
        jdbc.sql("""
                INSERT INTO customer (phone, name, address, segment, successful_purchases, province) VALUES
                ('+84900000001', 'A', 'x, Hà Nội', 'ONE_TIME', 1, 'ha-noi'),
                ('+84900000002', 'B', 'x, Cần Thơ', 'ONE_TIME', 1, 'can-tho'),
                ('+84900000003', 'C', 'x, TP.HCM', 'ONE_TIME', 1, 'tphcm'),
                ('+84900000004', 'D', 'không có tỉnh', 'ONE_TIME', 1, NULL)""").update();

        assertThat(customers.search(null, false, "R:NAM", null, 1, 50).total()).isEqualTo(2);
        assertThat(customers.search(null, false, "R:BAC", null, 1, 50).total()).isEqualTo(1);
        assertThat(customers.search(null, false, "P:can-tho", null, 1, 50).items()).extracting("name").containsExactly("B");
        assertThat(customers.search(null, false, "U", null, 1, 50).items()).extracting("name").containsExactly("D");
        assertThat(customers.search(null, false, "bậy bạ", null, 1, 50).total()).isEqualTo(4);
        assertThat(customers.countByRegion().get(vn.shop.crm.geo.Region.NAM)).isEqualTo(2);
        assertThat(customers.countUnknownRegion()).isEqualTo(1);
        assertThat(customers.countByProvince()).containsEntry("ha-noi", 1L);

        // khách cũ (phiên bản 0) được tính lại tỉnh từ địa chỉ có sẵn
        assertThat(customers.refreshProvinces(false)).isEqualTo(4);
        assertThat(customers.search(null, false, "U", null, 1, 50).items()).extracting("name").containsExactly("D");
        assertThat(customers.refreshProvinces(false)).isZero();
    }
}
