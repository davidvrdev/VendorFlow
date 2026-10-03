package com.vendorflow.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vendorflow.shared.validation.PlainTextValidator;
import com.vendorflow.support.ApiClient;
import com.vendorflow.support.IntegrationTest;
import com.vendorflow.support.TestAccounts;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

/** M3: control (Cc) and format (Cf, e.g. bidi override) characters are rejected in human-entered names. */
class PlainTextValidationTest extends IntegrationTest {

    static final String[] BAD = {"Ada\nBcc: victim@example.com", "Ada\r\nLovelace", "Ada\tL", "Ada\u0000L",
            "Ada\u202Elovelace", "Ada\u200BL", "\u2066Ada", "\uFEFFAda", "Ada\u007F", "Ada\u0085L"};

    @Autowired MockMvc mvc;
    @Autowired JsonMapper json;
    @Autowired JdbcTemplate jdbc;

    @Test
    void validatorAcceptsOrdinaryInternationalNamesAndRejectsControlAndFormatCharacters() {
        PlainTextValidator v = new PlainTextValidator();
        for (String ok : new String[] {"Ada Lovelace", "Zo\u00EB O'Brien-Smith Jr.", "\u5C71\u7530 \u592A\u90CE",
                "M\u00FCller & S\u00F6hne, LLC", "Caf\u00E9 \uD83C\uDFE0 Homes", "", "  "}) {
            assertThat(v.isValid(ok, null)).as(ok).isTrue();
        }
        assertThat(v.isValid(null, null)).isTrue();
        for (String bad : BAD) {
            assertThat(v.isValid(bad, null)).as(bad).isFalse();
        }
    }

    @Test
    void signupRejectsControlCharactersInFullNameAndOrganizationName() throws Exception {
        TestAccounts accounts = new TestAccounts(mvc, json, jdbc);
        for (String bad : BAD) {
            String email = TestAccounts.uniqueEmail();
            accounts.newClient().post("/api/v1/auth/signup",
                    TestAccounts.signupBody(email, TestAccounts.PASSWORD, bad, "Fine Org"))
                    .andExpect(status().isBadRequest());
            accounts.newClient().post("/api/v1/auth/signup",
                    TestAccounts.signupBody(email, TestAccounts.PASSWORD, "Fine Name", bad))
                    .andExpect(status().isBadRequest());
            assertThat(jdbc.queryForObject("select count(*) from app_user where lower(email) = ?", Integer.class,
                    email)).isZero();
        }
        accounts.newClient().post("/api/v1/auth/signup", TestAccounts.signupBody(TestAccounts.uniqueEmail(),
                TestAccounts.PASSWORD, "Zo\u00EB O'Brien", "M\u00FCller & S\u00F6hne")).andExpect(status().isCreated());
    }

    @Test
    void renamingTheOrganizationRejectsControlCharacters() throws Exception {
        TestAccounts accounts = new TestAccounts(mvc, json, jdbc);
        TestAccounts.Account owner = accounts.signup("Rename Org");
        for (String bad : BAD) {
            owner.client().patch("/api/v1/organization", Map.of("name", bad)).andExpect(status().isBadRequest());
        }
        owner.client().patch("/api/v1/organization", Map.of("name", "Fine Name")).andExpect(status().isOk());
    }

    @Test
    void acceptingAnInvitationRejectsControlCharactersInFullName() throws Exception {
        ApiClient client = new TestAccounts(mvc, json, jdbc).newClient();
        for (String bad : BAD) {
            client.post("/api/v1/invitations/accept",
                    Map.of("token", "irrelevant-token", "fullName", bad, "password", TestAccounts.PASSWORD))
                    .andExpect(status().isBadRequest());
        }
    }
}
