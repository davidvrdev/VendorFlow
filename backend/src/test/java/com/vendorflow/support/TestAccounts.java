package com.vendorflow.support;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Factory for accounts in tests. Every call creates fresh, uniquely named data, so tests never share state.
 * Usage: {@code Account a = accounts.signup("Acme"); a.client().get("/api/v1/me")}.
 */
public class TestAccounts {

    public static final String PASSWORD = "Correct-Horse-Battery-9";

    /** A signed-up, logged-in user: browser-like client + the Me returned by signup. */
    public record Account(ApiClient client, String email, String password, JsonNode me) {

        public String userId() {
            return me.get("user").get("id").asString();
        }

        public String organizationId() {
            return me.get("activeOrganization").get("id").asString();
        }
    }

    private final MockMvc mvc;
    private final JsonMapper json;
    private final JdbcTemplate jdbc;

    public TestAccounts(MockMvc mvc, JsonMapper json, JdbcTemplate jdbc) {
        this.mvc = mvc;
        this.json = json;
        this.jdbc = jdbc;
    }

    /** Gives an existing user a role in an organization (directly in the DB) and makes it their active org. */
    public void joinOrganization(Account user, String organizationId, String role) throws Exception {
        jdbc.update("""
                insert into membership (id, organization_id, user_id, role, created_at, updated_at)
                values (gen_random_uuid(), ?::uuid, ?::uuid, ?, now(), now())""", organizationId, user.userId(), role);
        user.client().post("/api/v1/session/organization", Map.of("organizationId", organizationId))
                .andExpect(status().isOk());
    }

    public static String uniqueEmail() {
        return "user-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
    }

    public ApiClient newClient() throws Exception {
        return new ApiClient(mvc, json).primeCsrf();
    }

    /** Signs up (which also logs in) a user who becomes OWNER of a new organization with the given name. */
    public Account signup(String organizationName) throws Exception {
        return signup(uniqueEmail(), PASSWORD, "Test User", organizationName);
    }

    public Account signup(String email, String password, String fullName, String organizationName)
            throws Exception {
        ApiClient client = newClient();
        String body = client.post("/api/v1/auth/signup", signupBody(email, password, fullName, organizationName))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return new Account(client, email, password, json.readTree(body));
    }

    /** Logs in with a brand-new browser (no previous session). */
    public ApiClient login(String email, String password) throws Exception {
        ApiClient client = newClient();
        client.post("/api/v1/auth/login", Map.of("email", email, "password", password))
                .andExpect(status().isOk());
        return client;
    }

    public static Map<String, Object> signupBody(String email, String password, String fullName,
            String organizationName) {
        return Map.of("email", email, "password", password, "fullName", fullName,
                "organizationName", organizationName);
    }
}
