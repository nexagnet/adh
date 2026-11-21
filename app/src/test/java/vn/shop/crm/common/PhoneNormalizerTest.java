package vn.shop.crm.common;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class PhoneNormalizerTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "0912345678", "+84912345678", "84912345678", "912345678", "0912 345 678",
            "0912.345.678", "912345678.0", "  0912345678  ", "+84 912 345 678"})
    void cacDinhDangKhacNhauVeCungMotSo(String raw) {
        assertThat(PhoneNormalizer.toE164(raw)).isEqualTo(Optional.of("+84912345678"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "abc", "12345", "0912****78", "091234567", "0912345678901",
            "0912345678 / 0987654321", "+1 650 253 0000"})
    void soSaiHoacThieuKhongDuocSuyDien(String raw) {
        assertThat(PhoneNormalizer.toE164(raw)).isEmpty();
    }

    @Test
    void oCoNhieuSoLayDungSoHopLeDauTien() {
        assertThat(PhoneNormalizer.firstValid("0912345678, 0987654321")).contains("+84912345678");
        assertThat(PhoneNormalizer.firstValid("0912****78; 0987654321")).contains("+84987654321");
        assertThat(PhoneNormalizer.firstValid("0912345678")).contains("+84912345678");
        assertThat(PhoneNormalizer.firstValid("abc, 12345")).isEmpty();
        assertThat(PhoneNormalizer.firstValid(null)).isEmpty();
    }

    @Test
    void hienThiDangQuocGia() {
        assertThat(PhoneNormalizer.toDisplay("+84912345678")).isEqualTo("0912 345 678");
    }
}
