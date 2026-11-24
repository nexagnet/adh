package vn.shop.crm.contact;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProductGroupTest {

    @Test
    void phanNhomGanDungTheoTuKhoa() {
        assertThat(ProductGroup.of("Áo điều hòa nam size L")).containsExactly(ProductGroup.AO_DIEU_HOA);
        assertThat(ProductGroup.of("AO DIEU HOA quạt đôi")).containsExactly(ProductGroup.AO_DIEU_HOA);
        assertThat(ProductGroup.of("Pin 20000mAh cho áo")).contains(ProductGroup.PIN);
        assertThat(ProductGroup.of("Pin sạc dự phòng 12V")).containsExactly(ProductGroup.PIN);
        assertThat(ProductGroup.of("Áo điều hòa kèm pin")).containsExactlyInAnyOrder(ProductGroup.AO_DIEU_HOA, ProductGroup.PIN);
        assertThat(ProductGroup.of("Dây sạc type C")).containsExactly(ProductGroup.PHU_KIEN_KHAC);
        assertThat(ProductGroup.of("Pinterest sticker")).containsExactly(ProductGroup.PHU_KIEN_KHAC);
        assertThat(ProductGroup.of(null)).containsExactly(ProductGroup.CHUA_RO);
        assertThat(ProductGroup.of("   ")).containsExactly(ProductGroup.CHUA_RO);
        assertThat(ProductGroup.CHUA_RO.filterable()).isFalse();
        assertThat(ProductGroup.PIN.filterable()).isTrue();
    }
}
