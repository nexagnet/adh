package vn.shop.crm.zalo;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Kênh báo ngoài cấu hình sai thì tự tắt, không làm ứng dụng hỏng, không ném lỗi. */
class WebhookHealthNotifierTest {

    @Test
    void diaChiSaiHoacKhongPhaiHttpsThiTatKenhVaKhongNemLoi() {
        for (String url : new String[]{"not a url", "http://example.invalid/x", "ftp://example.invalid/x", "https:///thieu-may-chu"}) {
            assertThat(new WebhookHealthNotifier(url).send("thử")).as(url).isFalse();
        }
    }

    @Test
    void khongToiDuocMayChuThiTraFalseKhongNemLoi() {
        // .invalid không bao giờ phân giải được: phải trả false thay vì ném lỗi làm hỏng nhịp bộ hẹn giờ
        assertThat(new WebhookHealthNotifier("https://khong-ton-tai.invalid/chu-de").send("thử")).isFalse();
    }
}
