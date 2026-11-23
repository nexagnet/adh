package vn.shop.crm.inbox;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Bộ kiểm cứng cho câu AI soạn (D20): thà chặn nhầm rồi chuyển người thật còn hơn để lọt một câu bịa. */
class ReplyValidatorTest {

    static final String INFO = "Khách mua trực tiếp qua nhóm Zalo được giảm 10% so với giá bán trên TikTok và được miễn phí ship. "
            + "Liên hệ bảo hành trực tiếp qua Zalo thuận tiện hơn. Cửa hàng Pin ADH";

    private static String check(String reply) {
        return ReplyValidator.check(reply, INFO).orElse("OK");
    }

    private static String checkNoWarranty(String reply) {
        return ReplyValidator.check(reply, "Khách mua qua nhóm được giảm 10% so với TikTok và miễn phí ship.").orElse("OK");
    }

    @Test
    void cauDungTrongBangThiQua() {
        assertThat(check("Dạ anh/chị vào nhóm mua trực tiếp sẽ được giảm 10% so với giá trên TikTok và miễn phí ship ạ.")).isEqualTo("OK");
        assertThat(check("Dạ anh/chị đồng ý kết bạn thì em thêm vào nhóm ạ.")).isEqualTo("OK");
    }

    @Test
    void chanRongVaQuaDai() {
        assertThat(check("  ")).isEqualTo("RONG");
        assertThat(check(null)).isEqualTo("RONG");
        assertThat(check("Dạ ".repeat(300))).isEqualTo("QUA_DAI");
    }

    @Test
    void chanDuongDanSoDienThoaiVaEmail() {
        assertThat(check("Anh/chị xem tại https://shop.example nhé")).isEqualTo("CO_LINK");
        assertThat(check("Vào www.shop.vn để biết thêm")).isEqualTo("CO_LINK");
        assertThat(check("Vào zalo.me/abc nhé")).isEqualTo("CO_LINK");
        assertThat(check("Gọi em số 0912 345 678 nhé")).isEqualTo("CO_SO_DIEN_THOAI_HOAC_EMAIL");
        assertThat(check("Mail a@b.com nhé")).isNotEqualTo("OK");
    }

    @Test
    void chanMoiConSoKhongCoTrongBang() {
        assertThat(check("Anh/chị được giảm 15% ạ.")).isEqualTo("CON_SO_NGOAI_BANG");
        assertThat(check("Dạ giá 500.000đ ạ")).isEqualTo("CON_SO_NGOAI_BANG");
        assertThat(check("Bảo hành 12 tháng ạ")).isEqualTo("CON_SO_NGOAI_BANG");
        assertThat(check("Giảm 10% ạ.")).isEqualTo("OK");
    }

    @Test
    void chanDoiTraHoanTienVaHuaHen() {
        assertThat(check("Bên TikTok đổi trả khó lắm ạ.")).isEqualTo("TU_CAM");
        assertThat(check("Em cam kết hàng tốt ạ.")).isEqualTo("TU_CAM");
        assertThat(check("Em đảm bảo chính hãng ạ.")).isEqualTo("TU_CAM");
        assertThat(check("Dạ hoàn tiền nhanh ạ.")).isEqualTo("TU_CAM");
        assertThat(check("Bên shop tặng thêm quà ạ.")).isEqualTo("TU_CAM");
    }

    @Test
    void chanNenTangKhacVaChePhan() {
        assertThat(check("Shopee thì đắt hơn ạ.")).isEqualTo("TU_CAM");
        assertThat(check("Bên khác toàn hàng giả ạ.")).isEqualTo("TU_CAM");
    }

    @Test
    void chanGiaLamNguoiVaXinThongTin() {
        assertThat(check("Em là người thật đây ạ.")).isEqualTo("TU_CAM");
        assertThat(check("Em là chủ shop ạ.")).isEqualTo("TU_CAM");
        assertThat(check("Anh/chị cho em xin địa chỉ nhé.")).isEqualTo("TU_CAM");
        assertThat(check("Anh/chị chuyển khoản trước nhé.")).isEqualTo("TU_CAM");
    }

    @Test
    void khongDauVanBiBat() {
        assertThat(check("Em cam ket hang tot a.")).isEqualTo("TU_CAM");
        assertThat(check("doi tra de lam a")).isEqualTo("TU_CAM");
    }

    @Test
    void tuNhayCamChiDuocNoiKhiBangCoChuDo() {
        assertThat(check("Liên hệ bảo hành qua Zalo thuận tiện hơn ạ.")).isEqualTo("OK");
        assertThat(checkNoWarranty("Liên hệ bảo hành qua Zalo thuận tiện hơn ạ.")).isEqualTo("NOI_NGOAI_BANG");
        assertThat(checkNoWarranty("Bên em còn hàng ạ.")).isEqualTo("NOI_NGOAI_BANG");
        assertThat(check("Dạ một triệu ạ")).isEqualTo("NOI_NGOAI_BANG");
    }

    @Test
    void dongYVaTangDuocPhanBietVoiTuDongNghiaTiengViet() {
        // "đồng ý" không được coi là đơn vị tiền "đồng"
        assertThat(check("Dạ nếu anh/chị đồng ý kết bạn thì em thêm vào nhóm ạ.")).isEqualTo("OK");
    }

    @Test
    void hoSoKhachChiMoThemConSoKhongMoThemTu() {
        String info = "Khách mua qua nhóm được giảm 10% so với TikTok và miễn phí ship.";
        String profile = "Ngày mua gần nhất: 05/01/2026. Số lần mua: 2. Hàng đã mua: Pin bảo hành đủ";
        assertThat(ReplyValidator.check("Dạ anh/chị mua 2 lần, gần nhất 05/01/2026, vào nhóm giảm 10% ạ.", info, profile)).isEmpty();
        assertThat(ReplyValidator.check("Dạ anh/chị mua 3 lần ạ.", info, profile)).contains("CON_SO_NGOAI_BANG");
        // chữ "bảo hành" chỉ có trong hồ sơ, không có trong bảng thông tin: vẫn bị chặn
        assertThat(ReplyValidator.check("Dạ bên em có bảo hành ạ.", info, profile)).contains("NOI_NGOAI_BANG");
        assertThat(ReplyValidator.check("Dạ anh/chị mua 2 lần ạ.", info)).contains("CON_SO_NGOAI_BANG"); // không có hồ sơ thì như cũ
    }
}
