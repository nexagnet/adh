package vn.shop.crm.imports;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FieldKeyTest {

    /** Tên cột đúng như file "Danh sách vận đơn" Viettel Post xuất ngày 21/11/2026 (chỉ tên cột, không có dữ liệu). */
    private static final List<String> VTP_REAL_HEADERS = List.of(
            "STT", "Mã Vận Đơn", "Mã đơn hàng", "Ngày tạo", "Người gửi", "Địa chỉ gửi", "SĐT Người Gửi",
            "Xã/Phường gửi", "Quận/Huyện gửi", "Tỉnh/Thành phố gửi", "Người nhận", "Địa chỉ nhận", "ĐT Nhận",
            "Xã/Phường nhận", "Quận/Huyện nhận", "Tỉnh/Thành phố nhận", "Tên Hàng", "Giá trị", "Trọng lượng",
            "Trạng Thái", "Lý do", "Ngày chuyển trạng thái", "Tên người nhận khi phát hàng",
            "Số điện thoại người nhận khi phát hàng", "Ghi chú", "Tài khoản tạo đơn");

    @Test
    void nhanDungCotCuaFileViettelPostThat() {
        Map<FieldKey, String> guessed = FieldKey.guess(VTP_REAL_HEADERS);

        assertThat(guessed.get(FieldKey.TRACKING_NO)).isEqualTo("Mã Vận Đơn");
        assertThat(guessed.get(FieldKey.ORDER_REF)).isEqualTo("Mã đơn hàng");
        // phải là cột SĐT người nhận, không phải SĐT người gửi, cũng không phải "số điện thoại khi phát hàng"
        assertThat(guessed.get(FieldKey.RECEIVER_PHONE)).isEqualTo("ĐT Nhận");
        assertThat(guessed.get(FieldKey.RECEIVER_NAME)).isEqualTo("Người nhận");
        assertThat(guessed.get(FieldKey.RECEIVER_ADDRESS)).isEqualTo("Địa chỉ nhận");
        assertThat(guessed.get(FieldKey.STATUS)).isEqualTo("Trạng Thái");
        assertThat(guessed.get(FieldKey.CREATED_ON)).isEqualTo("Ngày tạo");
    }

    @Test
    void khongLayTinhGuiLamDiaChiHanhChinh() {
        // "Tỉnh/Thành phố gửi" đứng trước cột "…nhận": phải chọn tỉnh NHẬN, không phải tỉnh của cửa hàng.
        assertThat(FieldKey.guess(VTP_REAL_HEADERS).get(FieldKey.ADMIN_AREA)).isEqualTo("Tỉnh/Thành phố nhận");
        assertThat(FieldKey.guess(List.of("Mã Vận Đơn", "Địa chỉ gửi", "Tỉnh/Thành phố gửi", "ĐT Nhận", "Trạng Thái"))
                .get(FieldKey.ADMIN_AREA)).isNull();
    }
}
