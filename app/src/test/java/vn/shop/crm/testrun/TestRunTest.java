package vn.shop.crm.testrun;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import vn.shop.crm.AbstractPostgresTest;
import vn.shop.crm.common.ImportException;
import vn.shop.crm.contact.ContactService;
import vn.shop.crm.imports.ImportService;
import vn.shop.crm.invite.GroupService;
import vn.shop.crm.invite.GroupStatus;
import vn.shop.crm.invite.InviteService;
import vn.shop.crm.shipping.Carrier;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Chạy thử trên tài khoản Zalo thật: vài số tự nhập, TÁCH HẲN khỏi khách thật. Chạy hết quy trình mà dữ liệu thật
 * (khách, lời mời, nhật ký gửi, danh sách từ chối, số khách của nhóm) không đổi một chữ nào.
 */
class TestRunTest extends AbstractPostgresTest {

    private static final String HEADER =
            "Mã vận đơn,Mã đơn hàng,Ngày tạo,Tên người nhận,SĐT người nhận,Địa chỉ người nhận,Trạng thái\n";

    @Autowired
    TestRunService tests;
    @Autowired
    ImportService imports;
    @Autowired
    ContactService contacts;
    @Autowired
    GroupService groups;
    @Autowired
    InviteService invites;
    @Autowired
    WebApplicationContext context;

    long north;
    long south;

    @BeforeEach
    void setUp() {
        String csv = HEADER
                + "T1,DH1,05/01/2026 09:00:00,Khach Bac,0900000001,1 Duong A Xa B Hà Nội,Giao thành công\n"
                + "T2,DH2,05/01/2026 09:00:00,Khach Nam,0900000002,2 Duong A Xa B Cần Thơ,Giao thành công\n";
        long id = imports.createBatch(Carrier.VIETTEL_POST, "t.csv", csv.getBytes(StandardCharsets.UTF_8));
        var view = imports.open(id, null);
        imports.commit(id, new ImportService.Mapping(view.headerRow(), view.suggested()));
        north = groups.create("Nhóm miền Bắc", "https://zalo.me/g/bac", 5, "R:BAC");
        groups.setStatus(north, GroupStatus.OPEN);
        south = groups.create("Nhóm miền Nam", "https://zalo.me/g/nam", 5, "R:NAM");
        groups.setStatus(south, GroupStatus.OPEN);
        contacts.approveAllCandidates();
        invites.createCampaign("Chiến dịch thật", null, 14, 10);
    }

    private long count(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }

    /** Ảnh chụp mọi thứ thuộc dữ liệu thật; so sánh trước và sau khi chạy thử. */
    private Map<String, Object> snapshot() {
        return Map.of(
                "customers", count("SELECT count(*) FROM customer"),
                "invitations", jdbc.sql("SELECT string_agg(id || ':' || status || ':' || coalesce(group_id::text, '-'), ',' ORDER BY id) FROM invitation").query(String.class).single(),
                "sendLog", count("SELECT count(*) FROM send_log"),
                "events", count("SELECT count(*) FROM contact_event"),
                "suppression", count("SELECT count(*) FROM suppression"),
                "campaigns", count("SELECT count(*) FROM invitation_campaign"),
                "groupMembers", jdbc.sql("SELECT string_agg(actual_customers::text, ',' ORDER BY id) FROM zalo_group").query(String.class).single(),
                "basis", jdbc.sql("SELECT string_agg(customer_id || ':' || status, ',' ORDER BY customer_id) FROM contact_basis").query(String.class).single());
    }

    private long create() {
        return tests.create("Thử lần 1", "Chào {ten}, cửa hàng tư vấn cách dùng. Nhắn \"ngừng\" nếu không muốn nhận tin.",
                List.of(new TestRunService.Entry("0911111111", "Tôi", "ha-noi"),
                        new TestRunService.Entry("0922 222 222", "Bạn tôi", "can-tho"),
                        new TestRunService.Entry("", "", "")));
    }

    @Test
    void chayHetQuyTrinhMaDuLieuThatKhongDoi() {
        var before = snapshot();
        long run = create();
        var targets = tests.targets(run);
        assertThat(targets).hasSize(2);
        assertThat(targets.get(0).displayPhone()).isEqualTo("0911111111");
        assertThat(targets.get(0).message()).contains("Chào Tôi,");
        // gợi ý nhóm theo khu vực của từng số thử
        assertThat(targets.get(0).groupName()).isEqualTo("Nhóm miền Bắc");
        assertThat(targets.get(1).groupName()).isEqualTo("Nhóm miền Nam");

        long a = targets.get(0).id();
        long b = targets.get(1).id();
        // chưa nhắn thì chưa kết bạn hay vào nhóm được
        assertThatThrownBy(() -> tests.markFriended(a)).isInstanceOf(ImportException.class);
        assertThatThrownBy(() -> tests.markJoined(a)).isInstanceOf(ImportException.class);
        tests.markSent(a);
        tests.markSent(b);
        assertThatThrownBy(() -> tests.markSent(a)).isInstanceOf(ImportException.class);
        tests.markFriended(a);
        tests.markJoined(a);
        tests.markJoined(b);
        tests.markDeclined(b);

        var after = tests.run(run).orElseThrow();
        assertThat(after.sent()).isEqualTo(2);
        assertThat(after.friended()).isEqualTo(2);
        assertThat(after.joined()).isEqualTo(2);
        assertThat(after.declined()).isEqualTo(1);
        assertThat(tests.targets(run).get(0).groupName()).isEqualTo("Nhóm miền Bắc");

        // dữ liệu thật không đổi: không "đã nhắn", không từ chối thật, không nhật ký, không tăng số khách của nhóm
        assertThat(snapshot()).isEqualTo(before);
        assertThat(groups.find(north).orElseThrow().actual()).isZero();
    }

    @Test
    void thuLaiTuDauVaXoaDotThu() {
        long run = create();
        long a = tests.targets(run).get(0).id();
        tests.markSent(a);
        tests.reset(run);
        assertThat(tests.targets(run)).allMatch(t -> "READY".equals(t.status()));
        tests.delete(run);
        assertThat(tests.runs()).isEmpty();
        assertThat(count("SELECT count(*) FROM test_target")).isZero();
    }

    @Test
    void kiemTraDauVao() {
        assertThatThrownBy(() -> tests.create("", null, List.of(new TestRunService.Entry("0911111111", null, null))))
                .isInstanceOf(ImportException.class).hasMessageContaining("tên");
        assertThatThrownBy(() -> tests.create("X", null, List.of()))
                .isInstanceOf(ImportException.class).hasMessageContaining("ít nhất một số");
        assertThatThrownBy(() -> tests.create("X", null, List.of(new TestRunService.Entry("12345", null, null))))
                .isInstanceOf(ImportException.class).hasMessageContaining("không hợp lệ");
        assertThatThrownBy(() -> tests.create("X", null, List.of(new TestRunService.Entry("0911111111", null, null),
                new TestRunService.Entry("+84 911 111 111", null, null))))
                .isInstanceOf(ImportException.class).hasMessageContaining("hai lần");
        assertThatThrownBy(() -> tests.create("X", null, List.of(new TestRunService.Entry("0911111111", null, "tinh-la"))))
                .isInstanceOf(ImportException.class).hasMessageContaining("Tỉnh");
        List<TestRunService.Entry> many = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            many.add(new TestRunService.Entry("09111111%02d".formatted(i), null, null));
        }
        assertThatThrownBy(() -> tests.create("X", null, many)).isInstanceOf(ImportException.class)
                .hasMessageContaining("tối đa");
        assertThat(tests.runs()).isEmpty();
    }

    @Test
    void khongCoNhomConChoThiBaoLoiVaKhongGhiGi() {
        groups.setStatus(north, GroupStatus.PAUSED);
        groups.setStatus(south, GroupStatus.PAUSED);
        long run = create();
        long a = tests.targets(run).get(0).id();
        tests.markSent(a);
        assertThatThrownBy(() -> tests.markJoined(a)).isInstanceOf(ImportException.class).hasMessageContaining("nhóm");
        assertThat(tests.targets(run).get(0).status()).isEqualTo("SENT");
    }

    @Test
    void cacTrangChayThuRenderDuocVaDungDuoc() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        var owner = user("admin").roles("OWNER");
        mvc.perform(get("/test-runs").with(owner)).andExpect(status().isOk())
                .andExpect(content().string(containsString("Chiến dịch thử")))
                .andExpect(content().string(containsString("không đụng tới khách thật")))
                .andExpect(content().string(containsString("Hà Nội")));
        mvc.perform(post("/test-runs").with(owner).with(csrf()).param("name", "Thử web")
                        .param("template", "Chào {ten}").param("phone", "0933333333", "", "")
                        .param("personName", "Tôi", "", "").param("province", "ha-noi", "", ""))
                .andExpect(status().is3xxRedirection());
        long run = tests.runs().get(0).id();
        long t = tests.targets(run).get(0).id();
        mvc.perform(get("/test-runs/" + run).with(owner)).andExpect(status().isOk())
                .andExpect(content().string(containsString("ĐÂY LÀ CHIẾN DỊCH THỬ")))
                .andExpect(content().string(containsString("0933333333")))
                .andExpect(content().string(containsString("Nhóm miền Bắc")));
        mvc.perform(post("/test-runs/" + run + "/targets/" + t + "/sent").with(owner).with(csrf()))
                .andExpect(status().is3xxRedirection());
        mvc.perform(post("/test-runs/" + run + "/targets/" + t + "/joined").with(owner).with(csrf()))
                .andExpect(status().is3xxRedirection());
        assertThat(tests.targets(run).get(0).status()).isEqualTo("JOINED");
        assertThat(groups.find(north).orElseThrow().actual()).isZero();
        mvc.perform(post("/test-runs/" + run + "/delete").with(owner).with(csrf())).andExpect(status().is3xxRedirection());
        assertThat(tests.runs()).isEmpty();
    }

    @Test
    void phaiDangNhapMoiVaoDuocTrangChayThu() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        mvc.perform(get("/test-runs")).andExpect(status().is3xxRedirection());
        mvc.perform(get("/today")).andExpect(status().is3xxRedirection());
    }
}
