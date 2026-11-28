package vn.shop.crm.zalo;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import vn.shop.crm.AbstractPostgresTest;
import vn.shop.crm.common.AppSettings;
import vn.shop.crm.common.ImportException;
import vn.shop.crm.zalo.ZaloDeputyService.Status;
import vn.shop.crm.zalo.ZaloPersonFinder.FindResult;
import vn.shop.crm.zalo.ZaloPersonFinder.Found;

import java.time.Duration;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Chọn mẹ làm phó nhóm bằng số điện thoại (D19, bộ nối GIẢ): tra, xác nhận, hạn mức, cầu dao, không lưu số điện thoại. */
@Import(ZaloTestConfig.class)
@TestPropertySource(properties = {
        "app.zalo.auto.enabled=true",
        "app.zalo.auto.bridge-key=test",
        "app.zalo.auto.initial-delay-millis=3600000",
        "app.zalo.auto.tick-millis=3600000",
        "app.zalo.group.deputy-id=99887766"})
class ZaloDeputyTest extends AbstractPostgresTest {

    private static final String AVATAR = "data:image/png;base64,iVBORw0KGgo=";
    private static final Found MOM = new Found(FindResult.OK, null, "4242", "Mẹ Lan", AVATAR, 0);

    @Autowired ZaloDeputyService svc;
    @Autowired ZaloAutoSettings auto;
    @Autowired AppSettings settings;
    @Autowired FakeZaloGateway fake;
    @Autowired ZaloTestClock clock;

    @BeforeEach
    void setUp() {
        fake.reset();
        clock.set(LocalDateTime.of(2026, 11, 23, 10, 0));
    }

    @Test
    void traRoiXacNhanThiLuuMaVaUuTienHonBienMoiTruong() {
        assertThat(svc.effectiveUid()).isEqualTo("99887766"); // chưa chọn: dùng biến môi trường như trước
        assertThat(svc.current().source()).isEqualTo("ENV");
        fake.willFind(MOM);
        var r = svc.lookup("0912 345 678", "admin");
        assertThat(r.status()).isEqualTo(Status.FOUND);
        assertThat(fake.finds).containsExactly("84912345678");
        assertThat(svc.candidate()).isPresent();
        assertThat(svc.candidate().orElseThrow().name()).isEqualTo("Mẹ Lan");
        assertThat(svc.effectiveUid()).isEqualTo("99887766"); // chưa xác nhận thì chưa đổi
        svc.confirm(svc.candidate().orElseThrow().revision(), "admin");
        assertThat(svc.candidate()).isEmpty();
        assertThat(svc.effectiveUid()).isEqualTo("4242");
        var cur = svc.current();
        assertThat(cur.source()).isEqualTo("SETTING");
        assertThat(cur.person().name()).isEqualTo("Mẹ Lan");
        assertThat(cur.person().avatar()).isEqualTo(AVATAR);
        svc.clear("admin");
        assertThat(svc.effectiveUid()).isEqualTo("99887766");
    }

    @Test
    void soDienThoaiKhongDuocLuuVaMaTenKhongVaoNhatKy() {
        fake.willFind(MOM);
        svc.lookup("0912345678", "admin");
        svc.confirm(svc.candidate().orElseThrow().revision(), "admin");
        String settingsDump = String.join("|", jdbc.sql("SELECT key || '=' || value FROM app_setting").query(String.class).list());
        String logDump = String.join("|", jdbc.sql("SELECT action || result || coalesce(code, '') || coalesce(actor, '') FROM zalo_auto_log")
                .query(String.class).list());
        assertThat(settingsDump).doesNotContain("912345678").doesNotContain("84912345678");
        assertThat(logDump).doesNotContain("912345678").doesNotContain("4242").doesNotContain("Mẹ Lan");
        assertThat(logDump).contains(ZaloDeputyService.A_LOOKUP);
    }

    @Test
    void soSaiBiTuChoiKhongGoiBoNoi() {
        for (String bad : new String[]{"", "abc", "123", "0912; DROP", "+1 202 555 0100"}) {
            assertThatThrownBy(() -> svc.lookup(bad, "admin")).isInstanceOf(ImportException.class);
        }
        assertThat(fake.finds).isEmpty();
    }

    @Test
    void tabCuKhongXacNhanNguoiTuLanTraMoi() {
        fake.willFind(MOM);
        svc.lookup("0912345678", "admin");
        String oldRevision = svc.candidate().orElseThrow().revision();
        clock.advance(Duration.ofSeconds(61));
        fake.willFind(new Found(FindResult.OK, null, "4243", "Người thứ hai", AVATAR, 0));
        svc.lookup("0912345679", "admin");

        assertThatThrownBy(() -> svc.confirm(oldRevision, "admin")).isInstanceOf(ImportException.class);
        assertThat(svc.effectiveUid()).isEqualTo("99887766");
        svc.confirm(svc.candidate().orElseThrow().revision(), "admin");
        assertThat(svc.effectiveUid()).isEqualTo("4243");
    }

    @Test
    void khongThayThiBaoRoVaKhongDungCauDao() {
        var r = svc.lookup("0912345678", "admin"); // mặc định của bộ nối giả: không tìm thấy
        assertThat(r.status()).isEqualTo(Status.NOT_FOUND);
        assertThat(r.message()).contains("cho phép tìm bằng số điện thoại");
        assertThat(auto.tripped()).isFalse();
        assertThat(svc.candidate()).isEmpty();
    }

    @Test
    void khongTraDonKhoangNghiCoDinhVaTranMoiNgay() {
        fake.willFind(MOM);
        assertThat(svc.lookup("0912345678", "admin").status()).isEqualTo(Status.FOUND);
        // tra lại ngay: bị từ chối, bộ nối không bị gọi lần hai
        assertThat(svc.lookup("0912345678", "admin").status()).isEqualTo(Status.LIMIT);
        assertThat(fake.finds).hasSize(1);
        clock.advance(Duration.ofSeconds(61));
        assertThat(svc.lookup("0912345678", "admin").status()).isEqualTo(Status.NOT_FOUND);
        assertThat(fake.finds).hasSize(2);
        // tới trần mỗi ngày (tính cả lần không thấy)
        for (int i = 2; i < ZaloDeputyService.DAILY; i++) {
            clock.advance(Duration.ofSeconds(61));
            svc.lookup("0912345678", "admin");
        }
        assertThat(fake.finds).hasSize(ZaloDeputyService.DAILY);
        clock.advance(Duration.ofSeconds(61));
        var over = svc.lookup("0912345678", "admin");
        assertThat(over.status()).isEqualTo(Status.LIMIT);
        assertThat(fake.finds).hasSize(ZaloDeputyService.DAILY);
    }

    @Test
    void loiLaThiDungCauDaoChungConTuChoiTruocKhiChamZaloThiKhong() {
        fake.willFind(Found.of(FindResult.RESTRICTED, "WINDOW"));
        assertThat(svc.lookup("0912345678", "admin").status()).isEqualTo(Status.UNAVAILABLE);
        assertThat(auto.tripped()).isFalse();
        clock.advance(Duration.ofSeconds(61));
        fake.willFind(Found.of(FindResult.UNREACHABLE, "BRIDGE_DOWN"));
        assertThat(svc.lookup("0912345678", "admin").status()).isEqualTo(Status.UNAVAILABLE);
        assertThat(auto.tripped()).isFalse();
        clock.advance(Duration.ofSeconds(61));
        fake.willFind(Found.of(FindResult.SYSTEM_ERROR, "NO_ACK"));
        assertThat(svc.lookup("0912345678", "admin").status()).isEqualTo(Status.STOPPED);
        assertThat(auto.tripped()).isTrue();
        assertThat(fake.stops).isEqualTo(1);
        // đang dừng thì không tra nữa
        clock.advance(Duration.ofSeconds(61));
        int before = fake.finds.size();
        assertThat(svc.lookup("0912345678", "admin").status()).isEqualTo(Status.STOPPED);
        assertThat(fake.finds).hasSize(before);
    }

    @Test
    void ketQuaTraHetHanSauBaMuoiPhutVaXacNhanKhongNhanMaTuNgoai() {
        assertThatThrownBy(() -> svc.confirm("", "admin")).isInstanceOf(ImportException.class); // chưa tra thì không xác nhận được
        fake.willFind(MOM);
        svc.lookup("0912345678", "admin");
        String expired = svc.candidate().orElseThrow().revision();
        clock.advance(Duration.ofMinutes(31));
        assertThat(svc.candidate()).isEmpty();
        assertThatThrownBy(() -> svc.confirm(expired, "admin")).isInstanceOf(ImportException.class);
        assertThat(svc.effectiveUid()).isEqualTo("99887766");
    }

    @Test
    void anhKhongDungDangBiBo() {
        fake.willFind(new Found(FindResult.OK, null, "4242", "Mẹ", "https://evil.example/a.png", 0));
        svc.lookup("0912345678", "admin");
        assertThat(svc.candidate().orElseThrow().avatar()).isNull();
    }
}
