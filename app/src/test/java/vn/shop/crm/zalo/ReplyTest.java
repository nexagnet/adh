package vn.shop.crm.zalo;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import vn.shop.crm.AbstractPostgresTest;
import vn.shop.crm.ai.AiClient;
import vn.shop.crm.ai.AiGate;
import vn.shop.crm.ai.AiProperties;
import vn.shop.crm.ai.AiUsage;
import vn.shop.crm.common.AppSettings;
import vn.shop.crm.common.ImportException;
import vn.shop.crm.contact.ContactService;
import vn.shop.crm.imports.ImportService;
import vn.shop.crm.inbox.ComebackService;
import vn.shop.crm.inbox.RefusalClassifier;
import vn.shop.crm.inbox.RefusalReviewService;
import vn.shop.crm.inbox.ReplyAssistant;
import vn.shop.crm.inbox.ReplyInfo;
import vn.shop.crm.inbox.ReplyService;
import vn.shop.crm.inbox.ReplyService.Mode;
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
 * Trợ lý AI tự soạn câu trả lời cho khách tự nhắn tới (D20, chủ dự án chốt 23/11/2026): phạm vi hẹp (mời vào nhóm, ưu đãi), mọi thứ khác chuyển người
 * thật; câu soạn chỉ từ bảng thông tin và phải qua bộ kiểm cứng; ba nấc, mặc định tắt; chỉ gửi qua đường chung; khách từng từ chối không bao giờ được trả lời.
 */
@Import(ZaloTestConfig.class)
@TestPropertySource(properties = {
        "app.zalo.auto.enabled=true",
        "app.zalo.auto.bridge-key=test",
        "app.zalo.auto.initial-delay-millis=3600000",
        "app.zalo.auto.tick-millis=3600000"})
class ReplyTest extends AbstractPostgresTest {

    private static final String HEADER =
            "Mã vận đơn,Mã đơn hàng,Ngày tạo,Tên người nhận,SĐT người nhận,Địa chỉ người nhận,Trạng thái\n";
    private static final String GOOD = "Dạ anh/chị vào nhóm mua trực tiếp sẽ được giảm 10% so với giá trên TikTok và miễn phí ship ạ.";

    @Autowired ImportService imports;
    @Autowired ContactService contacts;
    @Autowired InviteService invites;
    @Autowired RefusalReviewService reviews;
    @Autowired ComebackService comebacks;
    @Autowired ZaloMessageService messages;
    @Autowired ZaloAutoSettings auto;
    @Autowired AppSettings settings;
    @Autowired AiUsage usage;
    @Autowired ReplyInfo info;
    @Autowired FakeZaloGateway fake;
    @Autowired ZaloTestClock clock;

    final AtomicReference<String> label = new AtomicReference<>();
    final AtomicReference<String> scope = new AtomicReference<>();
    final AtomicReference<String> compose = new AtomicReference<>();
    final List<String> prompts = new ArrayList<>();
    final List<String> systems = new ArrayList<>();
    ReplyService replies;
    ZaloReplySender sender;
    ZaloInboxService svc;
    AiGate gate;
    boolean aiDown;

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
        prompts.clear();
        systems.clear();
        aiDown = false;
        clock.set(LocalDateTime.of(2026, 11, 22, 10, 0));
        settings.set("zalo.msg.enabled", "false", "test");
        settings.set("zalo.msg.daily", "10", "test");
        settings.set("zalo.msg.gap_seconds", "120", "test");
        settings.set("campaigns.paused", "false", "test");
        settings.set(ReplyService.MODE_KEY, "OFF", "test");
        auto.reopen("test");
        auto.setMode(ZaloMode.CONFIRM, false, "mẹ");
        AiProperties p = new AiProperties(true, "khoa", "m", "https://api.deepseek.com", 1000, 10, 300);
        AiClient client = (system, user, json) -> {
            systems.add(system);
            prompts.add(user);
            if (aiDown) {
                throw new AiClient.AiUnavailableException("down");
            }
            if (system.startsWith("Bạn chỉ làm một việc: xem MỘT tin")) {
                return scope.get();
            }
            if (system.startsWith("Bạn là TRỢ LÝ AI")) {
                return compose.get();
            }
            return label.get();
        };
        gate = new AiGate(client, p, usage, Clock.systemUTC());
        replies = new ReplyService(jdbc, settings, new ReplyAssistant(gate, info), info, invites, contacts);
        sender = new ZaloReplySender(replies, messages, settings);
        svc = new ZaloInboxService(new ZaloInboxProperties(true, 0.8, 0.85), fake, fake, auto, settings, new RefusalClassifier(gate), reviews,
                comebacks, replies);
        label.set(aiLabel("CAU_HOI", 0.95));
        scope.set(aiScope("TRONG", 0.95));
        compose.set(aiReply(GOOD));
        // khách 1 đã nhận Tin 1 và đang trong luồng mời (SENT)
        jdbc.sql("UPDATE invitation SET status = 'SENT', sent_at = now(), zalo_target_uid = '60000001' WHERE customer_id = :c")
                .param("c", cust(1)).update();
    }

    private long cust(int n) {
        return jdbc.sql("SELECT id FROM customer WHERE phone = :p").param("p", "+8490000000" + n).query(Long.class).single();
    }

    private static String aiLabel(String intent, double conf) {
        return "{\"intent\":\"" + intent + "\",\"confidence\":" + conf + "}";
    }

    private static String aiScope(String s, double conf) {
        return "{\"scope\":\"" + s + "\",\"confidence\":" + conf + "}";
    }

    private static String aiReply(String r) {
        return "{\"reply\":\"" + r + "\"}";
    }

    private long count(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }

    private String one(String col) {
        return jdbc.sql("SELECT " + col + " FROM reply_draft ORDER BY id DESC LIMIT 1").query(String.class).optional().orElse(null);
    }

    private void says(String text) {
        fake.pushInbound("60000001", text);
        svc.poll();
    }

    private void autoOn() {
        replies.setMode(Mode.DRAFT, false, "mẹ");
        replies.setMode(Mode.AUTO, true, "mẹ");
        messages.setEnabled(true, "mẹ");
    }

    private void freshInbound() {
        jdbc.sql("UPDATE reply_draft SET inbound_at = now()").update();
    }

    // ---------- tắt mặc định và các nấc ----------

    @Test
    void macDinhTatKhongGoiAiVaKhongLuuGi() {
        assertThat(replies.mode()).isEqualTo(Mode.OFF);
        says("Vào nhóm có mất phí không");
        assertThat(count("SELECT count(*) FROM reply_draft")).isZero();
        assertThat(systems.stream().filter(s -> s.startsWith("Bạn là TRỢ LÝ AI") || s.startsWith("Bạn chỉ làm một việc: xem")).count()).isZero();
        assertThat(fake.messages).isEmpty();
    }

    @Test
    void nacTuDongChiMoSauKhiChayNhapVaCoTickXacNhan() {
        assertThatThrownBy(() -> replies.setMode(Mode.AUTO, true, "mẹ")).isInstanceOf(ImportException.class);
        replies.setMode(Mode.DRAFT, false, "mẹ");
        assertThatThrownBy(() -> replies.setMode(Mode.AUTO, false, "mẹ")).isInstanceOf(ImportException.class);
        replies.setMode(Mode.AUTO, true, "mẹ");
        assertThat(replies.mode()).isEqualTo(Mode.AUTO);
    }

    @Test
    void nacNhapLuuCauNhungKhongGuiGi() {
        replies.setMode(Mode.DRAFT, false, "mẹ");
        messages.setEnabled(true, "mẹ");
        says("Vào nhóm có mất phí không");
        assertThat(count("SELECT count(*) FROM reply_draft WHERE state = 'DRAFT'")).isEqualTo(1);
        assertThat(replies.drafts()).singleElement().satisfies(r -> {
            assertThat(r.reply()).startsWith(GOOD).contains("Trợ lý AI của").contains("gặp người thật");
            assertThat(r.inbound()).contains("mất phí");
        });
        assertThat(sender.step()).isFalse();
        assertThat(fake.messages).isEmpty();
    }

    // ---------- tự động ----------

    @Test
    void tuDongGuiDungMotCauQuaDuongChungKemChuKyTroLyAi() {
        autoOn();
        says("Vào nhóm có mất phí không");
        assertThat(one("state")).isEqualTo("READY");
        freshInbound();
        assertThat(sender.step()).isTrue();
        assertThat(fake.messages).singleElement().satisfies(m -> {
            assertThat(m.kind()).isEqualTo(Kind.REPLY);
            assertThat(m.uid()).isEqualTo("60000001");
            assertThat(m.phone()).isNull();
            assertThat(m.text()).startsWith(GOOD).contains("Trợ lý AI của").doesNotContain("Khach 1");
        });
        assertThat(one("state")).isEqualTo("SENT");
        assertThat(count("SELECT count(*) FROM zalo_msg_log WHERE kind = 'REPLY' AND outcome = 'OK'")).isEqualTo(1);
        clock.advance(java.time.Duration.ofMinutes(10));
        assertThat(sender.step()).isFalse(); // không còn câu nào chờ
        assertThat(fake.messages).hasSize(1);
    }

    @Test
    void tuDongTinTraLoiKhongTinhVaoHanMucKhachMoiNgay() {
        autoOn();
        settings.set("zalo.msg.daily", "1", "test");
        says("Vào nhóm có mất phí không");
        freshInbound();
        assertThat(sender.step()).isTrue();
        clock.advance(java.time.Duration.ofMinutes(10));
        fake.pushInbound("60000001", "Ưu đãi thế nào");
        svc.poll();
        freshInbound();
        assertThat(sender.step()).isTrue(); // hạn mức 1 khách/ngày không chặn tin trả lời
        assertThat(fake.messages).hasSize(2);
    }

    @Test
    void chiGuiKhiNacTuDongVaCongTacGuiBat() {
        replies.setMode(Mode.DRAFT, false, "mẹ");
        says("Vào nhóm có mất phí không");
        jdbc.sql("UPDATE reply_draft SET state = 'READY'").update();
        freshInbound();
        assertThat(sender.step()).isFalse(); // nấc nháp không gửi
        replies.setMode(Mode.AUTO, true, "mẹ");
        assertThat(sender.step()).isFalse(); // chưa bật tự gửi tin
        messages.setEnabled(true, "mẹ");
        assertThat(sender.step()).isTrue();
    }

    @Test
    void ngoaiKhungGioThiKhongGui() {
        autoOn();
        says("Vào nhóm có mất phí không");
        freshInbound();
        clock.set(LocalDateTime.of(2026, 11, 22, 21, 0));
        assertThat(sender.step()).isFalse();
        assertThat(fake.messages).isEmpty();
        assertThat(one("state")).isEqualTo("READY");
    }

    @Test
    void loiGuiKhongRoThiChuyenNguoiThatVaKhongTuGuiLai() {
        autoOn();
        says("Vào nhóm có mất phí không");
        freshInbound();
        fake.willReturnMessage(Sent.of(Result.PERSON_ERROR, "NOT_REPLYABLE", true));
        assertThat(sender.step()).isTrue();
        assertThat(one("state")).isEqualTo("HANDOFF");
        assertThat(one("reason")).isEqualTo("GUI_LOI");
        assertThat(one("detail")).isEqualTo("NOT_REPLYABLE");
        clock.advance(java.time.Duration.ofMinutes(10));
        assertThat(sender.step()).isFalse();
        assertThat(fake.messages).hasSize(1);
    }

    @Test
    void quaHan23GioThiChuyenNguoiThatKhongGui() {
        autoOn();
        says("Vào nhóm có mất phí không");
        jdbc.sql("UPDATE reply_draft SET inbound_at = now() - interval '24 hours'").update();
        assertThat(sender.step()).isFalse();
        assertThat(replies.expireStale()).isEqualTo(1);
        assertThat(one("state")).isEqualTo("HANDOFF");
        assertThat(one("reason")).isEqualTo("QUA_HAN");
        assertThat(one("reply_text")).isNull();
        assertThat(fake.messages).isEmpty();
    }

    // ---------- chuyển người thật ----------

    @Test
    void ngoaiPhamViThiChuyenNguoiThatKhongSoanCau() {
        replies.setMode(Mode.DRAFT, false, "mẹ");
        scope.set(aiScope("NGOAI", 0.95));
        says("Pin 48V còn hàng không, giá bao nhiêu");
        assertThat(one("state")).isEqualTo("HANDOFF");
        assertThat(one("reason")).isEqualTo("NGOAI_PHAM_VI");
        assertThat(one("reply_text")).isNull();
        assertThat(systems.stream().filter(s -> s.startsWith("Bạn là TRỢ LÝ AI")).count()).isZero(); // không soạn câu
        assertThat(replies.handoffs()).singleElement().satisfies(r -> assertThat(r.inbound()).contains("giá bao nhiêu"));
        assertThat(replies.openCount()).isEqualTo(1);
        assertThat(fake.messages).isEmpty();
    }

    @Test
    void nghiNganGiaiPhamViThiCoiLaNgoai() {
        replies.setMode(Mode.DRAFT, false, "mẹ");
        scope.set(aiScope("TRONG", 0.79));
        says("ưu đãi sao");
        assertThat(one("reason")).isEqualTo("NGOAI_PHAM_VI");
    }

    @Test
    void khachMuonMuaHoacPhanNanChuyenNguoiThatNgayKhongGoiThemAi() {
        replies.setMode(Mode.DRAFT, false, "mẹ");
        label.set(aiLabel("MUON_MUA", 0.95));
        says("Cho mình đặt 2 cái");
        assertThat(one("reason")).isEqualTo("KHACH_MUON_MUA");
        assertThat(systems.stream().filter(s -> s.startsWith("Bạn là TRỢ LÝ AI") || s.startsWith("Bạn chỉ làm một việc: xem")).count()).isZero();
        jdbc.sql("UPDATE reply_draft SET state = 'DONE'").update();
        label.set(aiLabel("KHIEU_NAI", 0.95));
        says("Hàng lỗi rồi");
        assertThat(one("reason")).isEqualTo("KHACH_KHIEU_NAI");
    }

    @Test
    void cauAiSoanKhongQuaKiemCungThiKhongBaoGioGui() {
        autoOn();
        compose.set(aiReply("Dạ anh/chị vào nhóm được giảm 15% ạ."));
        says("Vào nhóm có mất phí không");
        assertThat(one("state")).isEqualTo("HANDOFF");
        assertThat(one("reason")).isEqualTo("CAU_BI_CHAN");
        assertThat(one("detail")).isEqualTo("CON_SO_NGOAI_BANG");
        assertThat(one("reply_text")).isNull();
        assertThat(sender.step()).isFalse();
        assertThat(fake.messages).isEmpty();
    }

    @Test
    void aiNoiBangKhongDuThongTinThiChuyenNguoiThat() {
        replies.setMode(Mode.DRAFT, false, "mẹ");
        compose.set(aiReply(""));
        says("Vào nhóm có mất phí không");
        assertThat(one("reason")).isEqualTo("KHONG_DU_THONG_TIN");
    }

    @Test
    void aiLoiHoacSaiKhuonThanhViecChoNguoiThat() {
        replies.setMode(Mode.DRAFT, false, "mẹ");
        scope.set("không phải json");
        says("Vào nhóm có mất phí không");
        assertThat(one("reason")).isEqualTo("AI_SAI_KHUON");
        jdbc.sql("UPDATE reply_draft SET state = 'DONE'").update();
        scope.set(aiScope("TRONG", 0.95));
        compose.set("{\"reply\":\"ok\",\"extra\":1}");
        says("Vào nhóm có mất phí không");
        assertThat(one("reason")).isEqualTo("AI_SAI_KHUON");
        jdbc.sql("UPDATE reply_draft SET state = 'DONE'").update();
        compose.set(aiReply(GOOD));
        scope.set(null); // AI không trả lời được ở bước phân loại phạm vi
        says("Vào nhóm có mất phí không");
        assertThat(one("reason")).isEqualTo("AI_LOI");
        jdbc.sql("UPDATE reply_draft SET state = 'DONE'").update();
        scope.set(aiScope("TRONG", 0.95));
        aiDown = true;
        says("Vào nhóm có mất phí không");
        // AI hỏng ngay ở bước gắn nhãn từ chối: khách tạm dừng và báo người thật như cũ (D19), không có câu trả lời nào
        assertThat(reviews.heldCount()).isEqualTo(1);
        assertThat(fake.messages).isEmpty();
    }

    @Test
    void khachNhanThemKhiDaChuyenNguoiThatThiGhiTiepVaoViecDoKhongGoiAi() {
        replies.setMode(Mode.DRAFT, false, "mẹ");
        scope.set(aiScope("NGOAI", 0.95));
        says("Giá pin 48V");
        int callsBefore = prompts.size();
        says("Có giao Cần Thơ không");
        assertThat(count("SELECT count(*) FROM reply_draft")).isEqualTo(1);
        assertThat(one("inbound_text")).contains("Giá pin 48V").contains("Cần Thơ");
        assertThat(prompts.size() - callsBefore).isEqualTo(1); // chỉ lượt gắn nhãn từ chối, không phân loại hay soạn thêm
    }

    @Test
    void khachNhanThemKhiNhapChuaXuLyThiThayBangViecMoiGopYKhach() {
        replies.setMode(Mode.DRAFT, false, "mẹ");
        says("Vào nhóm có mất phí không");
        says("Ưu đãi là gì");
        assertThat(count("SELECT count(*) FROM reply_draft WHERE state = 'SUPERSEDED'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM reply_draft WHERE state = 'DRAFT'")).isEqualTo(1);
        assertThat(one("inbound_text")).contains("mất phí").contains("Ưu đãi");
    }

    // ---------- ai được trả lời ----------

    @Test
    void khachTuChoiVoiAiChacKhongBaoGioDuocTraLoi() {
        autoOn();
        label.set(aiLabel("TU_CHOI", 0.99));
        says("đừng nhắn nữa");
        assertThat(count("SELECT count(*) FROM reply_draft")).isZero();
        assertThat(contacts.isSuppressed("+84900000001")).isTrue();
        assertThat(sender.step()).isFalse();
    }

    @Test
    void khachTungTuChoiKhongBaoGioDuocTraLoiKeCaKhiNacTuDong() {
        autoOn();
        contacts.suppress(cust(1), "khách bảo đừng nhắn");
        jdbc.sql("UPDATE invitation SET status = 'CANCELLED' WHERE customer_id = :c").param("c", cust(1)).update();
        says("Vào nhóm có mất phí không");
        assertThat(count("SELECT count(*) FROM reply_draft")).isZero();
        assertThat(fake.messages).isEmpty();
        // kể cả khi lời mời cũ còn JOINED nhưng cờ từ chối còn hiệu lực
        jdbc.sql("UPDATE invitation SET status = 'JOINED' WHERE customer_id = :c").param("c", cust(1)).update();
        says("Vào nhóm có mất phí không nữa");
        assertThat(count("SELECT count(*) FROM reply_draft")).isZero();
    }

    @Test
    void nguoiLaKhongPhaiKhachThiBoQua() {
        autoOn();
        fake.pushInbound("77777777", "Vào nhóm có mất phí không");
        svc.poll();
        assertThat(count("SELECT count(*) FROM reply_draft")).isZero();
        assertThat(prompts).isEmpty();
    }

    @Test
    void khachDaVaoNhomNhanRiengDuocTraLoiTrongPhamVi() {
        replies.setMode(Mode.DRAFT, false, "mẹ");
        jdbc.sql("UPDATE invitation SET status = 'JOINED' WHERE customer_id = :c").param("c", cust(1)).update();
        says("Ưu đãi trong nhóm là gì vậy");
        assertThat(one("state")).isEqualTo("DRAFT");
        assertThat(one("customer_id")).isEqualTo(Long.toString(cust(1)));
    }

    @Test
    void khachDaVaoNhomNoiTuChoiThiChuyenNguoiThatKhongGhiSo() {
        replies.setMode(Mode.DRAFT, false, "mẹ");
        jdbc.sql("UPDATE invitation SET status = 'JOINED' WHERE customer_id = :c").param("c", cust(1)).update();
        label.set(aiLabel("TU_CHOI", 0.99));
        says("đừng nhắn riêng nữa");
        assertThat(one("reason")).isEqualTo("KHACH_TU_CHOI");
        assertThat(contacts.isSuppressed("+84900000001")).isFalse();
        assertThat(fake.messages).isEmpty();
    }

    // ---------- an toàn dữ liệu ----------

    @Test
    void chiGuiChoAiVanBanDaCheKhongCoSoDienThoaiTenHayMaZalo() {
        replies.setMode(Mode.DRAFT, false, "mẹ");
        says("Mình là Lan, sdt 0912 345 678, vào nhóm có mất phí không");
        assertThat(prompts).isNotEmpty().allSatisfy(p -> assertThat(p).doesNotContain("0912").doesNotContain("345 678").doesNotContain("60000001")
                .doesNotContain("Khach 1").doesNotContain("+84900000001"));
        assertThat(systems).allSatisfy(s -> assertThat(s).doesNotContain("60000001").doesNotContain("0900000001"));
        assertThat(one("inbound_text")).doesNotContain("0912");
    }

    @Test
    void banThongTinLaNguonDuyNhatVaMacDinhDungLoaiHang() {
        String block = info.block();
        assertThat(block).contains("áo điều hòa").contains("10%").contains("miễn phí ship").contains("TikTok");
        assertThat(block).doesNotContain("đổi trả");
        String system = systems.isEmpty() ? "" : systems.get(0);
        assertThat(system).doesNotContain("đổi trả của TikTok là");
        assertThat(info.value("warranty")).isBlank(); // không tự thêm cam kết bảo hành
    }

    @Test
    void luuBangThongTinVaGioiHanDoDai() {
        info.save(java.util.Map.of("warranty", "Bảo hành 6 tháng"), "mẹ");
        assertThat(info.value("warranty")).isEqualTo("Bảo hành 6 tháng");
        assertThat(info.allowedText()).contains("6 tháng");
        assertThatThrownBy(() -> info.save(java.util.Map.of("extra", "x".repeat(601)), "mẹ")).isInstanceOf(ImportException.class);
    }

    @Test
    void chuKyLuonNoiRoLaTroLyAi() {
        assertThat(replies.signature()).contains("Trợ lý AI").doesNotContain("chủ shop").doesNotContain("mẹ");
    }

    @Test
    void chuyenNguoiThatLaHangDoiChungChuaGanChoAi() {
        replies.setMode(Mode.DRAFT, false, "mẹ");
        scope.set(aiScope("NGOAI", 0.95));
        says("Giá pin 48V");
        assertThat(one("assigned_to")).isNull();
        long id = replies.handoffs().get(0).id();
        assertThat(replies.done(id, "đội bán hàng")).isTrue();
        assertThat(one("state")).isEqualTo("DONE");
        assertThat(one("inbound_text")).isNull();
        assertThat(replies.done(id, "đội bán hàng")).isFalse();
    }

    @Test
    void chuVanBanQua30NgayBiXoa() {
        replies.setMode(Mode.DRAFT, false, "mẹ");
        says("Vào nhóm có mất phí không");
        jdbc.sql("UPDATE reply_draft SET created_at = now() - interval '31 days'").update();
        assertThat(replies.purgeOld()).isEqualTo(1);
        assertThat(one("inbound_text")).isNull();
        assertThat(one("reply_text")).isNull();
    }

    @Test
    void thuMotCauKhongLuuKhongGui() {
        ReplyService.Preview ok = replies.preview("Vào nhóm có mất phí không");
        assertThat(ok.wouldReply()).isTrue();
        assertThat(ok.reply()).contains(GOOD);
        scope.set(aiScope("NGOAI", 0.9));
        ReplyService.Preview no = replies.preview("Giá bao nhiêu");
        assertThat(no.wouldReply()).isFalse();
        assertThat(no.note()).contains("người thật");
        assertThat(count("SELECT count(*) FROM reply_draft")).isZero();
        assertThat(fake.messages).isEmpty();
    }
}
