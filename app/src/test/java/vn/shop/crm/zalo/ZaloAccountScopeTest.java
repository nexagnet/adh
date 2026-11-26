package vn.shop.crm.zalo;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import vn.shop.crm.AbstractPostgresTest;
import vn.shop.crm.common.AppSettings;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D23, PR 3: cài đặt, nhật ký, hạn mức và cầu dao tách theo TÀI KHOẢN; tài khoản 1 giữ nguyên khóa cũ; Zalo báo hạn chế ở một tài khoản thì
 * dừng chung; tài khoản thêm mới bắt đầu hạn mức thấp; trần tổng cộng mọi tài khoản.
 */
class ZaloAccountScopeTest extends AbstractPostgresTest {

    @Autowired AppSettings settings;
    @Autowired ZaloAutoSettings auto;
    @Autowired ZaloMessageService messages;
    @Autowired ZaloAccountRegistry registry;

    private void secondAccount() {
        jdbc.sql("INSERT INTO zalo_account (id, label) VALUES (2, 'Tài khoản phụ 2')").update();
    }

    @Test
    void khoaRiengTungTaiKhoanVaTaiKhoanMotGiuNguyenKhoaCu() {
        settings.set("zalo.msg.daily", "7", "test");
        ZaloAccountContext.run(2, () -> settings.set("zalo.msg.daily", "4", "test"));
        assertThat(settings.get("zalo.msg.daily")).contains("7");
        assertThat(ZaloAccountContext.call(2, () -> settings.get("zalo.msg.daily"))).contains("4");
        // tài khoản 1 vẫn ghi đúng khóa cũ, tài khoản 2 ghi khóa có tiền tố
        assertThat(jdbc.sql("SELECT value FROM app_setting WHERE key = 'zalo.msg.daily'").query(String.class).single()).isEqualTo("7");
        assertThat(jdbc.sql("SELECT value FROM app_setting WHERE key = 'acct2.zalo.msg.daily'").query(String.class).single()).isEqualTo("4");
    }

    @Test
    void khoaDungChungKhongBiDoiTen() {
        ZaloAccountContext.run(2, () -> settings.pauseAll("thử", "test"));
        assertThat(settings.paused()).isTrue();
        assertThat(ZaloAccountContext.settingKey("campaigns.paused")).isEqualTo("campaigns.paused");
        assertThat(ZaloAccountContext.settingKey("zalo.group.target_customers")).isEqualTo("zalo.group.target_customers");
        assertThat(ZaloAccountContext.call(3, () -> ZaloAccountContext.settingKey("zalo.auto.tripped_at"))).isEqualTo("acct3.zalo.auto.tripped_at");
    }

    @Test
    void nhatKyTachTheoTaiKhoan() {
        secondAccount();
        auto.log(ZaloAutoSettings.A_CONFIG, null, null, "MOT", null, "test");
        ZaloAccountContext.run(2, () -> auto.log(ZaloAutoSettings.A_CONFIG, null, null, "HAI", null, "test"));
        assertThat(auto.recentLog(10)).extracting(ZaloAutoSettings.LogRow::result).containsExactly("MOT");
        assertThat(ZaloAccountContext.call(2, () -> auto.recentLog(10))).extracting(ZaloAutoSettings.LogRow::result).containsExactly("HAI");
    }

    @Test
    void zaloHanCheMotTaiKhoanThiDungChungNhungNguoiBamDungChiDungMotTaiKhoan() {
        secondAccount();
        auto.trip("DUNG_TAY", "test");
        assertThat(auto.tripped()).isTrue();
        assertThat(ZaloAccountContext.call(2, auto::tripped)).isFalse();

        auto.reopen("test");
        auto.trip("RESTRICTED:Z999", "system");
        assertThat(auto.tripped()).isTrue();
        assertThat(ZaloAccountContext.call(2, auto::tripped)).isTrue();
        assertThat(ZaloAccountContext.call(2, auto::trippedReason)).startsWith("TK_KHAC_DUNG:RESTRICTED:Z999");
        // dừng chung KHÔNG lan tiếp (lý do bắt đầu bằng TK_KHAC_DUNG không phải lý do hạn chế)
        assertThat(ZaloAutoSettings.haltsAllAccounts("TK_KHAC_DUNG:RESTRICTED:Z999:1")).isFalse();
        assertThat(ZaloAutoSettings.haltsAllAccounts("SESSION_LOST")).isFalse();
        assertThat(ZaloAutoSettings.haltsAllAccounts("ACCOUNT_MISMATCH")).isFalse();
        assertThat(ZaloAutoSettings.haltsAllAccounts("TIN_INTERRUPTED")).isTrue();
    }

    @Test
    void bridgeAndDeputyWarningsStopAllButLocalBridgeLimitsDoNot() {
        assertThat(ZaloAutoSettings.haltsAllAccounts("BRIDGE_RESTRICTED:Z999")).isTrue();
        assertThat(ZaloAutoSettings.haltsAllAccounts("BRIDGE_SYSTEM_ERROR:NO_ACK")).isTrue();
        assertThat(ZaloAutoSettings.haltsAllAccounts("TRA_SO_ME_RESTRICTED")).isTrue();
        assertThat(ZaloAutoSettings.haltsAllAccounts("RESTRICTED:WINDOW")).isFalse();
        assertThat(ZaloAutoSettings.haltsAllAccounts("RESTRICTED:MSG_LIMIT")).isFalse();
        assertThat(ZaloAutoSettings.haltsAllAccounts("BRIDGE_STOP")).isFalse();
    }

    @Test
    void reopeningOneSourceKeepsAnIndependentSharedHaltActive() {
        secondAccount();
        auto.trip("BRIDGE_RESTRICTED:Z999", "test");
        ZaloAccountContext.run(2, () -> auto.trip("SYSTEM_ERROR:TIMEOUT", "test"));

        auto.reopen("test");
        assertThat(auto.tripped()).isTrue();
        assertThat(auto.sharedHaltSource()).isEqualTo(2L);
        assertThat(ZaloAccountContext.call(2, auto::trippedReason)).isEqualTo("SYSTEM_ERROR:TIMEOUT");

        ZaloAccountContext.run(2, () -> auto.reopen("test"));
        assertThat(auto.tripped()).isFalse();
        assertThat(ZaloAccountContext.call(2, auto::tripped)).isFalse();
    }

    @Test
    void taiKhoanThemMoiBatDauHanMucThapVaTaiKhoanMotKhongDoi() {
        int one = messages.limits().daily();
        assertThat(one).isEqualTo(10);
        assertThat(ZaloAccountContext.call(2, () -> messages.limits().daily())).isEqualTo(ZaloMessageService.NEW_ACCOUNT_START_DAILY);
        assertThat(ZaloAccountContext.call(2, () -> messages.friendDaily())).isLessThanOrEqualTo(ZaloMessageService.NEW_ACCOUNT_START_DAILY);
        ZaloAccountContext.run(2, () -> settings.set("zalo.msg.daily", "9", "test"));
        assertThat(ZaloAccountContext.call(2, () -> messages.limits().daily())).isEqualTo(9);
    }

    @Test
    void tranTongMacDinhBangTranCungMotTaiKhoanVaChinhDuocTrongTranCung() {
        assertThat(messages.totalDaily()).isEqualTo(50);
        assertThat(messages.setTotalDaily(120, "test")).isEqualTo(120);
        assertThat(ZaloAccountContext.call(2, messages::totalDaily)).isEqualTo(120); // khóa dùng chung
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> messages.setTotalDaily(ZaloMessageService.TOTAL_HARD_CAP + 1, "test"))
                .isInstanceOf(vn.shop.crm.common.ImportException.class);
    }

    @Test
    void macDinhChiTaiKhoanMotChayVaTaiKhoanThemKhongBiTuChay() {
        assertThat(registry.multiEnabled()).isFalse();
        assertThat(registry.active()).containsExactly(1L);
    }
}
