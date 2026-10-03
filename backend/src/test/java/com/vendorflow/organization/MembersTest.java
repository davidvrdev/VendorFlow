package com.vendorflow.organization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vendorflow.support.ApiClient;
import com.vendorflow.support.IntegrationTest;
import com.vendorflow.support.TestAccounts;
import com.vendorflow.support.TestAccounts.Account;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class MembersTest extends IntegrationTest {

    static final String MEMBERS = "/api/v1/organization/members";

    @Autowired MockMvc mvc;
    @Autowired JsonMapper json;
    @Autowired JdbcTemplate jdbc;

    TestAccounts accounts;
    String orgId;
    Account owner;
    Account admin;
    Account member;
    Account viewer;

    @BeforeEach
    void setUp() throws Exception {
        accounts = new TestAccounts(mvc, json, jdbc);
        owner = accounts.verified(accounts.signup(TestAccounts.uniqueEmail(), TestAccounts.PASSWORD, "Dora Owner",
                "Members Org " + UUID.randomUUID()));
        orgId = owner.organizationId();
        admin = accounts.memberOf(orgId, "ADMIN", "Adam Admin");
        member = accounts.memberOf(orgId, "MEMBER", "Mia Member");
        viewer = accounts.memberOf(orgId, "VIEWER", "Vic Viewer");
    }

    private String mid(Account user) {
        return accounts.membershipId(orgId, user.userId());
    }

    private String roleOf(Account user) {
        return jdbc.queryForObject("select role from membership where organization_id = ?::uuid and user_id = ?::uuid",
                String.class, orgId, user.userId());
    }

    private boolean isMember(Account user) {
        return jdbc.queryForObject("select count(*) from membership where organization_id = ?::uuid and user_id = ?::uuid",
                Integer.class, orgId, user.userId()) > 0;
    }

    private int patch(Account actor, Account target, String role) throws Exception {
        return actor.client().patch(MEMBERS + "/" + mid(target), Map.of("role", role)).andReturn().getResponse()
                .getStatus();
    }

    private int delete(Account actor, Account target) throws Exception {
        return actor.client().delete(MEMBERS + "/" + mid(target)).andReturn().getResponse().getStatus();
    }

    // ---- list ----

    @Test
    void listReturnsThePageEnvelopeSortedByNameForEveryRole() throws Exception {
        for (Account viewerOfList : List.of(owner, admin, member, viewer)) {
            viewerOfList.client().get(MEMBERS)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.page").value(0))
                    .andExpect(jsonPath("$.size").value(50))
                    .andExpect(jsonPath("$.totalItems").value(4))
                    .andExpect(jsonPath("$.totalPages").value(1))
                    .andExpect(jsonPath("$.items[0].fullName").value("Adam Admin"))
                    .andExpect(jsonPath("$.items[1].fullName").value("Dora Owner"))
                    .andExpect(jsonPath("$.items[2].fullName").value("Mia Member"))
                    .andExpect(jsonPath("$.items[3].fullName").value("Vic Viewer"));
        }
    }

    @Test
    void listItemHasTheMemberShapeAndNoSecrets() throws Exception {
        String body = owner.client().get(MEMBERS).andReturn().getResponse().getContentAsString();
        JsonNode first = json.readTree(body).get("items").get(0);
        assertThat(first.propertyNames()).containsExactlyInAnyOrder("membershipId", "userId", "fullName", "email",
                "role", "joinedAt");
        assertThat(first.get("membershipId").asString()).isEqualTo(mid(admin));
        assertThat(first.get("userId").asString()).isEqualTo(admin.userId());
        assertThat(first.get("email").asString()).isEqualTo(admin.email());
        assertThat(first.get("role").asString()).isEqualTo("ADMIN");
        assertThat(body).doesNotContain("password");
    }

    @Test
    void listPagesAndCapsTheSizeAt100() throws Exception {
        owner.client().get(MEMBERS + "?page=1&size=3")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(3))
                .andExpect(jsonPath("$.totalItems").value(4))
                .andExpect(jsonPath("$.totalPages").value(2));
        owner.client().get(MEMBERS + "?size=100000").andExpect(jsonPath("$.size").value(100));
    }

    @Test
    void listOnlyShowsTheActiveOrganization() throws Exception {
        Account stranger = accounts.signup("Stranger Org");
        stranger.client().get(MEMBERS).andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].userId").value(stranger.userId()));
    }

    // ---- change role ----

    @Test
    void ownerCanSetAnyRoleAndTheResponseIsTheUpdatedMember() throws Exception {
        owner.client().patch(MEMBERS + "/" + mid(member), Map.of("role", "ADMIN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.membershipId").value(mid(member)))
                .andExpect(jsonPath("$.role").value("ADMIN"))
                .andExpect(jsonPath("$.fullName").value("Mia Member"));
        assertThat(patch(owner, viewer, "OWNER")).isEqualTo(200);
        assertThat(patch(owner, admin, "VIEWER")).isEqualTo(200);
        assertThat(roleOf(member)).isEqualTo("ADMIN");
        assertThat(roleOf(viewer)).isEqualTo("OWNER");
        assertThat(roleOf(admin)).isEqualTo("VIEWER");
    }

    @Test
    void adminCanOnlyMoveMembersBetweenMemberAndViewer() throws Exception {
        assertThat(patch(admin, member, "VIEWER")).isEqualTo(200);
        assertThat(patch(admin, viewer, "MEMBER")).isEqualTo(200);
        assertThat(roleOf(member)).isEqualTo("VIEWER");
        assertThat(roleOf(viewer)).isEqualTo("MEMBER");
    }

    @Test
    void adminCannotGrantAdminOrOwner() throws Exception {
        assertThat(patch(admin, member, "ADMIN")).isEqualTo(403);
        assertThat(patch(admin, member, "OWNER")).isEqualTo(403);
        assertThat(roleOf(member)).isEqualTo("MEMBER");
    }

    @Test
    void adminCannotTouchAnotherAdminOrAnOwner() throws Exception {
        Account otherAdmin = accounts.memberOf(orgId, "ADMIN", "Anna Admin");
        assertThat(patch(admin, otherAdmin, "MEMBER")).isEqualTo(403);
        assertThat(patch(admin, owner, "MEMBER")).isEqualTo(403);
        assertThat(delete(admin, otherAdmin)).isEqualTo(403);
        assertThat(delete(admin, owner)).isEqualTo(403);
        assertThat(roleOf(otherAdmin)).isEqualTo("ADMIN");
        assertThat(roleOf(owner)).isEqualTo("OWNER");
        assertThat(isMember(otherAdmin)).isTrue();
        assertThat(isMember(owner)).isTrue();
    }

    @Test
    void memberAndViewerCannotChangeRoles() throws Exception {
        for (Account actor : List.of(member, viewer)) {
            assertThat(patch(actor, viewer, "MEMBER")).isEqualTo(403);
            assertThat(patch(actor, admin, "VIEWER")).isEqualTo(403);
        }
        assertThat(roleOf(viewer)).isEqualTo("VIEWER");
        assertThat(roleOf(admin)).isEqualTo("ADMIN");
    }

    @Test
    void nobodyCanChangeTheirOwnRole() throws Exception {
        Account secondOwner = accounts.memberOf(orgId, "OWNER", "Olga Owner");
        assertThat(patch(owner, owner, "ADMIN")).isEqualTo(403);
        assertThat(patch(secondOwner, secondOwner, "MEMBER")).isEqualTo(403);
        assertThat(patch(admin, admin, "MEMBER")).isEqualTo(403);
        assertThat(patch(member, member, "ADMIN")).isEqualTo(403);
        assertThat(roleOf(owner)).isEqualTo("OWNER");
        assertThat(roleOf(admin)).isEqualTo("ADMIN");
    }

    @Test
    void anOwnerCanDemoteAnotherOwnerWhileOneRemains() throws Exception {
        Account secondOwner = accounts.memberOf(orgId, "OWNER", "Olga Owner");
        assertThat(patch(owner, secondOwner, "ADMIN")).isEqualTo(200);
        assertThat(roleOf(secondOwner)).isEqualTo("ADMIN");
    }

    @Test
    void invalidOrMissingRoleIsAValidationError() throws Exception {
        owner.client().patch(MEMBERS + "/" + mid(member), Map.of("role", "SUPERUSER")).andExpect(status().isBadRequest());
        owner.client().patch(MEMBERS + "/" + mid(member), Map.of()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("role"));
        assertThat(roleOf(member)).isEqualTo("MEMBER");
    }

    @Test
    void unknownMembershipIdIs404() throws Exception {
        owner.client().patch(MEMBERS + "/" + UUID.randomUUID(), Map.of("role", "VIEWER")).andExpect(status().isNotFound());
        owner.client().delete(MEMBERS + "/" + UUID.randomUUID()).andExpect(status().isNotFound());
    }

    @Test
    void roleChangeTakesEffectOnTheTargetsNextRequest() throws Exception {
        member.client().get("/api/v1/me").andExpect(jsonPath("$.activeOrganization.role").value("MEMBER"));
        assertThat(patch(owner, member, "VIEWER")).isEqualTo(200);
        member.client().get("/api/v1/me").andExpect(jsonPath("$.activeOrganization.role").value("VIEWER"));
    }

    // ---- remove / leave ----

    @Test
    void ownerAndAdminCanRemoveMembersAndViewers() throws Exception {
        assertThat(delete(owner, member)).isEqualTo(204);
        assertThat(delete(admin, viewer)).isEqualTo(204);
        assertThat(isMember(member)).isFalse();
        assertThat(isMember(viewer)).isFalse();
    }

    @Test
    void ownerCanRemoveAnAdminOrAnotherOwner() throws Exception {
        Account secondOwner = accounts.memberOf(orgId, "OWNER", "Olga Owner");
        assertThat(delete(owner, admin)).isEqualTo(204);
        assertThat(delete(owner, secondOwner)).isEqualTo(204);
        assertThat(isMember(admin)).isFalse();
        assertThat(isMember(secondOwner)).isFalse();
    }

    @Test
    void memberAndViewerCannotRemoveOthers() throws Exception {
        assertThat(delete(member, viewer)).isEqualTo(403);
        assertThat(delete(viewer, member)).isEqualTo(403);
        assertThat(delete(member, owner)).isEqualTo(403);
        assertThat(isMember(viewer)).isTrue();
        assertThat(isMember(member)).isTrue();
        assertThat(isMember(owner)).isTrue();
    }

    @Test
    void anyoneCanLeaveAndLosesTheTenantContext() throws Exception {
        for (Account leaver : List.of(viewer, member, admin)) {
            assertThat(delete(leaver, leaver)).isEqualTo(204);
            assertThat(isMember(leaver)).isFalse();
            // The old session still works but has no active organization any more (their own org stays available).
            leaver.client().get(MEMBERS).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.title").value("No active organization"));
            assertThat(jdbc.queryForObject("select last_active_organization_id from app_user where id = ?::uuid",
                    UUID.class, leaver.userId())).isNull();
        }
    }

    @Test
    void leavingKeepsALastActiveOrganizationThatIsSomewhereElse() throws Exception {
        // member's last active org is orgId (joinOrganization switched to it); point it at their home org instead.
        String home = member.organizationId();
        jdbc.update("update app_user set last_active_organization_id = ?::uuid where id = ?::uuid", home,
                member.userId());
        assertThat(delete(member, member)).isEqualTo(204);
        assertThat(jdbc.queryForObject("select last_active_organization_id::text from app_user where id = ?::uuid",
                String.class, member.userId())).isEqualTo(home);
    }

    @Test
    void theLastOwnerCannotLeaveOrBeRemoved() throws Exception {
        owner.client().delete(MEMBERS + "/" + mid(owner))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Last owner"));
        assertThat(isMember(owner)).isTrue();
    }

    @Test
    void anOwnerCanLeaveWhenAnotherOwnerRemainsButThenTheRemainingOneCannot() throws Exception {
        Account secondOwner = accounts.memberOf(orgId, "OWNER", "Olga Owner");
        assertThat(delete(owner, owner)).isEqualTo(204);
        secondOwner.client().delete(MEMBERS + "/" + mid(secondOwner))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Last owner"));
        assertThat(roleOf(secondOwner)).isEqualTo("OWNER");
    }

    // ---- audit ----

    @Test
    void roleChangeRemovalAndLeavingAreAudited() throws Exception {
        patch(owner, member, "VIEWER");
        delete(owner, viewer);
        delete(admin, admin);

        Map<String, Object> changed = jdbc.queryForMap("""
                select actor_user_id::text actor, metadata->>'before' b, metadata->>'after' a, entity_id::text e
                from audit_event where organization_id = ?::uuid and action = 'membership.role_changed'""", orgId);
        assertThat(changed).containsEntry("actor", owner.userId()).containsEntry("b", "MEMBER")
                .containsEntry("a", "VIEWER").containsEntry("e", mid(member));
        assertThat(jdbc.queryForObject("select count(*) from audit_event where organization_id = ?::uuid "
                + "and action = 'membership.removed' and actor_user_id = ?::uuid", Integer.class, orgId,
                owner.userId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from audit_event where organization_id = ?::uuid "
                + "and action = 'membership.left' and actor_user_id = ?::uuid", Integer.class, orgId,
                admin.userId())).isEqualTo(1);
    }

    // ---- tenant isolation ----

    @Test
    void adminOfAnotherOrganizationGets404AndNothingChanges() throws Exception {
        Account foreignOwner = accounts.signup("Foreign Org");
        Account foreignAdmin = accounts.memberOf(foreignOwner.organizationId(), "ADMIN", "Frank Foreign");
        String victimMembership = mid(member);
        String ownerMembership = mid(owner);

        foreignAdmin.client().patch(MEMBERS + "/" + victimMembership, Map.of("role", "VIEWER"))
                .andExpect(status().isNotFound());
        foreignAdmin.client().patch(MEMBERS + "/" + ownerMembership, Map.of("role", "VIEWER"))
                .andExpect(status().isNotFound());
        foreignAdmin.client().delete(MEMBERS + "/" + victimMembership).andExpect(status().isNotFound());
        foreignOwner.client().delete(MEMBERS + "/" + ownerMembership).andExpect(status().isNotFound());

        assertThat(roleOf(member)).isEqualTo("MEMBER");
        assertThat(roleOf(owner)).isEqualTo("OWNER");
        assertThat(isMember(member)).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from membership where organization_id = ?::uuid",
                Integer.class, orgId)).isEqualTo(4);
        // A foreign MEMBER probing is also 404, not 403: the id does not exist from their point of view.
        Account foreignMember = accounts.memberOf(foreignOwner.organizationId(), "MEMBER", "Fiona Foreign");
        foreignMember.client().delete(MEMBERS + "/" + victimMembership).andExpect(status().isNotFound());
    }

    // ---- concurrency ----

    private List<Integer> runConcurrently(List<Callable<Integer>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        try {
            CountDownLatch ready = new CountDownLatch(tasks.size());
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Integer>> futures = new ArrayList<>();
            for (Callable<Integer> task : tasks) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return task.call();
                }));
            }
            ready.await();
            go.countDown();
            List<Integer> results = new ArrayList<>();
            for (Future<Integer> f : futures) {
                results.add(f.get());
            }
            return results;
        } finally {
            pool.shutdown();
        }
    }


    @Test
    void twoOwnersDemotingEachOtherConcurrentlyLeaveAtLeastOneOwner() throws Exception {
        for (int round = 0; round < 6; round++) {
            Account a = accounts.signup("Race Org " + UUID.randomUUID());
            String race = a.organizationId();
            Account b = accounts.memberOf(race, "OWNER", "Bea Owner");
            String aMembership = accounts.membershipId(race, a.userId());
            String bMembership = accounts.membershipId(race, b.userId());

            List<Integer> results = runConcurrently(List.of(
                    () -> a.client().patch(MEMBERS + "/" + bMembership, Map.of("role", "ADMIN")).andReturn()
                            .getResponse().getStatus(),
                    () -> b.client().patch(MEMBERS + "/" + aMembership, Map.of("role", "ADMIN")).andReturn()
                            .getResponse().getStatus()));

            int owners = jdbc.queryForObject("select count(*) from membership where organization_id = ?::uuid "
                    + "and role = 'OWNER'", Integer.class, race);
            assertThat(owners).as("round %d results %s", round, results).isGreaterThanOrEqualTo(1);
            assertThat(results).allMatch(s -> s == 200 || s == 403 || s == 409);
            assertThat(results.stream().filter(s -> s == 200).count()).isEqualTo(1);
        }
    }

    @Test
    void twoOwnersLeavingConcurrentlyLeaveExactlyOneOwner() throws Exception {
        for (int round = 0; round < 6; round++) {
            Account a = accounts.signup("Leave Race Org " + UUID.randomUUID());
            String race = a.organizationId();
            Account b = accounts.memberOf(race, "OWNER", "Bea Owner");
            String aMembership = accounts.membershipId(race, a.userId());
            String bMembership = accounts.membershipId(race, b.userId());

            List<Integer> results = runConcurrently(List.of(
                    () -> a.client().delete(MEMBERS + "/" + aMembership).andReturn().getResponse().getStatus(),
                    () -> b.client().delete(MEMBERS + "/" + bMembership).andReturn().getResponse().getStatus()));

            assertThat(results).as("round %d", round).containsExactlyInAnyOrder(204, 409);
            assertThat(jdbc.queryForObject("select count(*) from membership where organization_id = ?::uuid "
                    + "and role = 'OWNER'", Integer.class, race)).isEqualTo(1);
        }
    }

    @Test
    void anOwnerDemotingTheOtherWhileTheOtherRemovesThemCannotEmptyTheOrganization() throws Exception {
        for (int round = 0; round < 4; round++) {
            Account a = accounts.signup("Mixed Race Org " + UUID.randomUUID());
            String race = a.organizationId();
            Account b = accounts.memberOf(race, "OWNER", "Bea Owner");
            String aMembership = accounts.membershipId(race, a.userId());
            String bMembership = accounts.membershipId(race, b.userId());

            runConcurrently(List.of(
                    () -> a.client().delete(MEMBERS + "/" + bMembership).andReturn().getResponse().getStatus(),
                    () -> b.client().patch(MEMBERS + "/" + aMembership, Map.of("role", "VIEWER")).andReturn()
                            .getResponse().getStatus()));

            assertThat(jdbc.queryForObject("select count(*) from membership where organization_id = ?::uuid "
                    + "and role = 'OWNER'", Integer.class, race)).isGreaterThanOrEqualTo(1);
        }
    }

    @Test
    void unauthenticatedAccessIs401() throws Exception {
        ApiClient anonymous = accounts.newClient();
        anonymous.get(MEMBERS).andExpect(status().isUnauthorized());
        anonymous.patch(MEMBERS + "/" + mid(member), Map.of("role", "VIEWER")).andExpect(status().isUnauthorized());
        anonymous.delete(MEMBERS + "/" + mid(member)).andExpect(status().isUnauthorized());
    }
}
