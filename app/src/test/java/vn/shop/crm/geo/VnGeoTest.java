package vn.shop.crm.geo;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class VnGeoTest {

    private static String code(String address) {
        return VnGeo.detect(address).map(Province::code).orElse(null);
    }

    @Test
    void coDuBaMuoiBaTinhVaMoiTinhCoVungVaToaDo() {
        assertThat(VnGeo.all()).hasSize(63);
        assertThat(VnGeo.all()).allSatisfy(p -> {
            assertThat(p.lat()).isBetween(8.0, 24.0);
            assertThat(p.lon()).isBetween(102.0, 110.0);
            assertThat(p.sub()).isNotNull();
        });
        assertThat(VnGeo.all().stream().map(Province::code).distinct()).hasSize(63);
        assertThat(VnGeo.in(Region.BAC)).hasSize(25);
        assertThat(VnGeo.in(Region.TRUNG)).hasSize(19);
        assertThat(VnGeo.in(Region.NAM)).hasSize(19);
    }

    @Test
    void nhanTenTinhCoDauVaKhongDau() {
        assertThat(code("12 Lê Lợi, Phường Bến Nghé, Quận 1, Thành phố Hồ Chí Minh")).isEqualTo("tphcm");
        assertThat(code("so 5 ngo 10 pho Hue, Hai Ba Trung, Ha Noi")).isEqualTo("ha-noi");
        assertThat(code("xom 3, xa A, huyen B, tinh Nghe An")).isEqualTo("nghe-an");
        assertThat(code("Thôn 2, xã X, huyện Y, Đắk Lắk")).isEqualTo("dak-lak");
        assertThat(code("thon 2, xa X, huyen Y, Dac Lac")).isEqualTo("dak-lak");
        assertThat(code("123 Trần Hưng Đạo, TP.HCM")).isEqualTo("tphcm");
        assertThat(code("45 duong so 3, Sai Gon")).isEqualTo("tphcm");
        assertThat(code("Số 9, TP Bà Rịa - Vũng Tàu")).isEqualTo("ba-ria-vung-tau");
        assertThat(code("Số 9, P. Thắng Nhì, Vũng Tàu")).isEqualTo("ba-ria-vung-tau");
        assertThat(code("Phường 3, thành phố Huế")).isEqualTo("hue");
        assertThat(code("Số 1, Thừa Thiên Huế")).isEqualTo("hue");
    }

    @Test
    void layTenCuoiCungVaBoQuaTenPhuongXaDuong() {
        // địa chỉ có tên phường trùng tên tỉnh khác: tỉnh là tên đứng cuối
        assertThat(code("Phường Hòa Bình, Quận Hoàng Mai, Hà Nội")).isEqualTo("ha-noi");
        assertThat(code("Đường Hồ Chí Minh, Hà Nội")).isEqualTo("ha-noi");
        assertThat(code("Đường Đà Nẵng, Ngô Quyền, Hải Phòng")).isEqualTo("hai-phong");
        // chỉ có tên phường/xã/đường trùng tên tỉnh, không có tỉnh: chưa rõ, không đoán
        assertThat(code("Xã Hòa Bình, huyện Đà Bắc")).isNull();
        assertThat(code("số 5 đường Hồ Chí Minh")).isNull();
        assertThat(code("Thị trấn Thái Bình, huyện X")).isNull();
        // thành phố cùng tên tỉnh thì nhận
        assertThat(code("Thành phố Hòa Bình")).isEqualTo("hoa-binh");
        assertThat(code("TP Thái Bình")).isEqualTo("thai-binh");
    }

    @Test
    void khongChacThiChuaRo() {
        assertThat(code(null)).isNull();
        assertThat(code("")).isNull();
        assertThat(code("   ")).isNull();
        assertThat(code("Số 5 đường Lê Lợi")).isNull();
        assertThat(code("Việt Nam")).isNull();
        // "Huệ" (tên người/đường) không phải Huế
        assertThat(code("Số 7 đường Lê Thị Huệ")).isNull();
        assertThat(code("x".repeat(1100) + " Hà Nội")).isNull();
    }

    @Test
    void tenCoCumDaiHonThangCumNganTrongCungVitri() {
        assertThat(code("Phường 1, Bà Rịa Vũng Tàu")).isEqualTo("ba-ria-vung-tau");
        assertThat(code("Quận 3, TP Hồ Chí Minh")).isEqualTo("tphcm");
    }

    @Test
    void khuVucVaMucGan() {
        Province hn = VnGeo.byCode("ha-noi").orElseThrow();
        Province hp = VnGeo.byCode("hai-phong").orElseThrow();
        Province sg = VnGeo.byCode("tphcm").orElseThrow();
        Province ct = VnGeo.byCode("can-tho").orElseThrow();
        Province lc = VnGeo.byCode("lao-cai").orElseThrow();

        assertThat(GeoArea.province(hn).closeness(hn)).isZero();
        assertThat(GeoArea.province(hn).closeness(hp)).isEqualTo(1);
        assertThat(GeoArea.province(hn).closeness(lc)).isEqualTo(2);
        assertThat(GeoArea.province(hn).closeness(sg)).isEqualTo(-1);
        assertThat(GeoArea.sub(SubRegion.DB_SONG_HONG).closeness(hp)).isEqualTo(1);
        assertThat(GeoArea.sub(SubRegion.DB_SONG_HONG).closeness(lc)).isEqualTo(2);
        assertThat(GeoArea.sub(SubRegion.DB_SONG_HONG).closeness(ct)).isEqualTo(-1);
        assertThat(GeoArea.region(Region.NAM).closeness(ct)).isEqualTo(2);
        assertThat(GeoArea.region(Region.NAM).closeness(hn)).isEqualTo(-1);

        assertThat(GeoArea.parse(GeoArea.region(Region.NAM).code())).contains(GeoArea.region(Region.NAM));
        assertThat(GeoArea.parse("P:ha-noi").orElseThrow().label()).isEqualTo("Hà Nội");
        assertThat(GeoArea.parse("S:DONG_BAC").orElseThrow().label()).isEqualTo("Đông Bắc");
        assertThat(GeoArea.parse("R:XYZ")).isEmpty();
        assertThat(GeoArea.parse("P:khong-co")).isEmpty();
        assertThat(GeoArea.parse("zzz")).isEmpty();
        assertThat(GeoArea.parse(null)).isEmpty();
    }
}
