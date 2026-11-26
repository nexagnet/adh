package vn.shop.crm.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import vn.shop.crm.AbstractPostgresTest;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@org.springframework.test.context.TestPropertySource(properties = "APP_ADH_STAGE2_ENABLED=true")
class StaffWorkTest extends AbstractPostgresTest {
    @Autowired WebApplicationContext context;
    @Autowired StaffMembers members;
    @Autowired StaffWork work;

    @Test
    void activeEmployeeCanOnlyClaimAssignedGroupAndLosesAccessWhenDisabled() throws Exception {
        var mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        var owner = user("owner").roles("OWNER");
        var member = user("staff-a").roles("TEAM_MEMBER");
        long group = jdbc.sql("INSERT INTO zalo_group(name) VALUES ('Nhóm được giao') RETURNING id").query(Long.class).single();
        long hidden = jdbc.sql("INSERT INTO zalo_group(name) VALUES ('Nhóm khác') RETURNING id").query(Long.class).single();
        long ownWork = task(group);
        long hiddenWork = task(hidden);
        mvc.perform(post("/staff/members").with(owner).with(csrf())
                .param("username", "staff-a").param("name", "Nhân viên A")
                .param("password", "test-password-123")).andExpect(status().is3xxRedirection());
        mvc.perform(post("/staff/members/staff-a/groups").with(owner).with(csrf())
                .param("groupId", Long.toString(group)).param("allowed", "true")).andExpect(status().is3xxRedirection());
        mvc.perform(get("/staff/members").with(owner)).andExpect(status().isOk());
        mvc.perform(get("/staff/work").with(owner))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl("/handoffs?type=group"));
        mvc.perform(get("/staff/work").with(member)).andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("Nhóm khác"))));
        mvc.perform(get("/customers/999").with(member)).andExpect(status().isForbidden());
        mvc.perform(get("/imports/999/errors.csv").with(member)).andExpect(status().isForbidden());
        mvc.perform(post("/staff/work/{id}/claim", ownWork).with(member)).andExpect(status().isForbidden());
        mvc.perform(post("/staff/work/{id}/claim", hiddenWork).with(member).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(post("/staff/work/{id}/claim", ownWork).with(member).with(csrf())).andExpect(status().is3xxRedirection());
        mvc.perform(post("/staff/members/staff-a/active").with(owner).with(csrf())
                .param("active", "false")).andExpect(status().is3xxRedirection());
        mvc.perform(get("/staff/work").with(member)).andExpect(status().isForbidden());
    }

    @Test
    void concurrentClaimsCreateOnlyOneAssignmentEvent() throws Exception {
        members.add("staff-one", "Một", "test-password-123");
        members.add("staff-two", "Hai", "test-password-123");
        long group = jdbc.sql("INSERT INTO zalo_group(name) VALUES ('Nhóm thử') RETURNING id").query(Long.class).single();
        members.grant("staff-one", group, true);
        members.grant("staff-two", group, true);
        long id = task(group);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> attempt(start, id, "staff-one"));
            var second = pool.submit(() -> attempt(start, id, "staff-two"));
            start.countDown();
            assertThat(java.util.List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }
        assertThat(jdbc.sql("SELECT count(*) FROM handoff_assignment_event WHERE handoff_id = :id")
                .param("id", id).query(Long.class).single()).isEqualTo(1);
    }

    private boolean attempt(CountDownLatch start, long id, String user) throws InterruptedException {
        start.await();
        try {
            work.claim(id, user);
            return true;
        } catch (org.springframework.security.access.AccessDeniedException e) {
            return false;
        }
    }

    private long task(long group) {
        return jdbc.sql("INSERT INTO handoff (group_id, reason, excerpt) VALUES (:g, 'ORDER', '') RETURNING id")
                .param("g", group).query(Long.class).single();
    }
}
