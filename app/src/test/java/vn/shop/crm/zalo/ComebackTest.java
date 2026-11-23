package vn.shop.crm.zalo;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import vn.shop.crm.AbstractPostgresTest;
import vn.shop.crm.ai.AiGate;
import vn.shop.crm.ai.AiProperties;
import vn.shop.crm.ai.AiUsage;
import vn.shop.crm.common.AppSettings;
import vn.shop.crm.common.ImportException;
import vn.shop.crm.contact.ContactService;
import vn.shop.crm.imports.ImportService;
import vn.shop.crm.inbox.ComebackService;
import vn.shop.crm.inbox.ComebackService.Mode;
import vn.shop.crm.inbox.RefusalClassifier;
import vn.shop.crm.inbox.RefusalReviewService;
import vn.shop.crm.invite.InviteService;
import vn.shop.crm.shipping.Carrier;
import vn.shop.crm.zalo.ZaloMessenger.Kind;
import vn.shop.crm.zalo.ZaloMessenger.Result;
import vn.shop.crm.zalo.ZaloMessenger.Sent;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Khách từng từ chối nay TỰ nhắn lại muốn mua (D19, chủ shop chốt 23/11/2026): số điện thoại và dòng sổ từ chối KHÔNG BAO GIỜ bị xóa, chỉ
 * đánh dấu "đã gỡ"; ba nấc (tắt / chỉ tạo việc / tự động); AI chắc từ 0,85 mới tự gỡ; tối đa một lần tự gỡ trong 30 ngày; Hoàn tác;
 * tin mẫu cố định chỉ một lần và chỉ trong khung 24 giờ.
 */
@Import(ZaloTestConfig.class)
@TestPropertySource(properties = {
        "app.zalo.auto.enabled=true",
        "app.zalo.auto.bridge-key=test",
        "app.zalo.auto.initial-delay-millis=3600000",
        "app.zalo.auto.tick-millis=3600000"})
class ComebackTest extends AbstractPostgresTest {

    private static final String HEADER =
            "Mã vận đơn,Mã đơn hàng,Ngày tạo,Tên người nhận,SĐT người nhận,Địa chỉ người nhận,Trạng thái\n";

    @Autowired ImportService imports;
    @Autowired ContactService contacts;
    @Autowired InviteService invites;
    @Autowired RefusalReviewService reviews;
    @Autowired ComebackService comebacks;
    @Autowired vn.shop.crm.inbox.ReplyService replies;
    @Autowired ZaloWelcomeSender welcome;
    @Autowired ZaloMessageService messages;
    @Autowired ZaloAutoSettings auto;
    @Autowired AppSettings settings;
    @Autowired AiUsage usage;
    @Autowired FakeZaloGateway fake;
    @Autowired ZaloTestClock clock;

    final AtomicReference<String> aiAnswer = new AtomicReference<>();
    final List<String> aiPrompts = new ArrayList<>();
    ZaloInboxService svc;

    @BeforeEach
    void setUp() {
        StringBuilder csv = new StringBuilder(HEADER);
        for (int i = 1; i <= 3; i++) {
            csv.append("T").append(i).append(",DH").append(i).append(",05/01/2026 09:00:00,Khach ").append(i)
                    .append(",090000000").append(i).append(",1 Duong A Xa B Cần Thơ,Giao thành công\n");
        }
        long id = imports.createBatch(Carrier.VIETTEL_POST, "t.csv", csv.toString().getBytes(StandardCharsets.UTF_8));
        var view = imports.open(id, null);
        imports.commit(id, new ImportService.Mapping(view.headerRow(), view.suggested()));
        contacts.approveAllCandidates();
        invites.createCampaign("Đợt 1", "Chào {ten}, {cua_hang} tư vấn.", 14, 20);
        fake.reset();
        aiPrompts.clear();
        clock.set(LocalDateTime.of(2026, 11, 22, 10, 0));
        settings.set("zalo.msg.enabled", "false", "test");
        settings.set("zalo.msg.daily", "10", "test");
        settings.set("zalo.msg.gap_seconds", "120", "test");
        settings.set("campaigns.paused", "false", "test");
        auto.reopen("test");
        auto.setMode(ZaloMode.CONFIRM, false, "mẹ");
        AiProperties p = new AiProperties(true, "khoa", "m", "https://api.deepseek.com", 200, 10, 300);
        AiGate gate = new AiGate((system, user, json) -> {
            aiPrompts.add(user);
            return aiAnswer.get();
        }, p, usage, Clock.systemUTC());
        svc = new ZaloInboxService(new ZaloInboxProperties(true, 0.8, 0.85), fake, fake, auto, settings, new RefusalClassifier(gate), reviews,
                comebacks, replies);
        aiAnswer.set(ai("MUON_MUA", 0.95));
        // khách 1 đã nhận Tin 1 rồi TỪ CHỐI (mẹ ghi tay): lời mời CANCELLED, sổ từ chối có hiệu lực
        long c1 = cust(1);
        jdbc.sql("UPDATE invitation SET status = 'SENT', sent_at = now(), zalo_target_uid = '60000001' WHERE customer_id = :c")
                .param("c", c1).update();
        contacts.suppress(c1, "khách bảo đừng nhắn");
    }

    private long cust(int n) {
        return jdbc.sql("SELECT id FROM customer WHERE phone = :p").param("p", "+8490000000" + n).query(Long.class).single();
    }

    private String ai(String intent, double conf) {
        return "{\"intent\":\"" + intent + "\",\"confidence\":" + conf + "}";
    }

    private long count(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }

    private String inv1(String col) {
        return jdbc.sql("SELECT " + col + " FROM invitation WHERE customer_id = :c").param("c", cust(1)).query(String.class).optional().orElse(null);
    }

    private boolean suppressed() {
        return contacts.isSuppressed("+84900000001");
    }

    private long suppressionRows() {
        return count("SELECT count(*) FROM suppression");
    }

    private void says(String text) {
        fake.pushInbound("60000001", text);
        svc.poll();
    }

    private void autoOn() {
        comebacks.setMode(Mode.TASKS, false, "mẹ");
        comebacks.setMode(Mode.AUTO, true, "mẹ");
        messages.setEnabled(true, "mẹ");
    }

    // ---------- tắt mặc định ----------

    @Test
    void macDinhTatKhongGoiAiVaKhongDoiGi() {
        assertThat(comebacks.mode()).isEqualTo(Mode.OFF);
        says("cho mình hỏi mua pin");
        assertThat(aiPrompts).isEmpty();
        assertThat(count("SELECT count(*) FROM comeback")).isZero();
        assertThat(suppressed()).isTrue();
        assertThat(inv1("status")).isEqualTo("CANCELLED");
    }

    @Test
    void nacTuDongChiMoSauKhiChayNacChiTaoViecVaCoTickXacNhan() {
        assertThatThrownBy(() -> comebacks.setMode(Mode.AUTO, true, "mẹ")).isInstanceOf(ImportException.class);
        comebacks.setMode(Mode.TASKS, false, "mẹ");
        assertThatThrownBy(() -> comebacks.setMode(Mode.AUTO, false, "mẹ")).isInstanceOf(ImportException.class);
        comebacks.setMode(Mode.AUTO, true, "mẹ");
        assertThat(comebacks.mode()).isEqualTo(Mode.AUTO);
    }

    // ---------- nấc riêng của chiến dịch (D21 PR 7): chỉ chặt hơn hệ thống ----------

    private long campaignId() {
        return count("SELECT id FROM invitation_campaign");
    }

    private long inv1Id() {
        return count("SELECT id FROM invitation WHERE customer_id = " + cust(1));
    }

    @Test
    void chienDichChuaDatGiThiTheoHeThong() {
        autoOn();
        assertThat(comebacks.campaignMode(campaignId())).isNull();
        assertThat(comebacks.effective(inv1Id())).isEqualTo(Mode.AUTO);
    }

    @Test
    void chienDichTatThiKhongGoiAiDuHeThongDangTuDong() {
        autoOn();
        comebacks.saveCampaignMode(campaignId(), Mode.OFF, "mẹ");
        assertThat(comebacks.effective(inv1Id())).isEqualTo(Mode.OFF);
        says("cho mình hỏi mua pin 48V");
        assertThat(aiPrompts).isEmpty();
        assertThat(count("SELECT count(*) FROM comeback")).isZero();
        assertThat(suppressed()).isTrue();
        assertThat(inv1("status")).isEqualTo("CANCELLED");
    }

    @Test
    void chienDichChiTaoViecThiHeThongTuDongCungChiTaoViecKhongGoCo() {
        autoOn();
        comebacks.saveCampaignMode(campaignId(), Mode.TASKS, "mẹ");
        says("cho mình hỏi mua pin 48V");
        assertThat(comebacks.tasks()).singleElement().satisfies(t -> assertThat(t.reason()).isEqualTo(ComebackService.Reason.CHI_TAO_VIEC));
        assertThat(suppressed()).isTrue(); // cờ giữ nguyên
        assertThat(inv1("status")).isEqualTo("CANCELLED");
    }

    @Test
    void chienDichDatTuDongKhongNoiDuocNacChungDangChiTaoViec() {
        comebacks.setMode(Mode.TASKS, false, "mẹ");
        comebacks.saveCampaignMode(campaignId(), Mode.AUTO, "mẹ");
        assertThat(comebacks.effective(inv1Id())).isEqualTo(Mode.TASKS);
        says("cho mình hỏi mua pin 48V");
        assertThat(suppressed()).isTrue();
        assertThat(comebacks.tasks()).hasSize(1);
    }

    @Test
    void chienDichChiGuiLinkNhomThiTuDongHaXuongChiTaoViecVaTuDongTatThiVanTat() {
        autoOn();
        jdbc.sql("UPDATE invitation_campaign SET group_delivery = 'LINK_ONLY'").update();
        assertThat(comebacks.effective(inv1Id())).isEqualTo(Mode.TASKS);
        says("cho mình hỏi mua pin 48V");
        assertThat(suppressed()).isTrue(); // không tự gỡ cờ, không tự thêm nhóm
        assertThat(comebacks.tasks()).hasSize(1);
        settings.set(ComebackService.MODE_KEY, "OFF", "test");
        assertThat(comebacks.effective(inv1Id())).isEqualTo(Mode.OFF);
    }

    @Test
    void nacLaBiTuChoiVaChienDichKhongCoThiBaoLoi() {
        assertThatThrownBy(() -> Mode.ofCampaign("TUY_Y")).isInstanceOf(ImportException.class);
        assertThat(Mode.ofCampaign(" ")).isNull();
        assertThatThrownBy(() -> comebacks.saveCampaignMode(999999, Mode.OFF, "mẹ")).isInstanceOf(ImportException.class);
    }

    // ---------- chỉ tạo việc ----------

    @Test
    void nacChiTaoViecChiTaoMotViecVaGiuNguyenCo() {
        comebacks.setMode(Mode.TASKS, false, "mẹ");
        says("cho mình hỏi mua pin");
        says("alo shop ơi");
        assertThat(comebacks.tasks()).singleElement().satisfies(t -> {
            assertThat(t.reason()).isEqualTo(ComebackService.Reason.CHI_TAO_VIEC);
            assertThat(t.excerpt()).contains("mua pin");
            assertThat(t.orders()).isNotNull();
        });
        assertThat(suppressed()).isTrue();
        assertThat(inv1("status")).isEqualTo("CANCELLED");
        assertThat(fake.messages).isEmpty();
        assertThat(comebacks.openCount()).isEqualTo(1);
        assertThat(reviews.heldCount()).isEqualTo(1); // hiện ở số việc chờ mẹ
    }

    @Test
    void khachTuChoiLanNuaHoacTinKhongLienQuanThiKhongLamGi() {
        comebacks.setMode(Mode.TASKS, false, "mẹ");
        aiAnswer.set(ai("TU_CHOI", 0.99));
        says("đã bảo đừng nhắn nữa");
        aiAnswer.set(ai("KHAC", 0.9));
        says("cảm ơn");
        assertThat(count("SELECT count(*) FROM comeback")).isZero();
        assertThat(suppressed()).isTrue();
    }

    @Test
    void aiLoiHoacSaiKhuonThanhViecChoMe() {
        comebacks.setMode(Mode.TASKS, false, "mẹ");
        aiAnswer.set("không phải json");
        says("mua pin");
        assertThat(comebacks.tasks()).singleElement().satisfies(t -> assertThat(t.reason()).isEqualTo(ComebackService.Reason.AI_SAI_KHUON));
        assertThat(suppressed()).isTrue();
    }

    @Test
    void nguoiKhongPhaiKhachTungTuChoiThiBoQuaKhongGoiAi() {
        comebacks.setMode(Mode.TASKS, false, "mẹ");
        fake.pushInbound("77777777", "mua pin");
        svc.poll();
        assertThat(aiPrompts).isEmpty();
        assertThat(count("SELECT count(*) FROM comeback")).isZero();
    }

    @Test
    void khachDaYeuCauXoaDuLieuKhongBaoGioDuocGo() {
        comebacks.setMode(Mode.TASKS, false, "mẹ");
        jdbc.sql("UPDATE suppression SET reason = 'ERASED'").update();
        says("mua pin");
        assertThat(aiPrompts).isEmpty();
        assertThat(count("SELECT count(*) FROM comeback")).isZero();
        assertThatThrownBy(() -> contacts.liftOptOut(cust(1), "mẹ")).isInstanceOf(ImportException.class);
    }

    // ---------- tự động ----------

    @Test
    void tuDongAiChacThiGoCoNhungKhongXoaDongSoVaXepTinMau() {
        autoOn();
        long before = suppressionRows();
        says("cho mình hỏi mua pin 48V");
        assertThat(suppressionRows()).isEqualTo(before).isEqualTo(1); // dòng mã băm vẫn còn
        assertThat(count("SELECT count(*) FROM suppression WHERE lifted_at IS NOT NULL AND lifted_by = 'tro-ly-ai'")).isEqualTo(1);
        assertThat(suppressed()).isFalse();
        assertThat(count("SELECT count(*) FROM customer WHERE id = " + cust(1))).isEqualTo(1); // khách và số vẫn còn
        assertThat(jdbc.sql("SELECT basis || '/' || status FROM contact_basis WHERE customer_id = :c").param("c", cust(1)).query(String.class).single())
                .isEqualTo("OPT_IN/ELIGIBLE");
        assertThat(inv1("status")).isEqualTo("FRIENDED");
        assertThat(inv1("zalo_add_state")).isEqualTo("QUEUED");
        assertThat(inv1("zalo_uid")).isEqualTo("60000001");
        assertThat(count("SELECT count(*) FROM comeback WHERE state = 'LIFTED' AND welcome_state = 'PENDING'")).isEqualTo(1);
        assertThat(comebacks.tasks()).isEmpty();
        assertThat(fake.messages).isEmpty(); // chưa gửi gì: tin mẫu do bộ gửi riêng
    }

    @Test
    void tinMauGuiDungMotLanQuaDuongChungVaKhongDuaTenKhach() {
        autoOn();
        says("cho mình hỏi mua pin");
        assertThat(welcome.step()).isTrue();
        assertThat(fake.messages).singleElement().satisfies(m -> {
            assertThat(m.kind()).isEqualTo(Kind.WELCOME);
            assertThat(m.uid()).isEqualTo("60000001");
            assertThat(m.phone()).isNull();
            assertThat(m.text()).contains("trợ lý tự động").doesNotContain("Khach 1");
        });
        assertThat(count("SELECT count(*) FROM comeback WHERE welcome_state = 'SENT'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM zalo_msg_log WHERE kind = 'WELCOME' AND outcome = 'OK'")).isEqualTo(1);
        clock.advance(java.time.Duration.ofMinutes(10));
        assertThat(welcome.step()).isFalse(); // không còn tin nào chờ
        assertThat(fake.messages).hasSize(1);
    }

    @Test
    void tinMauDungChungHanMucVaKhungGio() {
        autoOn();
        says("mua pin");
        clock.set(LocalDateTime.of(2026, 11, 22, 23, 0)); // ngoài 08:00 đến 20:00
        assertThat(welcome.step()).isFalse();
        assertThat(fake.messages).isEmpty();
        assertThat(count("SELECT count(*) FROM comeback WHERE welcome_state = 'PENDING'")).isEqualTo(1);
        clock.set(LocalDateTime.of(2026, 11, 22, 10, 0));
        assertThat(welcome.step()).isTrue();
    }

    @Test
    void tinMauBiCauNoiTuChoiThiDanhDauThatBaiVaKhongThuLai() {
        autoOn();
        says("mua pin");
        fake.willReturnMessage(Sent.of(Result.PERSON_ERROR, "NOT_REPLYABLE", true));
        assertThat(welcome.step()).isTrue();
        assertThat(count("SELECT count(*) FROM comeback WHERE welcome_state = 'FAILED' AND welcome_code = 'NOT_REPLYABLE'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM zalo_msg_log")).isZero(); // chưa chạm Zalo: không tính hạn mức
        assertThat(auto.tripped()).isFalse();
        clock.advance(java.time.Duration.ofMinutes(10));
        assertThat(welcome.step()).isFalse();
        assertThat(fake.messages).hasSize(1);
    }

    @Test
    void tinMauQuaHan23GioThiBoQua() {
        autoOn();
        says("mua pin");
        jdbc.sql("UPDATE comeback SET lifted_at = now() - interval '24 hours'").update();
        assertThat(welcome.step()).isFalse();
        assertThat(fake.messages).isEmpty();
        assertThat(count("SELECT count(*) FROM comeback WHERE welcome_state = 'SKIPPED'")).isEqualTo(1);
    }

    @Test
    void tinMauChiBatKhiNacTuDong() {
        comebacks.setMode(Mode.TASKS, false, "mẹ");
        messages.setEnabled(true, "mẹ");
        says("mua pin");
        assertThat(welcome.step()).isFalse();
        assertThat(fake.messages).isEmpty();
    }

    @Test
    void duoiNguongHoacNhanKhacChiTaoViec() {
        autoOn();
        aiAnswer.set(ai("MUON_MUA", 0.8));
        says("hình như mình muốn mua");
        assertThat(comebacks.tasks()).singleElement().satisfies(t -> assertThat(t.reason()).isEqualTo(ComebackService.Reason.CHUA_CHAC));
        assertThat(suppressed()).isTrue();
        aiAnswer.set(ai("KHIEU_NAI", 0.99));
        says("pin lỗi");
        assertThat(comebacks.tasks()).hasSize(1); // đã có một việc đang chờ cho khách này
    }

    @Test
    void nacBoNoiKhongChoThemNhomThiKhongTuGo() {
        autoOn();
        auto.setMode(ZaloMode.LISTEN_ONLY, false, "mẹ"); // nghe nhưng không thêm nhóm
        says("mua pin");
        assertThat(comebacks.tasks()).singleElement().satisfies(t -> assertThat(t.reason()).isEqualTo(ComebackService.Reason.CHI_TAO_VIEC));
        assertThat(suppressed()).isTrue();
    }

    @Test
    void khachKhongConDuDieuKienThiKhongTuGoVaMoiThuQuayLaiNhuCu() {
        autoOn();
        jdbc.sql("UPDATE customer SET needs_review = TRUE WHERE id = :c").param("c", cust(1)).update();
        says("mua pin");
        assertThat(comebacks.tasks()).singleElement().satisfies(t -> assertThat(t.reason()).isEqualTo(ComebackService.Reason.KHONG_DU_DIEU_KIEN));
        assertThat(suppressed()).isTrue(); // giao dịch đã quay lại
        assertThat(inv1("status")).isEqualTo("CANCELLED");
        assertThat(count("SELECT count(*) FROM suppression WHERE lifted_at IS NOT NULL")).isZero();
        assertThat(jdbc.sql("SELECT status FROM contact_basis WHERE customer_id = :c").param("c", cust(1)).query(String.class).single())
                .isEqualTo("OPTED_OUT");
    }

    // ---------- hoàn tác và 30 ngày ----------

    @Test
    void hoanTacDuaMoiThuVeNhuCuVaHuyTinMauChuaGui() {
        autoOn();
        says("mua pin");
        long id = comebacks.recentLifted(1).get(0).id();
        assertThat(comebacks.undo(id)).isTrue();
        assertThat(suppressed()).isTrue();
        assertThat(suppressionRows()).isEqualTo(1);
        assertThat(inv1("status")).isEqualTo("CANCELLED");
        assertThat(inv1("zalo_add_state")).isNull();
        assertThat(jdbc.sql("SELECT status FROM contact_basis WHERE customer_id = :c").param("c", cust(1)).query(String.class).single())
                .isEqualTo("OPTED_OUT");
        assertThat(count("SELECT count(*) FROM comeback WHERE state = 'UNDONE' AND welcome_state = 'SKIPPED'")).isEqualTo(1);
        assertThat(welcome.step()).isFalse();
        assertThatThrownBy(() -> comebacks.undo(id)).isInstanceOf(ImportException.class);
    }

    @Test
    void toiDaMotLanTuGoTrong30NgayKeCaDaHoanTac() {
        autoOn();
        says("mua pin");
        comebacks.undo(comebacks.recentLifted(1).get(0).id());
        says("mua pin lần nữa");
        assertThat(comebacks.tasks()).singleElement().satisfies(t -> assertThat(t.reason()).isEqualTo(ComebackService.Reason.DA_GO_GAN_DAY));
        assertThat(suppressed()).isTrue();
        jdbc.sql("UPDATE comeback SET lifted_at = now() - interval '31 days'").update();
        jdbc.sql("DELETE FROM comeback WHERE state = 'TASK'").update();
        says("mua pin lần ba");
        assertThat(suppressed()).isFalse(); // quá 30 ngày thì được tự gỡ lại
        assertThat(suppressionRows()).isEqualTo(1);
    }

    @Test
    void khachTuChoiLanNuaSauKhiGoThiDongSoCuKichHoatLaiKhongTaoDongMoi() {
        autoOn();
        says("mua pin");
        assertThat(suppressed()).isFalse();
        contacts.suppress(cust(1), "lại bảo đừng nhắn");
        assertThat(suppressed()).isTrue();
        assertThat(suppressionRows()).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM suppression WHERE lifted_at IS NULL")).isEqualTo(1);
    }

    @Test
    void khachBiXoaTuGiaoDienThiSoVanKhongBiNhanLai() {
        autoOn();
        says("mua pin");
        contacts.suppress(cust(1), "lại từ chối");
        // chủ shop tự xóa khách trên giao diện: số vẫn nằm trong sổ băm, không được tự gỡ
        assertThat(contacts.isSuppressed("+84900000001")).isTrue();
    }

    // ---------- mẹ quyết ----------

    @Test
    void meGoTayTuViecThiVaoHangChoVaKhongCoTinMau() {
        comebacks.setMode(Mode.TASKS, false, "mẹ");
        says("mua pin");
        long id = comebacks.tasks().get(0).id();
        comebacks.liftByHand(id);
        assertThat(suppressed()).isFalse();
        assertThat(inv1("zalo_add_state")).isEqualTo("QUEUED");
        assertThat(comebacks.tasks()).isEmpty();
        assertThat(comebacks.recentLifted(1)).singleElement().satisfies(r -> {
            assertThat(r.welcomeState()).isNull();
            assertThat(r.excerpt()).isNull();
        });
        assertThat(count("SELECT count(*) FROM comeback WHERE welcome_state IS NOT NULL")).isZero();
        assertThatThrownBy(() -> comebacks.liftByHand(id)).isInstanceOf(ImportException.class);
    }

    @Test
    void meGiuNguyenThiCoVanConVaXoaTrichDoan() {
        comebacks.setMode(Mode.TASKS, false, "mẹ");
        says("mua pin");
        comebacks.dismiss(comebacks.tasks().get(0).id());
        assertThat(suppressed()).isTrue();
        assertThat(count("SELECT count(*) FROM comeback WHERE state = 'DONE' AND excerpt IS NULL")).isEqualTo(1);
    }

    @Test
    void trichDoanQua30NgayBiXoa() {
        comebacks.setMode(Mode.TASKS, false, "mẹ");
        says("mua pin");
        jdbc.sql("UPDATE comeback SET created_at = now() - interval '31 days'").update();
        assertThat(comebacks.purgeOld()).isEqualTo(1);
        assertThat(comebacks.tasks()).singleElement().satisfies(t -> assertThat(t.excerpt()).isNull());
    }

    @Test
    void khongGuiChoAiTenSoDienThoaiHayMaZalo() {
        comebacks.setMode(Mode.TASKS, false, "mẹ");
        says("mình tên Khach 1 sđt 0900000001 muốn mua");
        assertThat(aiPrompts).singleElement().satisfies(p -> {
            assertThat(p).doesNotContain("0900000001").doesNotContain("60000001");
        });
    }
}
