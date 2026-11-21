package vn.shop.crm.shipping;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StatusKeyTest {

    @Test
    void boDauChuThuongGopKhoangTrang() {
        assertThat(StatusKey.of("Giao thành công")).isEqualTo("giao thanh cong");
        assertThat(StatusKey.of("  ĐÃ   HỦY ")).isEqualTo("da huy");
        assertThat(StatusKey.of("Đang chuyển hoàn")).isEqualTo("dang chuyen hoan");
    }

    @Test
    void nullThanhChuoiRong() {
        assertThat(StatusKey.of(null)).isEmpty();
    }
}
