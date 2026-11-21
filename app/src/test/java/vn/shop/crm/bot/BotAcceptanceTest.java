package vn.shop.crm.bot;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import vn.shop.crm.AbstractPostgresTest;
import vn.shop.crm.bot.BotEngine.IncomingMessage;
import vn.shop.crm.bot.BotEngine.SenderKind;
import vn.shop.crm.bot.BotEngine.Type;
import vn.shop.crm.common.AppSettings;
import vn.shop.crm.common.ImportException;
import vn.shop.crm.invite.GroupService;
import vn.shop.crm.invite.GroupStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Mốc 1E: lõi bot (DRY_RUN, kill switch, báo người thật, không tự chốt đơn). Không có kênh Zalo thật nào. */
class BotAcceptanceTest extends AbstractPostgresTest {

    @Autowired
    GroupService groups;
    @Autowired
    FaqService faqs;
    @Autowired
    HandoffService handoffs;
    @Autowired
    AppSettings settings;
    @Autowired
    MockAiProvider mockAi;

    private final AtomicInteger ids = new AtomicInteger();
    private long group;

    static class RecordingChannel implements BotChannel {
        final List<String> sent = new ArrayList<>();
        boolean fail;

        @Override
        public boolean send(long groupId, String text) {
            if (fail) {
                throw new IllegalStateException("kênh hỏng");
            }
            sent.add(text);
            return true;
        }
    }

    @BeforeEach
    void setUp() {
        group = groups.create("Nhóm 1", "https://zalo.me/g/abc", 5);
        groups.setStatus(group, GroupStatus.OPEN);
    }

    private BotEngine engine(BotChannel channel, AiProvider ai) {
        return new BotEngine(jdbc, settings, faqs, handoffs, ai, channel, 30, 3);
    }

    private BotEngine engine() {
        return engine(new NoopBotChannel(), mockAi);
    }

    private void botOn() {
        settings.set(AppSettings.BOT_ENABLED, "true", "test");
        jdbc.sql("UPDATE zalo_group SET bot_enabled = TRUE WHERE id = :id").param("id", group).update();
    }

    private IncomingMessage msg(SenderKind who, String text) {
        return new IncomingMessage(group, "m" + ids.incrementAndGet(), who, text);
    }

    private long count(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }

    // ---------- tắt / bật ----------

    @Test
    void macDinhBotTatKhongTaoViecVaKhongTraLoi() {
        var d = engine().handle(msg(SenderKind.CUSTOMER, "Mình muốn mua thêm 2 cái"));

        assertThat(d.type()).isEqualTo(Type.BOT_OFF);
        assertThat(d.reply()).isNull();
        assertThat(count("SELECT count(*) FROM handoff")).isZero();
        assertThat(count("SELECT count(*) FROM bot_event WHERE event_type = 'BOT_OFF'")).isEqualTo(1);
    }

    @Test
    void congTacToanHeThongVaTungNhomDeuPhaiBat() {
        settings.set(AppSettings.BOT_ENABLED, "true", "test");
        assertThat(engine().handle(msg(SenderKind.CUSTOMER, "muốn mua thêm")).type()).isEqualTo(Type.BOT_OFF);

        jdbc.sql("UPDATE zalo_group SET bot_enabled = TRUE").update();
        assertThat(engine().handle(msg(SenderKind.CUSTOMER, "muốn mua thêm")).type()).isEqualTo(Type.HANDOFF);

        settings.set(AppSettings.BOT_ENABLED, "false", "test"); // tắt khẩn cấp
        assertThat(engine().handle(msg(SenderKind.CUSTOMER, "hàng bị lỗi")).type()).isEqualTo(Type.BOT_OFF);
    }

    @Test
    void nhomKhongTonTaiKhongLamGi() {
        botOn();
        var d = engine().handle(new IncomingMessage(999_999, "x1", SenderKind.CUSTOMER, "mua thêm"));
        assertThat(d.type()).isEqualTo(Type.UNKNOWN_GROUP);
        assertThat(count("SELECT count(*) FROM bot_event")).isZero();
    }

    // ---------- báo người thật, không tự chốt đơn ----------

    @Test
    void yDinhMuaBaoMeVaKhongBaoGioTuNhanDaChotDon() {
        botOn();

        var d = engine().handle(msg(SenderKind.CUSTOMER, "Mình muốn đặt hàng thêm 3 cái"));

        assertThat(d.type()).isEqualTo(Type.HANDOFF);
        assertThat(d.intent()).isEqualTo(Intent.ORDER);
        assertThat(d.reply()).startsWith("Trợ lý cửa hàng:").contains("báo cho chủ cửa hàng");
        assertThat(d.reply().toLowerCase()).doesNotContain("chốt").doesNotContain("đã đặt").doesNotContain("xác nhận đơn");
        assertThat(d.dryRun()).isTrue();
        assertThat(d.sent()).isFalse();
        var open = handoffs.list(true);
        assertThat(open).hasSize(1);
        assertThat(open.getFirst().reason()).isEqualTo(HandoffService.Reason.ORDER);
        assertThat(open.getFirst().status()).isEqualTo("OPEN");
    }

    @Test
    void khieuNaiVaMuonGapNguoiThatCungBaoMe() {
        botOn();
        engine().handle(msg(SenderKind.CUSTOMER, "Hàng bị lỗi, tôi không hài lòng"));
        assertThat(handoffs.list(true).getFirst().reason()).isEqualTo(HandoffService.Reason.COMPLAINT);
        handoffs.resolve(handoffs.list(true).getFirst().id());

        engine().handle(msg(SenderKind.CUSTOMER, "Cho mình gặp người thật"));
        assertThat(handoffs.list(true).getFirst().reason()).isEqualTo(HandoffService.Reason.HUMAN);
    }

    @Test
    void trichDoanChoMeDaCheSoDienThoaiVaBotNhacDungGuiThongTinRieng() {
        botOn();

        var d = engine().handle(msg(SenderKind.CUSTOMER, "Mình muốn mua thêm, gọi 0912345678 nhé"));

        assertThat(d.reply()).contains("không gửi số điện thoại hay địa chỉ");
        String excerpt = handoffs.list(true).getFirst().excerpt();
        assertThat(excerpt).contains("[số điện thoại]").doesNotContain("0912345678");
        assertThat(count("SELECT count(*) FROM bot_event WHERE reply_text LIKE '%0912345678%'")).isZero();
    }

    @Test
    void khiDaCoViecDangMoThiKhongBaoLaiVaKhongNhacKhachLanNua() {
        botOn();
        engine().handle(msg(SenderKind.CUSTOMER, "muốn mua thêm"));

        var again = engine().handle(msg(SenderKind.CUSTOMER, "mua thêm nhé, nhanh giúp"));

        assertThat(again.type()).isEqualTo(Type.HANDOFF);
        assertThat(again.reply()).isNull();
        assertThat(count("SELECT count(*) FROM handoff")).isEqualTo(1);

        handoffs.resolve(handoffs.list(true).getFirst().id());
        assertThat(engine().handle(msg(SenderKind.CUSTOMER, "muốn mua thêm")).reply()).isNotNull();
        assertThat(count("SELECT count(*) FROM handoff")).isEqualTo(2);
    }

    // ---------- FAQ ----------

    @Test
    void traLoiBangFaqCuaCuaHangVaChiNhuVay() {
        botOn();
        faqs.create("Giặt áo", "giặt, giặt máy, vệ sinh", "Nên giặt tay nước lạnh, không vắt mạnh.");

        var d = engine().handle(msg(SenderKind.CUSTOMER, "Áo này giặt máy được không?"));

        assertThat(d.type()).isEqualTo(Type.ANSWERED);
        assertThat(d.reply()).isEqualTo("Trợ lý cửa hàng: Nên giặt tay nước lạnh, không vắt mạnh.");
        assertThat(count("SELECT count(*) FROM handoff")).isZero();
    }

    @Test
    void faqDaTatKhongDuocDung() {
        botOn();
        long id = faqs.create("Giặt áo", "giặt", "Giặt tay.");
        faqs.update(id, "Giặt áo", "giặt", "Giặt tay.", false);

        var d = engine().handle(msg(SenderKind.CUSTOMER, "Cách giặt thế nào?"));

        assertThat(d.type()).isEqualTo(Type.HANDOFF);
        assertThat(handoffs.list(true).getFirst().reason()).isEqualTo(HandoffService.Reason.UNSURE);
    }

    @Test
    void yDinhMuaRoRangVaKhieuNaiLuonDenUuTienHonFaq() {
        botOn();
        faqs.create("Giặt áo", "giặt, mua, hỏng", "Giặt tay.");

        assertThat(engine().handle(msg(SenderKind.CUSTOMER, "Mình muốn mua thêm, giặt thế nào?")).type())
                .isEqualTo(Type.HANDOFF);
        handoffs.resolve(handoffs.list(true).getFirst().id());
        assertThat(engine().handle(msg(SenderKind.CUSTOMER, "Áo bị hỏng khi giặt")).type()).isEqualTo(Type.HANDOFF);
    }

    @Test
    void hoiGiaCoFaqThiTraLoiKhongCoFaqThiBaoMeKhongBiaGia() {
        botOn();
        var none = engine().handle(msg(SenderKind.CUSTOMER, "Áo này giá bao nhiêu vậy?"));
        assertThat(none.type()).isEqualTo(Type.HANDOFF);
        assertThat(none.reply()).doesNotContainPattern("\\d{3}");
        handoffs.resolve(handoffs.list(true).getFirst().id());

        faqs.create("Giá áo", "giá", "Giá áo hiện là theo bảng giá cửa hàng đã duyệt, mẹ sẽ báo cụ thể.");
        var withFaq = engine().handle(msg(SenderKind.CUSTOMER, "Áo này giá bao nhiêu vậy?"));
        assertThat(withFaq.type()).isEqualTo(Type.ANSWERED);
    }

    @Test
    void cauHoiChuaCoFaqThiBaoMeVoiLyDoBotChuaChac() {
        botOn();

        var d = engine().handle(msg(SenderKind.CUSTOMER, "Cho hỏi bảo quản áo thế nào?"));

        assertThat(d.type()).isEqualTo(Type.HANDOFF);
        assertThat(d.reply()).contains("chưa chắc");
        assertThat(handoffs.list(true).getFirst().reason()).isEqualTo(HandoffService.Reason.UNSURE);
    }

    @Test
    void traoDoiThuongGiuaCacKhachKhongBiChenNgang() {
        botOn();

        var d = engine().handle(msg(SenderKind.CUSTOMER, "Hôm nay trời đẹp quá mọi người"));

        assertThat(d.type()).isEqualTo(Type.IGNORED);
        assertThat(d.reply()).isNull();
        assertThat(count("SELECT count(*) FROM handoff")).isZero();
    }

    @Test
    void aiChiNhanVanBanDaCheVaCauFaqDaDuyet() {
        botOn();
        faqs.create("Giặt", "giặt", "Giặt tay.");
        List<String> seen = new ArrayList<>();
        AiProvider ai = (question, answers) -> {
            seen.add(question + "|" + String.join(";", answers));
            return Optional.of("Bạn thử liên hệ cửa hàng nhé.");
        };

        var d = engine(new NoopBotChannel(), ai).handle(msg(SenderKind.CUSTOMER, "Cho hỏi sao có 0912345678 gọi hoài?"));

        assertThat(seen).hasSize(1);
        assertThat(seen.getFirst()).doesNotContain("0912345678").contains("[số điện thoại]").endsWith("|Giặt tay.");
        assertThat(d.reply()).isEqualTo("Trợ lý cửa hàng: Bạn thử liên hệ cửa hàng nhé.");
    }

    // ---------- không trả lời trùng, không tự trả lời mình ----------

    @Test
    void motTinChiXuLyMotLanDuGoiLai() {
        botOn();
        var first = new IncomingMessage(group, "same-id", SenderKind.CUSTOMER, "muốn mua thêm");

        assertThat(engine().handle(first).type()).isEqualTo(Type.HANDOFF);
        assertThat(engine().handle(first).type()).isEqualTo(Type.DUPLICATE);

        assertThat(count("SELECT count(*) FROM bot_event")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM handoff")).isEqualTo(1);
    }

    @Test
    void khongTraLoiTinCuaChinMinh() {
        botOn();
        var d = engine().handle(msg(SenderKind.BOT, "Trợ lý cửa hàng: Mình đã báo cho chủ cửa hàng, mua thêm"));
        assertThat(d.type()).isEqualTo(Type.SELF);
        assertThat(count("SELECT count(*) FROM handoff")).isZero();
    }

    @Test
    void meVuaTraLoiThiBotImLangDenKhiHetThoiGianCho() {
        botOn();
        assertThat(engine().handle(msg(SenderKind.OWNER, "Dạ em gửi chị nhé")).type()).isEqualTo(Type.HUMAN_ACTIVE);

        assertThat(engine().handle(msg(SenderKind.CUSTOMER, "muốn mua thêm")).type()).isEqualTo(Type.HUMAN_ACTIVE);
        assertThat(count("SELECT count(*) FROM handoff")).isZero();

        jdbc.sql("UPDATE zalo_group SET human_active_until = now() - interval '1 minute'").update();
        assertThat(engine().handle(msg(SenderKind.CUSTOMER, "muốn mua thêm")).type()).isEqualTo(Type.HANDOFF);
    }

    @Test
    void khongTraLoiQuaDayTrongNamPhut() {
        botOn();
        faqs.create("Giặt", "giặt", "Giặt tay.");
        for (int i = 0; i < 3; i++) {
            assertThat(engine().handle(msg(SenderKind.CUSTOMER, "Giặt thế nào?")).type()).isEqualTo(Type.ANSWERED);
        }

        assertThat(engine().handle(msg(SenderKind.CUSTOMER, "Giặt thế nào?")).type()).isEqualTo(Type.RATE_LIMITED);
    }

    // ---------- gửi thật qua kênh ----------

    @Test
    void chayThuKhongGuiVaTatCheDoThuMoiGuiQuaKenh() {
        botOn();
        RecordingChannel channel = new RecordingChannel();

        var dry = engine(channel, mockAi).handle(msg(SenderKind.CUSTOMER, "muốn mua thêm"));
        assertThat(dry.dryRun()).isTrue();
        assertThat(channel.sent).isEmpty();
        assertThat(count("SELECT count(*) FROM bot_event WHERE dry_run AND NOT sent AND reply_text IS NOT NULL"))
                .isEqualTo(1);

        handoffs.resolve(handoffs.list(true).getFirst().id());
        settings.set(AppSettings.BOT_DRY_RUN, "false", "test");
        var real = engine(channel, mockAi).handle(msg(SenderKind.CUSTOMER, "muốn mua thêm"));
        assertThat(real.dryRun()).isFalse();
        assertThat(real.sent()).isTrue();
        assertThat(channel.sent).hasSize(1);
    }

    @Test
    void kenhHongKhongLamBotHongVaGhiLaChuaGui() {
        botOn();
        settings.set(AppSettings.BOT_DRY_RUN, "false", "test");
        RecordingChannel channel = new RecordingChannel();
        channel.fail = true;

        var d = engine(channel, mockAi).handle(msg(SenderKind.CUSTOMER, "muốn mua thêm"));

        assertThat(d.type()).isEqualTo(Type.HANDOFF);
        assertThat(d.sent()).isFalse();
        assertThat(handoffs.list(true)).hasSize(1); // vẫn báo mẹ dù kênh hỏng
    }

    @Test
    void thuBotKhongGhiGiVaoCoSoDuLieu() {
        botOn();

        var d = engine().preview("Mình muốn mua thêm 2 cái");

        assertThat(d.type()).isEqualTo(Type.HANDOFF);
        assertThat(engine().preview("trời đẹp quá").type()).isEqualTo(Type.IGNORED);
        assertThat(count("SELECT count(*) FROM bot_event")).isZero();
        assertThat(count("SELECT count(*) FROM handoff")).isZero();
    }

    // ---------- việc cần mẹ xử lý ----------

    @Test
    void viecChuyenTuMoiSangDaNhanSangXongVaKhongNhanHaiLan() {
        botOn();
        engine().handle(msg(SenderKind.CUSTOMER, "muốn mua thêm"));
        long id = handoffs.list(true).getFirst().id();
        assertThat(handoffs.openCount()).isEqualTo(1);

        handoffs.acknowledge(id);
        assertThat(handoffs.openCount()).isZero();
        assertThat(handoffs.list(true).getFirst().status()).isEqualTo("ACKNOWLEDGED");
        assertThatThrownBy(() -> handoffs.acknowledge(id)).isInstanceOf(ImportException.class);

        handoffs.resolve(id);
        assertThat(handoffs.list(true)).isEmpty();
        assertThat(handoffs.list(false)).hasSize(1);
        assertThatThrownBy(() -> handoffs.resolve(id)).isInstanceOf(ImportException.class);
    }

    @Test
    void faqKiemTraDauVao() {
        assertThatThrownBy(() -> faqs.create("", "a", "b")).isInstanceOf(ImportException.class);
        assertThatThrownBy(() -> faqs.create("t", "", "b")).isInstanceOf(ImportException.class);
        assertThatThrownBy(() -> faqs.create("t", "a", " ")).isInstanceOf(ImportException.class);
        assertThatThrownBy(() -> faqs.update(12345, "t", "a", "b", true)).isInstanceOf(ImportException.class);
        long id = faqs.create("t", "a", "b");
        faqs.delete(id);
        assertThat(faqs.list()).isEmpty();
    }
}
