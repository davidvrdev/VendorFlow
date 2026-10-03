package com.vendorflow.organization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vendorflow.notification.EmailMessage;
import com.vendorflow.notification.NotificationKind;
import com.vendorflow.notification.OutboxDispatcher;
import com.vendorflow.support.ApiClient;
import com.vendorflow.support.EmailTokens;
import com.vendorflow.support.IntegrationTest;
import com.vendorflow.support.TestAccounts;
import com.vendorflow.support.TestAccounts.Account;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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

class InvitationsTest extends IntegrationTest {

    static final String INVITATIONS = "/api/v1/organization/invitations";
    static final String LOOKUP = "/api/v1/invitations/lookup";
    static final String ACCEPT = "/api/v1/invitations/accept";
    static final String NEW_PASSWORD = "Invited-Person-Pass-7";

    @Autowired MockMvc mvc;
    @Autowired JsonMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired OutboxDispatcher dispatcher;

    TestAccounts accounts;
    String orgName;
    Account owner;
    String orgId;

    @BeforeEach
    void setUp() throws Exception {
        accounts = new TestAccounts(mvc, json, jdbc);
        orgName = "Invite Org " + UUID.randomUUID();
        owner = accounts.verified(accounts.signup(TestAccounts.uniqueEmail(), TestAccounts.PASSWORD, "Olive Owner",
                orgName));
        orgId = owner.organizationId();
    }

    private JsonNode invite(Account actor, String email, String role, int expectedStatus) throws Exception {
        String body = actor.client().post(INVITATIONS, Map.of("email", email, "role", role))
                .andExpect(status().is(expectedStatus)).andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    /** Tests that move the clock by more than the 7-day absolute session lifetime must log in again. */
    private void relogin(Account account) throws Exception {
        account.client().post("/api/v1/auth/login", Map.of("email", account.email(), "password", account.password()))
                .andExpect(status().isOk());
    }

    /** Invites and returns the raw token the way the invitee gets it: from the delivered email. */
    private String inviteAndGetToken(String email, String role) throws Exception {
        invite(owner, email, role, 201);
        return EmailTokens.token(dispatcher, emailSender, email, NotificationKind.INVITATION);
    }

    private String invitationId(String email) {
        return jdbc.queryForObject("select id::text from invitation where organization_id = ?::uuid "
                + "and lower(email) = lower(?) order by created_at desc limit 1", String.class, orgId, email);
    }

    private boolean isMember(String userId) {
        return jdbc.queryForObject("select count(*) from membership where organization_id = ?::uuid and user_id = ?::uuid",
                Integer.class, orgId, userId) > 0;
    }

    private int membershipCount(String email) {
        return jdbc.queryForObject("""
                select count(*) from membership m join app_user u on u.id = m.user_id
                where m.organization_id = ?::uuid and lower(u.email) = lower(?)""", Integer.class, orgId, email);
    }

    // ---- create ----

    @Test
    void createReturnsTheInvitationShapeAndEnqueuesAnEmailWithAFragmentLink() throws Exception {
        String email = TestAccounts.uniqueEmail();
        Instant before = Instant.now();
        String raw = invite(owner, email, "MEMBER", 201).toString();
        JsonNode created = json.readTree(raw);

        assertThat(created.propertyNames()).containsExactlyInAnyOrder("id", "email", "role", "expiresAt",
                "createdAt", "invitedBy");
        assertThat(created.get("email").asString()).isEqualTo(email);
        assertThat(created.get("role").asString()).isEqualTo("MEMBER");
        assertThat(created.get("invitedBy").get("fullName").asString()).isEqualTo("Olive Owner");
        assertThat(Instant.parse(created.get("expiresAt").asString())).isBetween(before.plus(Duration.ofDays(7))
                .minusSeconds(60), before.plus(Duration.ofDays(7)).plusSeconds(60));

        EmailMessage mail = EmailTokens.latest(dispatcher, emailSender, email, NotificationKind.INVITATION);
        assertThat(mail.textBody()).contains("http://localhost:3000/invite#token=").contains(orgName)
                .contains("Olive Owner");
        assertThat(mail.subject()).contains("Olive Owner").contains(orgName);
        String token = EmailTokens.tokenOf(mail);
        // Only the hash is stored, never the token; the token never appears in API responses.
        assertThat(jdbc.queryForObject("select count(*) from invitation where token_hash = ?", Integer.class, token))
                .isZero();
        assertThat(raw).doesNotContain(token);
        // And the delivered notification no longer holds the raw token.
        assertThat(jdbc.queryForObject("select payload::text from notification where recipient_email = ? "
                + "and kind = 'INVITATION'", String.class, email)).doesNotContain(token);
    }

    @Test
    void emailContentIsHtmlEscapedAndHeaderSafe() throws Exception {
        Account evil = accounts.verified(accounts.signup(TestAccounts.uniqueEmail(), TestAccounts.PASSWORD,
                "Eve <img src=x onerror=alert(1)>", "Acme <script>alert(1)</script> Org"));
        // The API now rejects control characters in names (@PlainText), so simulate legacy/bad data directly in the
        // database to keep proving the email layer is header-safe on its own (defense in depth).
        jdbc.update("update app_user set full_name = ? where id = ?::uuid",
                "Eve <img src=x onerror=alert(1)>\r\nBcc: attacker@example.com", evil.userId());
        String email = TestAccounts.uniqueEmail();
        invite(evil, email, "VIEWER", 201);

        EmailMessage mail = EmailTokens.latest(dispatcher, emailSender, email, NotificationKind.INVITATION);
        assertThat(mail.htmlBody()).doesNotContain("<script>").doesNotContain("<img").contains("&lt;script&gt;")
                .contains("&lt;img");
        assertThat(mail.subject()).doesNotContain("\r").doesNotContain("\n");
        assertThat(mail.textBody()).doesNotContain("\r\nBcc:");
    }

    @Test
    void anUnverifiedInviterGets422() throws Exception {
        Account unverified = accounts.signup("Unverified Org");
        unverified.client().post(INVITATIONS, Map.of("email", TestAccounts.uniqueEmail(), "role", "MEMBER"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.title").value("Email not verified"));
    }

    @Test
    void onlyAnOwnerCanInviteAnAdmin() throws Exception {
        Account admin = accounts.verified(accounts.memberOf(orgId, "ADMIN", "Adam Admin"));
        admin.client().post(INVITATIONS, Map.of("email", TestAccounts.uniqueEmail(), "role", "ADMIN"))
                .andExpect(status().isForbidden());
        invite(admin, TestAccounts.uniqueEmail(), "MEMBER", 201);
        invite(admin, TestAccounts.uniqueEmail(), "VIEWER", 201);
        invite(owner, TestAccounts.uniqueEmail(), "ADMIN", 201);
    }

    @Test
    void ownerRoleCannotBeInvitedAndBadInputIsAValidationError() throws Exception {
        owner.client().post(INVITATIONS, Map.of("email", TestAccounts.uniqueEmail(), "role", "OWNER"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("role"));
        owner.client().post(INVITATIONS, Map.of("email", "not-an-email", "role", "MEMBER"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("email"));
        owner.client().post(INVITATIONS, Map.of("email", TestAccounts.uniqueEmail(), "role", "GOD"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void invitingTheSamePendingEmailTwiceIs409EvenInAnotherCase() throws Exception {
        String email = TestAccounts.uniqueEmail();
        invite(owner, email, "MEMBER", 201);
        owner.client().post(INVITATIONS, Map.of("email", email.toUpperCase(), "role", "VIEWER"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.title").value("Invitation already pending"));
        assertThat(jdbc.queryForObject("select count(*) from invitation where organization_id = ?::uuid",
                Integer.class, orgId)).isEqualTo(1);
    }

    @Test
    void invitingAnExistingMemberIs409() throws Exception {
        Account member = accounts.memberOf(orgId, "VIEWER", "Vic Viewer");
        owner.client().post(INVITATIONS, Map.of("email", member.email(), "role", "MEMBER"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.title").value("Already a member"));
        owner.client().post(INVITATIONS, Map.of("email", member.email().toUpperCase(), "role", "MEMBER"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.title").value("Already a member"));
        owner.client().post(INVITATIONS, Map.of("email", owner.email(), "role", "MEMBER"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.title").value("Already a member"));
    }

    @Test
    void anExpiredInvitationCanBeReplacedByANewOne() throws Exception {
        String email = TestAccounts.uniqueEmail();
        String oldToken = inviteAndGetToken(email, "MEMBER");
        clock.advance(Duration.ofDays(8));
        relogin(owner);

        String newToken = inviteAndGetToken(email, "VIEWER");

        assertThat(newToken).isNotEqualTo(oldToken);
        accounts.newClient().post(LOOKUP, Map.of("token", oldToken)).andExpect(status().isNotFound());
        accounts.newClient().post(LOOKUP, Map.of("token", newToken)).andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("VIEWER"));
    }

    @Test
    void reInvitingAfterRevokeWorks() throws Exception {
        String email = TestAccounts.uniqueEmail();
        invite(owner, email, "MEMBER", 201);
        owner.client().delete(INVITATIONS + "/" + invitationId(email)).andExpect(status().isNoContent());
        invite(owner, email, "MEMBER", 201);
    }

    @Test
    void creationIsAudited() throws Exception {
        String email = TestAccounts.uniqueEmail();
        invite(owner, email, "MEMBER", 201);
        assertThat(jdbc.queryForObject("select count(*) from audit_event where organization_id = ?::uuid "
                + "and action = 'invitation.created' and actor_user_id = ?::uuid and entity_id = ?::uuid",
                Integer.class, orgId, owner.userId(), invitationId(email))).isEqualTo(1);
    }

    // ---- list ----

    @Test
    void listShowsOnlyPendingInvitationsNewestFirstWithTheInviterName() throws Exception {
        String pending1 = TestAccounts.uniqueEmail();
        String pending2 = TestAccounts.uniqueEmail();
        String revoked = TestAccounts.uniqueEmail();
        String accepted = TestAccounts.uniqueEmail();
        String expired = TestAccounts.uniqueEmail();

        invite(owner, expired, "MEMBER", 201);
        clock.advance(Duration.ofDays(8));
        relogin(owner);
        invite(owner, pending1, "MEMBER", 201);
        invite(owner, revoked, "VIEWER", 201);
        owner.client().delete(INVITATIONS + "/" + invitationId(revoked)).andExpect(status().isNoContent());
        String acceptedToken = inviteAndGetToken(accepted, "MEMBER");
        accounts.newClient().post(ACCEPT, Map.of("token", acceptedToken, "fullName", "Al Accepted",
                "password", NEW_PASSWORD)).andExpect(status().isOk());
        invite(owner, pending2, "ADMIN", 201);

        String body = owner.client().get(INVITATIONS).andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0)).andExpect(jsonPath("$.size").value(50))
                .andExpect(jsonPath("$.totalItems").value(2)).andExpect(jsonPath("$.totalPages").value(1))
                .andReturn().getResponse().getContentAsString();
        JsonNode items = json.readTree(body).get("items");
        assertThat(items).hasSize(2);
        assertThat(items.get(0).get("email").asString()).isEqualTo(pending2);
        assertThat(items.get(1).get("email").asString()).isEqualTo(pending1);
        assertThat(items.get(0).get("invitedBy").get("fullName").asString()).isEqualTo("Olive Owner");
        assertThat(body).doesNotContain("token");
    }

    @Test
    void listIsPaged() throws Exception {
        for (int i = 0; i < 3; i++) {
            invite(owner, TestAccounts.uniqueEmail(), "VIEWER", 201);
        }
        owner.client().get(INVITATIONS + "?size=2&page=1").andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.totalItems").value(3)).andExpect(jsonPath("$.totalPages").value(2));
    }

    // ---- roles ----

    @Test
    void memberAndViewerCannotCreateListOrRevoke() throws Exception {
        String email = TestAccounts.uniqueEmail();
        invite(owner, email, "MEMBER", 201);
        String id = invitationId(email);
        for (String role : List.of("MEMBER", "VIEWER")) {
            Account low = accounts.verified(accounts.memberOf(orgId, role, "Low " + role));
            low.client().get(INVITATIONS).andExpect(status().isForbidden());
            low.client().post(INVITATIONS, Map.of("email", TestAccounts.uniqueEmail(), "role", "VIEWER"))
                    .andExpect(status().isForbidden());
            low.client().delete(INVITATIONS + "/" + id).andExpect(status().isForbidden());
        }
        assertThat(jdbc.queryForObject("select revoked_at is null from invitation where id = ?::uuid", Boolean.class,
                id)).isTrue();
    }

    @Test
    void unauthenticatedManagementIs401() throws Exception {
        ApiClient anonymous = accounts.newClient();
        anonymous.get(INVITATIONS).andExpect(status().isUnauthorized());
        anonymous.post(INVITATIONS, Map.of("email", TestAccounts.uniqueEmail(), "role", "VIEWER"))
                .andExpect(status().isUnauthorized());
        anonymous.delete(INVITATIONS + "/" + UUID.randomUUID()).andExpect(status().isUnauthorized());
    }

    // ---- revoke & tenant isolation ----

    @Test
    void revokeMarksTheInvitationAndKillsTheLink() throws Exception {
        String email = TestAccounts.uniqueEmail();
        String token = inviteAndGetToken(email, "MEMBER");
        String id = invitationId(email);

        owner.client().delete(INVITATIONS + "/" + id).andExpect(status().isNoContent());

        accounts.newClient().post(LOOKUP, Map.of("token", token)).andExpect(status().isNotFound());
        accounts.newClient().post(ACCEPT, Map.of("token", token, "fullName", "X", "password", NEW_PASSWORD))
                .andExpect(status().isNotFound());
        owner.client().delete(INVITATIONS + "/" + id).andExpect(status().isNotFound()); // already revoked
        owner.client().get(INVITATIONS).andExpect(jsonPath("$.totalItems").value(0));
        assertThat(jdbc.queryForObject("select count(*) from audit_event where action = 'invitation.revoked' "
                + "and entity_id = ?::uuid and organization_id = ?::uuid", Integer.class, id, orgId)).isEqualTo(1);
    }

    @Test
    void adminOfAnotherOrganizationCannotRevokeOurInvitation() throws Exception {
        String email = TestAccounts.uniqueEmail();
        invite(owner, email, "MEMBER", 201);
        String id = invitationId(email);
        Account foreignOwner = accounts.verified(accounts.signup("Foreign Invite Org"));
        Account foreignAdmin = accounts.memberOf(foreignOwner.organizationId(), "ADMIN", "Frank Foreign");

        foreignAdmin.client().delete(INVITATIONS + "/" + id).andExpect(status().isNotFound());
        foreignOwner.client().delete(INVITATIONS + "/" + id).andExpect(status().isNotFound());

        assertThat(jdbc.queryForObject("select revoked_at is null from invitation where id = ?::uuid", Boolean.class,
                id)).isTrue();
        foreignOwner.client().get(INVITATIONS).andExpect(jsonPath("$.totalItems").value(0));
        owner.client().get(INVITATIONS).andExpect(jsonPath("$.totalItems").value(1));
    }

    // ---- lookup ----

    @Test
    void lookupReturnsOrganizationRoleEmailAndWhetherAnAccountExists() throws Exception {
        String newcomer = TestAccounts.uniqueEmail();
        String token = inviteAndGetToken(newcomer, "ADMIN");
        accounts.newClient().post(LOOKUP, Map.of("token", token)).andExpect(status().isOk())
                .andExpect(jsonPath("$.organizationName").value(orgName)).andExpect(jsonPath("$.role").value("ADMIN"))
                .andExpect(jsonPath("$.email").value(newcomer)).andExpect(jsonPath("$.accountExists").value(false));

        Account existing = accounts.signup("Existing Org");
        String token2 = inviteAndGetToken(existing.email().toUpperCase(), "VIEWER");
        accounts.newClient().post(LOOKUP, Map.of("token", token2)).andExpect(status().isOk())
                .andExpect(jsonPath("$.accountExists").value(true));
    }

    @Test
    void lookupOfAnyInvalidTokenIsTheSame404() throws Exception {
        String expiredEmail = TestAccounts.uniqueEmail();
        String expiredToken = inviteAndGetToken(expiredEmail, "MEMBER");
        String revokedEmail = TestAccounts.uniqueEmail();
        String revokedToken = inviteAndGetToken(revokedEmail, "MEMBER");
        owner.client().delete(INVITATIONS + "/" + invitationId(revokedEmail)).andExpect(status().isNoContent());
        String acceptedToken = inviteAndGetToken(TestAccounts.uniqueEmail(), "MEMBER");
        accounts.newClient().post(ACCEPT, Map.of("token", acceptedToken, "fullName", "A B", "password", NEW_PASSWORD))
                .andExpect(status().isOk());
        clock.advance(Duration.ofDays(8)); // expires the first one (and the revoked/accepted ones are already dead)

        List<String> bodies = new ArrayList<>();
        for (String token : List.of("garbage", expiredToken, revokedToken, acceptedToken)) {
            String body = accounts.newClient().post(LOOKUP, Map.of("token", token)).andExpect(status().isNotFound())
                    .andReturn().getResponse().getContentAsString();
            JsonNode node = json.readTree(body);
            bodies.add(node.get("title").asString() + "|" + node.get("detail").asString());
        }
        assertThat(bodies).hasSize(4).containsOnly(bodies.get(0));
    }

    // ---- accept ----

    @Test
    void acceptCreatesAVerifiedAccountMembershipAndASession() throws Exception {
        String email = TestAccounts.uniqueEmail();
        String token = inviteAndGetToken(email, "MEMBER");
        ApiClient browser = accounts.newClient();
        String csrfBefore = browser.cookie(ApiClient.CSRF_COOKIE);

        browser.post(ACCEPT, Map.of("token", token, "fullName", "Nina Newcomer", "password", NEW_PASSWORD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.email").value(email))
                .andExpect(jsonPath("$.user.fullName").value("Nina Newcomer"))
                .andExpect(jsonPath("$.user.emailVerified").value(true))
                .andExpect(jsonPath("$.activeOrganization.id").value(orgId))
                .andExpect(jsonPath("$.activeOrganization.role").value("MEMBER"))
                .andExpect(jsonPath("$.organizations.length()").value(1));

        assertThat(browser.cookie(ApiClient.SESSION_COOKIE)).isNotBlank();
        assertThat(browser.cookie(ApiClient.CSRF_COOKIE)).isNotEqualTo(csrfBefore); // rotated like login
        browser.get("/api/v1/organization").andExpect(status().isOk()).andExpect(jsonPath("$.name").value(orgName));
        accounts.login(email, NEW_PASSWORD).get("/api/v1/me").andExpect(status().isOk());
        assertThat(membershipCount(email)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select accepted_at is not null from invitation where id = ?::uuid",
                Boolean.class, invitationId(email))).isTrue();
        assertThat(jdbc.queryForObject("select last_active_organization_id::text from app_user where lower(email) = lower(?)",
                String.class, email)).isEqualTo(orgId);
        assertThat(jdbc.queryForObject("select count(*) from audit_event where organization_id = ?::uuid "
                + "and action in ('invitation.accepted', 'membership.created')", Integer.class, orgId)).isEqualTo(2);
    }

    @Test
    void acceptWithoutAnAccountNeedsNameAndAValidPasswordAndKeepsTheLinkUsable() throws Exception {
        String email = TestAccounts.uniqueEmail();
        String token = inviteAndGetToken(email, "VIEWER");

        accounts.newClient().post(ACCEPT, Map.of("token", token)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.field=='fullName')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field=='password')]").exists());
        accounts.newClient().post(ACCEPT, Map.of("token", token, "fullName", "N N", "password", "password1234"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("password"));
        accounts.newClient().post(ACCEPT, Map.of("token", token, "fullName", "N N", "password", "short"))
                .andExpect(status().isBadRequest());

        assertThat(membershipCount(email)).isZero();
        accounts.newClient().post(ACCEPT, Map.of("token", token, "fullName", "N N", "password", NEW_PASSWORD))
                .andExpect(status().isOk());
    }

    @Test
    void acceptForAnExistingAccountWithoutASessionIs409SignInToAccept() throws Exception {
        Account existing = accounts.signup("Existing Org 2");
        String token = inviteAndGetToken(existing.email(), "MEMBER");

        accounts.newClient().post(ACCEPT, Map.of("token", token, "fullName", "Hacker", "password", NEW_PASSWORD))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.title").value("Sign in to accept"));

        assertThat(isMember(existing.userId())).isFalse();
        // The existing account's password is untouched.
        accounts.login(existing.email(), existing.password());
    }

    @Test
    void acceptWithTheMatchingSessionJoinsAndSwitchesTheActiveOrganization() throws Exception {
        Account invitee = accounts.signup("Invitee Home Org");
        String token = inviteAndGetToken(invitee.email().toUpperCase(), "ADMIN");
        invitee.client().get("/api/v1/organization").andExpect(jsonPath("$.name").value("Invitee Home Org"));

        invitee.client().post(ACCEPT, Map.of("token", token)).andExpect(status().isOk())
                .andExpect(jsonPath("$.activeOrganization.id").value(orgId))
                .andExpect(jsonPath("$.activeOrganization.role").value("ADMIN"))
                .andExpect(jsonPath("$.organizations.length()").value(2))
                .andExpect(jsonPath("$.user.id").value(invitee.userId()));

        invitee.client().get("/api/v1/organization").andExpect(jsonPath("$.name").value(orgName));
        assertThat(jdbc.queryForObject("select last_active_organization_id::text from app_user where id = ?::uuid",
                String.class, invitee.userId())).isEqualTo(orgId);
        // The inviter's lookups do not give the invitee's own account any new email-verified flag.
        assertThat(jdbc.queryForObject("select email_verified_at is null from app_user where id = ?::uuid",
                Boolean.class, invitee.userId())).isTrue();
    }

    @Test
    void acceptWithADifferentAccountsSessionIs403AndTheInvitationStaysPending() throws Exception {
        String token = inviteAndGetToken(TestAccounts.uniqueEmail(), "MEMBER");
        Account wrong = accounts.signup("Wrong Person Org");

        wrong.client().post(ACCEPT, Map.of("token", token)).andExpect(status().isForbidden());

        assertThat(isMember(wrong.userId())).isFalse();
        accounts.newClient().post(LOOKUP, Map.of("token", token)).andExpect(status().isOk());
    }

    @Test
    void acceptingWhenAlreadyAMemberKeepsTheExistingRoleAndConsumesTheInvitation() throws Exception {
        Account invitee = accounts.signup("Already Home Org");
        String token = inviteAndGetToken(invitee.email(), "VIEWER");
        accounts.joinOrganization(invitee, orgId, "ADMIN"); // became an admin through another route meanwhile

        invitee.client().post(ACCEPT, Map.of("token", token)).andExpect(status().isOk())
                .andExpect(jsonPath("$.activeOrganization.role").value("ADMIN"));

        assertThat(membershipCount(invitee.email())).isEqualTo(1);
        accounts.newClient().post(LOOKUP, Map.of("token", token)).andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("select count(*) from audit_event where organization_id = ?::uuid "
                + "and action = 'membership.created' and actor_user_id = ?::uuid", Integer.class, orgId,
                invitee.userId())).isZero();
    }

    @Test
    void acceptWithAnExpiredOrUnknownTokenIs404() throws Exception {
        String token = inviteAndGetToken(TestAccounts.uniqueEmail(), "MEMBER");
        accounts.newClient().post(ACCEPT, Map.of("token", "unknown", "fullName", "A B", "password", NEW_PASSWORD))
                .andExpect(status().isNotFound());
        clock.advance(Duration.ofDays(8));
        accounts.newClient().post(ACCEPT, Map.of("token", token, "fullName", "A B", "password", NEW_PASSWORD))
                .andExpect(status().isNotFound());
    }

    @Test
    void acceptingTwiceSequentiallyCreatesOneMembership() throws Exception {
        String email = TestAccounts.uniqueEmail();
        String token = inviteAndGetToken(email, "MEMBER");
        accounts.newClient().post(ACCEPT, Map.of("token", token, "fullName", "A B", "password", NEW_PASSWORD))
                .andExpect(status().isOk());
        accounts.newClient().post(ACCEPT, Map.of("token", token, "fullName", "A B", "password", NEW_PASSWORD))
                .andExpect(status().isNotFound());
        assertThat(membershipCount(email)).isEqualTo(1);
    }

    @Test
    void concurrentDoubleAcceptCreatesExactlyOneAccountAndMembership() throws Exception {
        for (int round = 0; round < 4; round++) {
            String email = TestAccounts.uniqueEmail();
            String token = inviteAndGetToken(email, "MEMBER");
            ApiClient c1 = accounts.newClient();
            ApiClient c2 = accounts.newClient();

            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                CountDownLatch go = new CountDownLatch(1);
                List<Future<Integer>> results = new ArrayList<>();
                for (ApiClient client : List.of(c1, c2)) {
                    results.add(pool.submit(() -> {
                        go.await();
                        return client.post(ACCEPT, Map.of("token", token, "fullName", "Dup Dup", "password",
                                NEW_PASSWORD)).andReturn().getResponse().getStatus();
                    }));
                }
                go.countDown();
                List<Integer> statuses = new ArrayList<>();
                for (Future<Integer> f : results) {
                    statuses.add(f.get());
                }
                assertThat(statuses).as("round %d", round).containsExactlyInAnyOrder(200, 404);
            } finally {
                pool.shutdown();
            }
            assertThat(membershipCount(email)).isEqualTo(1);
            assertThat(jdbc.queryForObject("select count(*) from app_user where lower(email) = lower(?)",
                    Integer.class, email)).isEqualTo(1);
        }
    }

    @Test
    void concurrentDoubleAcceptWithASessionCreatesOneMembership() throws Exception {
        Account invitee = accounts.signup("Double Home Org");
        String token = inviteAndGetToken(invitee.email(), "MEMBER");
        ApiClient second = invitee.client().copy();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Integer>> results = new ArrayList<>();
            for (ApiClient client : List.of(invitee.client(), second)) {
                results.add(pool.submit(() -> {
                    go.await();
                    return client.post(ACCEPT, Map.of("token", token)).andReturn().getResponse().getStatus();
                }));
            }
            go.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> f : results) {
                statuses.add(f.get());
            }
            assertThat(statuses).containsExactlyInAnyOrder(200, 404);
        } finally {
            pool.shutdown();
        }
        assertThat(membershipCount(invitee.email())).isEqualTo(1);
    }

    @Test
    void invitationEndpointsArePublicButStillNeedCsrf() throws Exception {
        String token = inviteAndGetToken(TestAccounts.uniqueEmail(), "MEMBER");
        ApiClient noCsrf = new ApiClient(mvc, json).remoteAddr("198.51.100.77");
        noCsrf.postWithoutCsrf(LOOKUP, Map.of("token", token)).andExpect(status().isForbidden());
        noCsrf.postWithoutCsrf(ACCEPT, Map.of("token", token, "fullName", "A B", "password", NEW_PASSWORD))
                .andExpect(status().isForbidden());
    }
}
