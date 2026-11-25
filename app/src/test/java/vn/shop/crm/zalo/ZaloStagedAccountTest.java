package vn.shop.crm.zalo;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestPropertySource;
import vn.shop.crm.AbstractPostgresTest;
import vn.shop.crm.common.ImportException;
import vn.shop.crm.safety.TestMode;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import(ZaloStagedAccountTest.Routes.class)
@TestPropertySource(properties = {
        "app.zalo.multi.enabled=true",
        "app.zalo.accounts[0].id=2",
        "app.zalo.accounts[0].bridge-url=http://secondary.invalid:3100",
        "app.zalo.accounts[0].bridge-key=test"})
class ZaloStagedAccountTest extends AbstractPostgresTest {

    private static final String PRIMARY_TAG = "000000000001";
    private static final String EXTRA_TAG = "000000000002";

    @Autowired ZaloAccountRegistry accounts;
    @Autowired ZaloAccountRoutes routes;
    @Autowired TestMode mode;

    @Test
    void additionalAccountStartsDisabledAndRemainsTestOnlyAfterActivation() {
        bridge(1).status = listening(PRIMARY_TAG);
        bridge(2).status = listening(EXTRA_TAG);

        assertThat(accounts.active()).containsExactly(1L);
        assertThat(accounts.selectable(2)).isTrue();
        accounts.setEnabled(2, true);

        assertThat(accounts.active()).containsExactly(1L, 2L);
        assertThat(mode.live()).isTrue();
        assertThat(ZaloAccountContext.call(2, mode::testOnly)).isTrue();
        assertThat(ZaloAccountContext.call(2, () -> mode.phoneAllowed("0900000012"))).isFalse();
        assertThat(ZaloAccountContext.call(1, () -> mode.phoneAllowed("0900000012"))).isTrue();
        assertThat(jdbc.sql("SELECT account_tag FROM zalo_account ORDER BY id").query(String.class).list())
                .containsExactly(PRIMARY_TAG, EXTRA_TAG);
    }

    @Test
    void rejectsDuplicateAccountAndRefusesToReplaceIdentityAfterPause() {
        bridge(1).status = listening(PRIMARY_TAG);
        bridge(2).status = listening(PRIMARY_TAG);
        assertThatThrownBy(() -> accounts.setEnabled(2, true)).isInstanceOf(ImportException.class)
                .hasMessageContaining("cùng một tài khoản");
        assertThat(accounts.active()).containsExactly(1L);

        bridge(2).status = listening(EXTRA_TAG);
        accounts.setEnabled(2, true);
        accounts.setEnabled(2, false);
        bridge(2).status = listening("000000000003");
        assertThatThrownBy(() -> accounts.setEnabled(2, true)).isInstanceOf(ImportException.class)
                .hasMessageContaining("khác với tài khoản Zalo đã xác nhận");
        assertThat(accounts.active()).containsExactly(1L);
    }

    private FakeZaloGateway bridge(long account) {
        return (FakeZaloGateway) routes.target(account);
    }

    private static ZaloGateway.Status listening(String tag) {
        return new ZaloGateway.Status(ZaloGateway.State.LISTENING, null, true, null, null, null, tag);
    }

    @TestConfiguration
    static class Routes {
        @Bean
        @Primary
        ZaloAccountRoutes stagedRoutes() {
            return new ZaloAccountRoutes(Map.of(1L, new FakeZaloGateway(), 2L, new FakeZaloGateway()));
        }
    }
}
