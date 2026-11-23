package vn.shop.crm.inbox;

import org.junit.jupiter.api.Test;
import vn.shop.crm.inbox.WarrantyAssistant.Category;
import vn.shop.crm.inbox.WarrantyAssistant.Outcome;
import vn.shop.crm.inbox.WarrantyAssistant.Urgency;

import static org.assertj.core.api.Assertions.assertThat;

/** Khuôn đầu ra của AI phân loại bảo hành và khiếu nại: sai khuôn thì không bao giờ được dùng. */
class WarrantyAssistantTest {

    private static String json(String cat, String urg, String sum, String photo, String draft) {
        return "{\"category\":\"" + cat + "\",\"urgency\":\"" + urg + "\",\"summary\":\"" + sum + "\",\"needs_photo\":" + photo + ",\"draft\":\"" + draft + "\"}";
    }

    @Test
    void khuonDungThiDoc() {
        var t = WarrantyAssistant.parse(json("GIAO_HANG", "THUONG", "  Chưa nhận   hàng ", "false", "Dạ em xin lỗi ạ."));
        assertThat(t.outcome()).isEqualTo(Outcome.OK);
        assertThat(t.category()).isEqualTo(Category.GIAO_HANG);
        assertThat(t.urgency()).isEqualTo(Urgency.THUONG);
        assertThat(t.summary()).isEqualTo("Chưa nhận hàng");
        assertThat(t.needsPhoto()).isFalse();
        assertThat(t.draft()).isEqualTo("Dạ em xin lỗi ạ.");
    }

    @Test
    void sai_khuon_thi_khong_dung() {
        assertThat(WarrantyAssistant.parse("").outcome()).isEqualTo(Outcome.INVALID);
        assertThat(WarrantyAssistant.parse("[]").outcome()).isEqualTo(Outcome.INVALID);
        assertThat(WarrantyAssistant.parse(json("LA", "CAO", "x", "true", "")).outcome()).isEqualTo(Outcome.INVALID);
        assertThat(WarrantyAssistant.parse(json("KHAC", "RAT_GAP", "x", "true", "")).outcome()).isEqualTo(Outcome.INVALID);
        assertThat(WarrantyAssistant.parse(json("KHAC", "CAO", "x", "\"true\"", "")).outcome()).isEqualTo(Outcome.INVALID);
        assertThat(WarrantyAssistant.parse("{\"category\":\"KHAC\",\"urgency\":\"CAO\"}").outcome()).isEqualTo(Outcome.INVALID);
        assertThat(WarrantyAssistant.parse(json("KHAC", "CAO", "x", "true", "") .replace("}", ",\"extra\":1}")).outcome()).isEqualTo(Outcome.INVALID);
    }

    @Test
    void haiGiaTriDanhRiengChoHeThongKhongDuocDoTuAi() {
        assertThat(WarrantyAssistant.parse(json("CHUA_PHAN_LOAI", "CAO", "x", "true", "")).outcome()).isEqualTo(Outcome.INVALID);
        assertThat(WarrantyAssistant.parse(json("KHAC", "CHUA_RO", "x", "true", "")).outcome()).isEqualTo(Outcome.INVALID);
    }

    @Test
    void tomTatQuaDaiDuocCat() {
        var t = WarrantyAssistant.parse(json("KHAC", "THAP", "a".repeat(900), "false", ""));
        assertThat(t.summary()).hasSize(WarrantyAssistant.SUMMARY_MAX);
    }
}
