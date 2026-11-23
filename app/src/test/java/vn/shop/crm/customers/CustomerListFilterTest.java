package vn.shop.crm.customers;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import vn.shop.crm.AbstractPostgresTest;
import vn.shop.crm.contact.ContactService;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Danh sách khách: sắp xếp và lọc theo từng cột chạy ở máy chủ, giữ trong địa chỉ trang, tiếng Việt không phân biệt dấu. */
class CustomerListFilterTest extends AbstractPostgresTest {

    @Autowired
    CustomerService customers;
    @Autowired
    ContactService contacts;
    @Autowired
    WebApplicationContext context;
    MockMvc mvc;

    long an, be, can, dung, em;

    @BeforeEach
    void seed() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        an = insert("+84901000001", "Nguyễn Văn An", "Xóm 3, Hà Nội", "ha-noi", "ONE_TIME", 1, "2026-01-10 08:00:00", false);
        be = insert("+84902000002", "Trần Thị Bé", "Ninh Kiều, Cần Thơ", "can-tho", "ONE_TIME", 1, "2026-03-05 08:00:00", false);
        can = insert("+84903000003", "Lê Văn Cần", "Quận 1, TP.HCM", "tphcm", "REPEAT", 3, "2026-02-01 08:00:00", false);
        dung = insert("+84904000004", "Phạm Đức Dũng", "không ghi tỉnh", null, "ONE_TIME", 1, null, false);
        em = insert("+84905000005", "Hoàng Em", "Cầu Giấy, Hà Nội", "ha-noi", "ONE_TIME", 1, "2026-03-20 08:00:00", true);
    }

    private long insert(String phone, String name, String address, String province, String segment, int purchases,
                        String last, boolean review) {
        return jdbc.sql("""
                INSERT INTO customer (phone, name, address, segment, successful_purchases, province, last_order_on, needs_review)
                VALUES (:phone, :name, :address, :segment, :n, :province, CAST(:last AS timestamp), :review) RETURNING id""")
                .param("phone", phone).param("name", name).param("address", address).param("segment", segment)
                .param("n", purchases).param("province", province, java.sql.Types.VARCHAR)
                .param("last", last, java.sql.Types.VARCHAR).param("review", review)
                .query(Long.class).single();
    }

    private List<String> names(Map<String, String> params) {
        return customers.search(CustomerQuery.parse(params), 50).items().stream().map(CustomerService.CustomerRow::name).toList();
    }

    @Test
    void macDinhMoiMuaNhatTruocVaKhachChuaCoNgayXuongCuoi() {
        assertThat(names(Map.of())).containsExactly("Hoàng Em", "Trần Thị Bé", "Lê Văn Cần", "Nguyễn Văn An", "Phạm Đức Dũng");
    }

    @Test
    void sapXepTangGiamTungCotVaTrongLuonXuongCuoi() {
        assertThat(names(Map.of("sort", "last", "dir", "asc")))
                .containsExactly("Nguyễn Văn An", "Lê Văn Cần", "Trần Thị Bé", "Hoàng Em", "Phạm Đức Dũng");
        assertThat(names(Map.of("sort", "last", "dir", "desc")).getLast()).isEqualTo("Phạm Đức Dũng");
        assertThat(names(Map.of("sort", "purchases", "dir", "desc")).getFirst()).isEqualTo("Lê Văn Cần");
        assertThat(names(Map.of("sort", "purchases", "dir", "asc")).getLast()).isEqualTo("Lê Văn Cần");
        assertThat(names(Map.of("sort", "phone", "dir", "desc")).getFirst()).isEqualTo("Hoàng Em");
        // theo tên: không phân biệt dấu nên "Đức" xếp như "Duc", không bị đẩy xuống cuối bảng mã
        assertThat(names(Map.of("sort", "name", "dir", "asc")))
                .containsExactly("Hoàng Em", "Lê Văn Cần", "Nguyễn Văn An", "Phạm Đức Dũng", "Trần Thị Bé");
        // theo khu vực: miền Bắc trước, miền Nam sau, chưa rõ luôn cuối cả khi sắp giảm
        assertThat(names(Map.of("sort", "area", "dir", "asc")).getLast()).isEqualTo("Phạm Đức Dũng");
        assertThat(names(Map.of("sort", "area", "dir", "desc")).getLast()).isEqualTo("Phạm Đức Dũng");
        assertThat(names(Map.of("sort", "area", "dir", "asc")).subList(0, 2)).containsExactlyInAnyOrder("Nguyễn Văn An", "Hoàng Em");
        // cột lạ hoặc hướng lạ không gây lỗi
        assertThat(names(Map.of("sort", "'; drop table customer; --", "dir", "xx"))).hasSize(5);
    }

    @Test
    void sapXepTheoPhanLoaiDuyetMoiVaLoiMoi() {
        contacts.approve(an);
        contacts.block(be, "thử");
        assertThat(names(Map.of("segment", "ONE_TIME", "sort", "approval", "dir", "asc")))
                .containsExactly("Hoàng Em", "Phạm Đức Dũng", "Nguyễn Văn An", "Trần Thị Bé");
        assertThat(names(Map.of("sort", "segment", "dir", "desc")).getFirst()).isEqualTo("Lê Văn Cần");
        assertThat(names(Map.of("segment", "ONE_TIME", "sort", "invite", "dir", "asc"))).hasSize(4);
        assertThat(names(Map.of("sort", "note", "dir", "asc"))).hasSize(5);
    }

    @Test
    void locChuTheoSoDienThoaiTenVaDiaChiKhongPhanBietDau() {
        assertThat(names(Map.of("phone", "0902"))).containsExactly("Trần Thị Bé");
        assertThat(names(Map.of("phone", "090 300"))).containsExactly("Lê Văn Cần");
        assertThat(names(Map.of("phone", "abc"))).isEmpty();
        assertThat(names(Map.of("name", "nguyen"))).containsExactly("Nguyễn Văn An");
        assertThat(names(Map.of("name", "DUC"))).containsExactly("Phạm Đức Dũng");
        assertThat(names(Map.of("name", "văn")).size()).isEqualTo(2);
        assertThat(names(Map.of("address", "ha noi"))).containsExactlyInAnyOrder("Nguyễn Văn An", "Hoàng Em");
        assertThat(names(Map.of("address", "can tho"))).containsExactly("Trần Thị Bé");
        // ký tự đặc biệt của LIKE không bị hiểu là ký tự thay thế
        assertThat(names(Map.of("name", "%"))).isEmpty();
        assertThat(names(Map.of("name", "_"))).isEmpty();
        // ô tìm chung cũng không phân biệt dấu
        assertThat(names(Map.of("q", "tran thi"))).containsExactly("Trần Thị Bé");
        assertThat(names(Map.of("q", "0901"))).containsExactly("Nguyễn Văn An");
    }

    @Test
    void locKhoangSoLanMuaVaNgayMuaGanNhat() {
        assertThat(names(Map.of("buyFrom", "2"))).containsExactly("Lê Văn Cần");
        assertThat(names(Map.of("buyTo", "1"))).hasSize(4);
        assertThat(names(Map.of("buyFrom", "3", "buyTo", "1"))).as("đảo ngược khoảng thì tự đổi chỗ").hasSize(5);
        assertThat(names(Map.of("lastFrom", "2026-02-01", "lastTo", "2026-03-05")))
                .containsExactlyInAnyOrder("Lê Văn Cần", "Trần Thị Bé");
        assertThat(names(Map.of("lastTo", "2026-01-31"))).containsExactly("Nguyễn Văn An");
        assertThat(names(Map.of("lastFrom", "2026-03-06"))).containsExactly("Hoàng Em");
        assertThat(names(Map.of("lastFrom", "không phải ngày", "buyFrom", "-4", "buyTo", "x"))).hasSize(5);
    }

    @Test
    void locTheoKhuVucDuyetMoiLoiMoiVaGhiChu() {
        assertThat(names(Map.of("area", "R:BAC"))).containsExactlyInAnyOrder("Nguyễn Văn An", "Hoàng Em");
        assertThat(names(Map.of("area", "U"))).containsExactly("Phạm Đức Dũng");

        contacts.approve(an);
        contacts.block(be, "thử");
        contacts.saveNote(can, "Khách quen, đã gọi điện");
        assertThat(names(Map.of("approval", "ELIGIBLE"))).containsExactly("Nguyễn Văn An");
        assertThat(names(Map.of("approval", "BLOCKED"))).containsExactly("Trần Thị Bé");
        assertThat(names(Map.of("approval", "REVIEW"))).containsExactly("Hoàng Em");
        // khách cần kiểm tra hiện "Cần kiểm tra" nên không lọt vào nhóm "Chờ duyệt"
        assertThat(names(Map.of("approval", "PENDING"))).containsExactlyInAnyOrder("Lê Văn Cần", "Phạm Đức Dũng");
        assertThat(names(Map.of("note", "HAS"))).containsExactly("Lê Văn Cần");
        assertThat(names(Map.of("note", "NONE"))).hasSize(4);

        long campaign = jdbc.sql("INSERT INTO invitation_campaign (name, message_template, expires_days, created_by) "
                + "VALUES ('Đợt 1', 'x', 14, 't') RETURNING id").query(Long.class).single();
        jdbc.sql("INSERT INTO invitation (campaign_id, customer_id, token, status, expires_at) "
                + "VALUES (:c, :id, 'tok-an', 'SENT', now() + interval '1 day')").param("c", campaign).param("id", an).update();
        assertThat(names(Map.of("invite", "SENT"))).containsExactly("Nguyễn Văn An");
        assertThat(names(Map.of("invite", "NONE"))).hasSize(4);
        assertThat(names(Map.of("invite", "JOINED"))).isEmpty();
    }

    @Test
    void ketHopNhieuBoLocVaPhanTrangDemDungTong() {
        assertThat(names(Map.of("address", "ha noi", "buyTo", "1", "sort", "name", "dir", "desc")))
                .containsExactly("Nguyễn Văn An", "Hoàng Em");
        var page = customers.search(CustomerQuery.parse(Map.of("sort", "name", "dir", "asc", "page", "2")), 2);
        assertThat(page.total()).isEqualTo(5);
        assertThat(page.items()).extracting("name").containsExactly("Nguyễn Văn An", "Phạm Đức Dũng");
        assertThat(customers.listAll(CustomerQuery.parse(Map.of("area", "R:BAC"))).size()).isEqualTo(2);
    }

    @Test
    void duongDanGiuTrangThaiVaDoiHuongKhiBamLaiCot() {
        var q = CustomerQuery.parse(Map.of("segment", "ONE_TIME", "name", "Nguyễn Văn", "sort", "last", "dir", "asc", "page", "3"));
        var links = new CustomerListLinks(q, CustomerColumn.keys(CustomerColumn.forSegment(Segment.ONE_TIME)));
        assertThat(links.sort("last")).contains("sort=last").contains("dir=desc").doesNotContain("page=")
                .contains("segment=ONE_TIME").contains("name=Nguy%E1%BB%85n+V%C4%83n");
        assertThat(links.sort("name")).contains("sort=name").contains("dir=asc");
        assertThat(links.ariaSort("last")).isEqualTo("ascending");
        assertThat(links.ariaSort("name")).isEqualTo("none");
        assertThat(links.page(4)).contains("page=4").contains("sort=last");
        assertThat(links.clearFilters()).doesNotContain("name=").contains("segment=ONE_TIME").contains("sort=last");
        assertThat(links.export()).startsWith("/customers/export.csv?").doesNotContain("page=");
        // mặc định: sắp theo ngày giảm dần, bấm vào thì chuyển tăng dần
        var def = new CustomerListLinks(CustomerQuery.empty(), CustomerColumn.keys(CustomerColumn.forSegment(null)));
        assertThat(def.ariaSort("last")).isEqualTo("descending");
        assertThat(def.sort("last")).contains("dir=asc");
    }

    @Test
    void boLocCuaCotKhongHienBiBoQuaDeKhongLocNgam() {
        // khách mua từ 2 lần không có cột "Duyệt mời": tham số đó không được lọc ngầm
        var q = CustomerQuery.parse(Map.of("segment", "REPEAT", "approval", "BLOCKED", "sort", "invite"))
                .restrictedTo(CustomerColumn.keys(CustomerColumn.forSegment(Segment.REPEAT)));
        assertThat(q.approval()).isNull();
        assertThat(q.sort()).isNull();
        assertThat(customers.search(q, 50).items()).extracting("name").containsExactly("Lê Văn Cần");
    }

    @Test
    void trangRenderCoTieuDeBamDuocHangLocVaThanhLocLenDau() throws Exception {
        var owner = user("admin").roles("OWNER");
        String html = mvc.perform(get("/customers").param("segment", "ONE_TIME").param("sort", "name").param("dir", "asc")
                        .param("name", "nguyen").with(owner))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("aria-label=\"Lọc khách\"")))
                .andExpect(content().string(containsString("aria-sort=\"ascending\"")))
                .andExpect(content().string(containsString("/customers?segment=ONE_TIME&amp;name=nguyen&amp;sort=name&amp;dir=desc")))
                .andExpect(content().string(containsString("Nguyễn Văn An")))
                .andExpect(content().string(not(containsString("Trần Thị Bé"))))
                .andExpect(content().string(containsString("Duyệt mời")))
                .andExpect(content().string(containsString("Xóa tất cả bộ lọc")))
                // biểu tượng phễu ở từng tiêu đề cột (bật/tắt ô lọc của cột), biểu tượng sắp xếp là hình, không còn ký tự mũi tên
                .andExpect(content().string(containsString("class=\"fbtn\"")))
                .andExpect(content().string(containsString("data-col=\"name\"")))
                .andExpect(content().string(containsString("class=\"sort-ico\"")))
                .andExpect(content().string(not(containsString("&#8597;"))))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        // thanh lọc khu vực nằm trên bảng và trên các nút "Tất cả / Cần kiểm tra"
        assertThat(html.indexOf("aria-label=\"Lọc khách\"")).isLessThan(html.indexOf("<table"));
        assertThat(html.indexOf("Đang lọc:")).isLessThan(html.indexOf("<table"));
        assertThat(html).contains("Khách: nguyen");

        mvc.perform(get("/customers").with(owner)).andExpect(status().isOk())
                .andExpect(content().string(containsString("Phân loại")))
                .andExpect(content().string(containsString("Lần mua")));
        mvc.perform(get("/customers").param("segment", "REPEAT").with(owner)).andExpect(status().isOk())
                .andExpect(content().string(containsString("Lê Văn Cần")))
                .andExpect(content().string(not(containsString("Duyệt mời"))));
        // tham số hỏng không làm lỗi trang
        mvc.perform(get("/customers").param("page", "abc").param("buyFrom", "x").param("lastFrom", "2026-99-99")
                        .param("sort", "zzz").with(owner)).andExpect(status().isOk());
        mvc.perform(get("/customers/export.csv").param("area", "R:BAC").param("sort", "name").with(owner))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Nguyễn Văn An")))
                .andExpect(content().string(not(containsString("Trần Thị Bé"))));
    }

    @Test
    void locNgayDungKhiLaNgayCuoiTronVen() {
        // ngày cuối của khoảng tính trọn ngày (08:00 ngày 20/3 vẫn nằm trong "đến 20/3")
        assertThat(names(Map.of("lastTo", LocalDate.of(2026, 3, 20).toString())).size()).isEqualTo(4);
        assertThat(names(Map.of("lastFrom", "2026-03-20", "lastTo", "2026-03-20"))).containsExactly("Hoàng Em");
    }

    @Test
    void theDangLocNoiRoTungDieuKienVaXoaTatCaGiuSapXep() {
        var q = CustomerQuery.parse(Map.of("segment", "ONE_TIME", "review", "true", "q", "an", "area", "R:BAC", "name", "nguyen",
                "buyFrom", "2", "lastFrom", "2026-03-01", "sort", "name", "dir", "asc", "page", "3"));
        var links = new CustomerListLinks(q, CustomerColumn.keys(CustomerColumn.forSegment(null)));
        var chips = CustomerFilterChips.of(q, links);
        assertThat(chips).extracting("label").contains("Loại: Mua 1 lần", "Chỉ khách cần kiểm tra", "Tìm: an",
                "Khách: nguyen", "Lần mua: từ 2", "Mua gần nhất: từ 01/03/2026");
        assertThat(chips).extracting("label").anyMatch(l -> ((String) l).startsWith("Khu vực: "));
        // bỏ một thẻ: chỉ mất điều kiện đó, giữ sắp xếp, về trang 1
        var removeName = chips.stream().filter(c -> c.label().startsWith("Khách:")).findFirst().orElseThrow();
        assertThat(removeName.removeUrl()).doesNotContain("name=").contains("segment=ONE_TIME").contains("sort=name").doesNotContain("page=");
        // xóa tất cả: không còn điều kiện nào, vẫn giữ cách sắp xếp
        String all = links.clearAll();
        assertThat(all).isEqualTo("/customers?sort=name&dir=asc");
        assertThat(CustomerFilterChips.of(CustomerQuery.parse(Map.of("sort", "name")), links)).isEmpty();
    }
}
