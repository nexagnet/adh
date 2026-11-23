package vn.shop.crm.zalo;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import vn.shop.crm.AbstractPostgresTest;
import vn.shop.crm.safety.TestMode;
import vn.shop.crm.zalo.ZaloMessenger.Kind;
import vn.shop.crm.zalo.ZaloMessenger.Result;

import static org.assertj.core.api.Assertions.assertThat;

/** Chạy thật ({@code app.zalo.safety.live=true}, như các test cũ): lưới an toàn chỉ chuyển tiếp, không hạn chế gì. */
@Import(ZaloTestConfig.class)
@TestPropertySource(properties = {
        "app.zalo.auto.enabled=true",
        "app.zalo.auto.bridge-key=test",
        "app.zalo.auto.initial-delay-millis=3600000",
        "app.zalo.auto.tick-millis=3600000"})
class TestModeLiveTest extends AbstractPostgresTest {

    @Autowired TestMode mode;
    @Autowired ZaloLink link;
    @Autowired FakeZaloGateway fake;

    @BeforeEach
    void setUp() {
        fake.reset();
    }

    @Test
    void chayThatKhongHanCheAiVaKhongLocSql() {
        assertThat(mode.live()).isTrue();
        assertThat(mode.testOnly()).isFalse();
        assertThat(mode.onlyTestSql("c.phone")).isEmpty();
        assertThat(mode.onlyTestCustomerSql("k.customer_id")).isEmpty();
        assertThat(mode.phoneAllowed("0911111111")).isTrue();
        assertThat(mode.uidAllowed("bất kỳ")).isTrue();
        assertThat(mode.protectedCustomers()).isZero();
        assertThat(link.sendByPhone(Kind.INVITE, "84911111111", "x").result()).isEqualTo(Result.OK);
        assertThat(link.addToGroup("U1", "1001").outcome()).isEqualTo(ZaloGateway.Outcome.OK);
        fake.pushInbound("U1", "alo");
        assertThat(link.inbound(0)).hasSize(1);
    }
}
