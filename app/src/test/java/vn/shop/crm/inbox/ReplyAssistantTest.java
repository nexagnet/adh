package vn.shop.crm.inbox;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Cách đưa tin của khách cho AI soạn câu: một tin giữ nguyên, nhiều tin tách thành từng lượt có số. */
class ReplyAssistantTest {

    @Test
    void motTinGiuNguyenKhuon() {
        assertThat(ReplyAssistant.turns("Ưu đãi như nào")).isEqualTo("Tin của khách:\n<<<\nƯu đãi như nào\n>>>");
    }

    @Test
    void nhieuTinTachTungLuotTinMoiNhatDungCuoi() {
        String out = ReplyAssistant.turns("Vào nhóm có mất phí không | Cho tôi biết ưu đãi bên bạn");
        assertThat(out).contains("2 tin").contains("Tin 1:\n<<<\nVào nhóm có mất phí không\n>>>")
                .contains("Tin 2 (mới nhất):\n<<<\nCho tôi biết ưu đãi bên bạn\n>>>").doesNotContain(" | ");
        assertThat(out.indexOf("Tin 1")).isLessThan(out.indexOf("Tin 2"));
    }
}
