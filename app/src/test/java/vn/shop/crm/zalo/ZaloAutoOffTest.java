package vn.shop.crm.zalo;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import vn.shop.crm.AbstractPostgresTest;
import vn.shop.crm.common.ImportException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Cấu hình mặc định (không đặt gì): không có cầu nối, không có bộ hẹn giờ, không thể bật bằng công tắc trên giao diện. */
class ZaloAutoOffTest extends AbstractPostgresTest {

    @Autowired ApplicationContext context;
    @Autowired ZaloGateway gateway;
    @Autowired ZaloAutoService zalo;
    @Autowired ZaloAutoSettings cfg;

    @Test
    void macDinhLaBoNoiTayKhongCoBoHenGio() {
        assertThat(gateway).isInstanceOf(ManualZaloGateway.class);
        assertThat(context.getBeansOfType(ZaloAutoScheduler.class)).isEmpty();
        assertThat(context.getBeansOfType(ZcaBridgeGateway.class)).isEmpty();
        assertThat(gateway.status().state()).isEqualTo(ZaloGateway.State.DISABLED);
    }

    @Test
    void khongBatDuocBangCongTacGiaoDienKhiChuaBatBienMoiTruong() {
        assertThatThrownBy(() -> cfg.setMode(ZaloMode.CONFIRM, false, "mẹ")).isInstanceOf(ImportException.class)
                .hasMessageContaining("APP_ZALO_AUTO_ENABLED");
        assertThat(cfg.effectiveMode()).isEqualTo(ZaloMode.OFF);
        assertThat(zalo.poll()).isZero();
        assertThat(zalo.addNext()).isEqualTo(ZaloAutoService.Step.NOT_ACTIVE);
    }

    @Test
    void cacLoiGoiCuaBanMacDinhKhongLamGi() {
        assertThat(gateway.friendEvents(0)).isEmpty();
        assertThat(gateway.addToGroup("1", "2").outcome()).isEqualTo(ZaloGateway.Outcome.UNREACHABLE);
    }
}
