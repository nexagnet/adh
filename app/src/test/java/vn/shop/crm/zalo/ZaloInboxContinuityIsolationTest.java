package vn.shop.crm.zalo;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import vn.shop.crm.AbstractPostgresTest;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@Import(ZaloTwoBridgeSchedulerTest.Bridges.class)
@TestPropertySource(properties = {
        "app.zalo.auto.enabled=true", "app.zalo.auto.bridge-key=test",
        "app.zalo.auto.initial-delay-millis=3600000",
        "app.zalo.multi.enabled=true", "app.zalo.inbox.enabled=true",
        "app.zalo.accounts[0].id=2", "app.zalo.accounts[0].bridge-url=http://127.0.0.1:1",
        "app.zalo.accounts[0].bridge-key=k2", "app.ai.enabled=true", "app.ai.api-key=test", "app.ai.model=test"})
class ZaloInboxContinuityIsolationTest extends AbstractPostgresTest {
    @Autowired ZaloTwoBridgeSchedulerTest.BridgePair bridges;
    @Autowired ZaloInboxService inbox;
    @Autowired ZaloAutoSettings auto;
    @Autowired ZaloAccountRegistry registry;

    @Test
    void missingProofOnBridgeTwoCannotBlockOrBorrowProofFromBridgeOne() {
        bridges.reset();
        registry.setEnabled(2, true);
        ZaloAccountContext.run(1, () -> auto.setMode(ZaloMode.AUTO, true, "test"));
        ZaloAccountContext.run(2, () -> auto.setMode(ZaloMode.AUTO, true, "test"));
        bridges.second().inboxCoverageSince = null;

        ZaloAccountContext.run(2, () -> assertThat(inbox.catchUpForFollowup()).isEmpty());
        ZaloAccountContext.run(1, () -> assertThat(inbox.catchUpForFollowup()).contains(Instant.EPOCH));
        assertThat(bridges.first().inboxCalls).isEqualTo(1);
        assertThat(bridges.second().inboxCalls).isEqualTo(1);
    }
}
