package vn.shop.crm.performance;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class EconomicsTest {

    private static PerformanceSettings settings(String discount, boolean freeShip, String fee) {
        return new PerformanceSettings(new BigDecimal(discount), freeShip, fee == null ? null : new BigDecimal(fee), "");
    }

    @Test
    void giaTikTokVaChiPhiUuDai() {
        // Bán qua nhóm 900.000 đ, rẻ hơn TikTok 10% => giá TikTok 1.000.000 đ, ưu đãi 100.000 đ.
        var e = Economics.of(new BigDecimal("900000"), new BigDecimal("60000"), settings("10", true, null));
        assertThat(e.tiktokPrice()).isEqualByComparingTo("1000000");
        assertThat(e.discountCost()).isEqualByComparingTo("100000");
        assertThat(e.shipCost()).isEqualByComparingTo("60000");
        // Hòa vốn khi TikTok thu (100.000 + 60.000) / 1.000.000 = 16%.
        assertThat(e.breakEvenFeePercent()).isEqualByComparingTo("16.0");
        assertThat(e.netGain()).isNull();
    }

    @Test
    void coPhiTikTokThiTinhRaLoiLo() {
        var e = Economics.of(new BigDecimal("900000"), new BigDecimal("60000"), settings("10", true, "20"));
        // TikTok thu 20% của 1.000.000 = 200.000; trừ ưu đãi 100.000 và ship 60.000 => lời 40.000.
        assertThat(e.tiktokFeeAvoided()).isEqualByComparingTo("200000");
        assertThat(e.netGain()).isEqualByComparingTo("40000");

        var loss = Economics.of(new BigDecimal("900000"), new BigDecimal("60000"), settings("10", true, "12"));
        assertThat(loss.netGain()).isEqualByComparingTo("-40000");
    }

    @Test
    void khongMienShipThiKhongTinhCuoc() {
        var e = Economics.of(new BigDecimal("900000"), new BigDecimal("60000"), settings("10", false, "12"));
        assertThat(e.shipCost()).isEqualByComparingTo("0");
        assertThat(e.netGain()).isEqualByComparingTo("20000");
    }

    @Test
    void chuaCoDoanhThuHoacUuDaiVoLyThiKhongTinh() {
        assertThat(Economics.of(BigDecimal.ZERO, BigDecimal.ZERO, settings("10", true, "12")).breakEvenFeePercent()).isNull();
        assertThat(Economics.of(null, null, settings("10", true, "12")).netGain()).isNull();
        // Ưu đãi 100% sẽ chia cho 0: phải từ chối thay vì lỗi.
        assertThat(Economics.of(new BigDecimal("1000"), BigDecimal.ZERO, settings("100", true, "12")).breakEvenFeePercent()).isNull();
    }

    @Test
    void uuDaiBangKhongThiGiaBangNhau() {
        var e = Economics.of(new BigDecimal("500000"), BigDecimal.ZERO, settings("0", true, "10"));
        assertThat(e.discountCost()).isEqualByComparingTo("0");
        assertThat(e.netGain()).isEqualByComparingTo("50000");
    }
}
