package vn.shop.crm.common;

import org.junit.jupiter.api.Test;
import vn.shop.crm.bot.BotEngine;
import vn.shop.crm.bot.Intent;

import static org.assertj.core.api.Assertions.assertThat;

/** Giao diện không được hiện mã kỹ thuật bằng tiếng Anh: mọi mã đang dùng đều phải có nhãn tiếng Việt. */
class LabelsTest {

    private final Labels labels = new Labels();

    @Test
    void moiKetQuaCuaBotDeuCoNhan() {
        for (BotEngine.Type t : BotEngine.Type.values()) {
            assertThat(labels.botResult(t.name())).as(t.name()).isNotBlank().isNotEqualTo("Khác").isNotEqualTo(t.name());
        }
    }

    @Test
    void moiNguoiNhanCoNhan() {
        for (BotEngine.SenderKind k : BotEngine.SenderKind.values()) {
            assertThat(labels.sender(k.name())).as(k.name()).isNotBlank().isNotEqualTo("Khác").isNotEqualTo(k.name());
        }
    }

    @Test
    void moiYDinhCoNhan() {
        for (Intent i : Intent.values()) {
            assertThat(labels.intent(i.name())).isEqualTo(i.label());
        }
        assertThat(labels.intent("LA_HOAC")).isEmpty();
    }

    @Test
    void kenhGuiVaNhatKyKhach() {
        assertThat(labels.channel("MOCK")).doesNotContain("MOCK");
        assertThat(labels.channel("MANUAL_EXPORT")).doesNotContain("MANUAL");
        for (String code : new String[]{"BASIS_APPROVED", "BLOCKED", "UNBLOCKED", "OPTED_OUT", "INVITATION_CREATED",
                "INVITATION_SENT", "REQUEST_OPENED", "EXPORTED", "ERASED"}) {
            assertThat(labels.contactEvent(code)).as(code).isNotBlank().isNotEqualTo("Khác").isNotEqualTo(code);
        }
        assertThat(labels.contactEvent("MA_LA")).isEqualTo("Khác");
    }

    @Test
    void bangChungDuyetHienTenHang() {
        assertThat(labels.evidence("VIETTEL_POST:123")).isEqualTo("Viettel Post, vận đơn 123");
        assertThat(labels.evidence("JT_EXPRESS:9")).isEqualTo("J&T Express, vận đơn 9");
        assertThat(labels.evidence(null)).isEmpty();
    }

    @Test
    void nguoiThaoTacHienThanhBoMeKhongHienTenDangNhap() {
        assertThat(labels.person("admin")).isEqualTo("Bố/Mẹ");
        assertThat(labels.person("system")).isEqualTo("Hệ thống");
        assertThat(labels.person(null)).isEmpty();
        assertThat(labels.person("co-tuan")).isEqualTo("co-tuan");
    }

    @Test
    void khongConChuBotTrongNhanHienRaGiaoDien() {
        for (BotEngine.Type t : BotEngine.Type.values()) {
            assertThat(labels.botResult(t.name())).doesNotContainPattern("(?i)\\bbot\\b");
        }
        for (BotEngine.SenderKind k : BotEngine.SenderKind.values()) {
            assertThat(labels.sender(k.name())).doesNotContainPattern("(?i)\\bbot\\b");
        }
    }
}
