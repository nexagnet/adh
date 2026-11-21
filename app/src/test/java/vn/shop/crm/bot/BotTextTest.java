package vn.shop.crm.bot;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BotTextTest {

    @Test
    void cheSoDienThoaiEmailVaSoDai() {
        for (String phone : new String[]{"0912345678", "0912 345 678", "0912.345.678", "+84912345678", "84912345678"}) {
            assertThat(PiiMasker.mask("Gọi mình số " + phone + " nhé")).as(phone)
                    .isEqualTo("Gọi mình số [số điện thoại] nhé");
        }
        assertThat(PiiMasker.mask("mail a.b@gmail.com nha")).isEqualTo("mail [email] nha");
        assertThat(PiiMasker.mask("thẻ 4111 1111 1111 1111")).isEqualTo("thẻ [số]");
        assertThat(PiiMasker.mask(null)).isEmpty();
    }

    @Test
    void giuNguyenTinKhongChuaThongTinRieng() {
        String text = "Áo này giặt máy được không? Mua 2 cái hôm 12/05";
        assertThat(PiiMasker.mask(text)).isEqualTo(text);
        assertThat(PiiMasker.containsPii(text)).isFalse();
        assertThat(PiiMasker.containsPii("sdt 0912345678")).isTrue();
    }

    @Test
    void trichDoanNganVaDaChe() {
        String long300 = "a".repeat(300);
        assertThat(PiiMasker.excerpt(long300, 200)).hasSize(200).endsWith("…");
        assertThat(PiiMasker.excerpt("  gọi 0912345678   nhé ", 200)).isEqualTo("gọi [số điện thoại] nhé");
    }

    @Test
    void chuanHoaTiengVietBoDauVaKhopTheoTu() {
        assertThat(TextNorm.words("Hàng bị LỖI!")).isEqualTo(" hang bi loi ");
        assertThat(TextNorm.containsPhrase(TextNorm.words("Đặt hàng giúp mình"), "dat hang")).isTrue();
        // khớp theo từ, không khớp chuỗi con: "mua" không nằm trong "mua hang"
        assertThat(TextNorm.containsPhrase(TextNorm.words("muaban"), "mua")).isFalse();
        // bỏ dấu nên "mưa" trùng "mua": nhận nhầm thì chỉ báo mẹ thừa, đây là hướng sai an toàn (chấp nhận)
        assertThat(TextNorm.containsPhrase(TextNorm.words("trời mưa to"), "mua")).isTrue();
        assertThat(TextNorm.containsPhrase(TextNorm.words("hôm nay mua gì"), "mua")).isTrue();
    }

    @Test
    void nhanDienYDinh() {
        assertThat(IntentClassifier.classify("Cho mình gặp chủ shop với")).isEqualTo(Intent.WANTS_HUMAN);
        assertThat(IntentClassifier.classify("Hàng bị lỗi rồi shop ơi")).isEqualTo(Intent.COMPLAINT);
        assertThat(IntentClassifier.classify("khieu nai")).isEqualTo(Intent.COMPLAINT);
        assertThat(IntentClassifier.classify("Mình muốn mua thêm 2 cái")).isEqualTo(Intent.ORDER);
        assertThat(IntentClassifier.classify("Giá bao nhiêu vậy")).isEqualTo(Intent.ORDER);
        assertThat(IntentClassifier.classify("Áo giặt máy được không?")).isEqualTo(Intent.QUESTION);
        assertThat(IntentClassifier.classify("Hôm nay trời đẹp quá")).isEqualTo(Intent.OTHER);
        assertThat(IntentClassifier.classify(null)).isEqualTo(Intent.OTHER);
        assertThat(IntentClassifier.strongOrder("đặt hàng giúp mình")).isTrue();
        assertThat(IntentClassifier.strongOrder("giá bao nhiêu")).isFalse();
        assertThat(IntentClassifier.weakOrder("giá bao nhiêu")).isTrue();
    }
}
