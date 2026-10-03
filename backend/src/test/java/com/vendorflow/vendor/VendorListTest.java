package com.vendorflow.vendor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vendorflow.support.ApiClient;
import com.vendorflow.support.IntegrationTest;
import com.vendorflow.support.TestAccounts;
import com.vendorflow.support.TestAccounts.Account;
import jakarta.persistence.EntityManagerFactory;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** GET /vendors: filters, search escaping, sorting, paging, tenant scoping and the no-N+1 guarantee. */
class VendorListTest extends IntegrationTest {

    static final String VENDORS = "/api/v1/vendors";

    @Autowired MockMvc mvc;
    @Autowired JsonMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManagerFactory emf;

    TestAccounts accounts;
    Account owner;
    Account viewer;
    Account other;

    @BeforeEach
    void setUp() throws Exception {
        accounts = new TestAccounts(mvc, json, jdbc);
        owner = accounts.signup(TestAccounts.uniqueEmail(), TestAccounts.PASSWORD, "Dora Owner",
                "List Org " + UUID.randomUUID());
        viewer = accounts.memberOf(owner.organizationId(), "VIEWER", "Vic Viewer");
        other = accounts.signup(TestAccounts.uniqueEmail(), TestAccounts.PASSWORD, "Olga Other",
                "List Other " + UUID.randomUUID());
    }

    String create(Account who, String name, String contact, String email, String category) throws Exception {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("companyName", name);
        b.put("contactName", contact);
        b.put("email", email);
        b.put("category", category);
        return json.readTree(who.client().post(VENDORS, b).andExpect(status().isCreated()).andReturn()
                .getResponse().getContentAsString()).get("id").asString();
    }

    List<String> names(Account who, String query) throws Exception {
        JsonNode page = json.readTree(who.client().get(VENDORS + (query.isEmpty() ? "" : "?" + query))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        List<String> result = new ArrayList<>();
        page.get("items").forEach(i -> result.add(i.get("companyName").asString()));
        return result;
    }

    static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    @Test
    void defaultsToActiveVendorsSortedByNameWithThePageEnvelope() throws Exception {
        create(owner, "bravo", null, null, null);
        create(owner, "Alpha", null, null, null);
        String gone = create(owner, "Charlie", null, null, null);
        owner.client().post(VENDORS + "/" + gone + "/deactivate", null).andExpect(status().isOk());

        viewer.client().get(VENDORS).andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].companyName").value("Alpha"))
                .andExpect(jsonPath("$.items[1].companyName").value("bravo"))
                .andExpect(jsonPath("$.items[0].requirementCount").value(3))
                .andExpect(jsonPath("$.items[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.page").value(0)).andExpect(jsonPath("$.size").value(25))
                .andExpect(jsonPath("$.totalItems").value(2)).andExpect(jsonPath("$.totalPages").value(1));
    }

    @Test
    void statusFilterAcceptsActiveInactiveAllAndRejectsAnythingElse() throws Exception {
        create(owner, "Active One", null, null, null);
        String off = create(owner, "Inactive One", null, null, null);
        owner.client().post(VENDORS + "/" + off + "/deactivate", null);

        assertThat(names(owner, "status=ACTIVE")).containsExactly("Active One");
        assertThat(names(owner, "status=INACTIVE")).containsExactly("Inactive One");
        assertThat(names(owner, "status=ALL")).containsExactly("Active One", "Inactive One");
        owner.client().get(VENDORS + "?status=active").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("status"));
        owner.client().get(VENDORS + "?status=DELETED").andExpect(status().isBadRequest());
    }

    @Test
    void sortAllowlistAndDirections() throws Exception {
        create(owner, "Bravo", null, null, null);
        create(owner, "alpha", null, null, null);
        create(owner, "Charlie", null, null, null);

        assertThat(names(owner, "sort=companyName")).containsExactly("alpha", "Bravo", "Charlie");
        assertThat(names(owner, "sort=companyName,desc")).containsExactly("Charlie", "Bravo", "alpha");
        assertThat(names(owner, "sort=createdAt,asc")).containsExactly("Bravo", "alpha", "Charlie");
        assertThat(names(owner, "sort=createdAt,desc")).containsExactly("Charlie", "alpha", "Bravo");
        assertThat(names(owner, "sort=updatedAt,desc")).hasSize(3);

        for (String bad : List.of("sort=email", "sort=companyName,sideways", "sort=password,asc", "sort=",
                "sort=companyName,asc,desc", "sort=v.id;drop table vendor", "sort=companyName%20desc")) {
            if (bad.equals("sort=")) {
                owner.client().get(VENDORS + "?" + bad).andExpect(status().isOk()); // blank = default
                continue;
            }
            owner.client().get(VENDORS + "?" + bad).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].field").value("sort"));
        }
    }

    @Test
    void pagingIsStableAndReportsTotals() throws Exception {
        for (int i = 0; i < 7; i++) {
            create(owner, "Same Timestamp Co " + i, null, null, null);
        }
        // Many identical values of the sort key (all categories null for createdAt ties are rare, so also sort by name):
        List<String> all = names(owner, "size=100");
        assertThat(all).hasSize(7);
        owner.client().get(VENDORS + "?size=3&page=0").andExpect(jsonPath("$.totalItems").value(7))
                .andExpect(jsonPath("$.totalPages").value(3)).andExpect(jsonPath("$.items.length()").value(3));
        List<String> paged = new ArrayList<>();
        for (int p = 0; p < 3; p++) {
            paged.addAll(names(owner, "size=3&page=" + p));
        }
        assertThat(paged).containsExactlyElementsOf(all);
        assertThat(names(owner, "size=3&page=3")).isEmpty();
    }

    @Test
    void sizeIsClampedToTheMaximumOf100() throws Exception {
        create(owner, "One", null, null, null);
        owner.client().get(VENDORS + "?size=1000").andExpect(jsonPath("$.size").value(100));
        owner.client().get(VENDORS + "?size=0").andExpect(jsonPath("$.size").value(1));
        owner.client().get(VENDORS + "?page=-4").andExpect(jsonPath("$.page").value(0));
    }

    @Test
    void searchMatchesNameContactAndEmailCaseInsensitively() throws Exception {
        create(owner, "Acme Plumbing", "Pat Jones", "pat@acme.example", null);
        create(owner, "Best Roofing", "Rita Roof", "rita@best.example", null);
        create(owner, "Cool HVAC", "Zed", "ZED@COOL.EXAMPLE", null);

        assertThat(names(owner, "q=ACME")).containsExactly("Acme Plumbing");
        assertThat(names(owner, "q=" + enc("plumb"))).containsExactly("Acme Plumbing");
        assertThat(names(owner, "q=rita")).containsExactly("Best Roofing");
        assertThat(names(owner, "q=cool.example")).containsExactly("Cool HVAC");
        assertThat(names(owner, "q=" + enc("  jones  "))).containsExactly("Acme Plumbing");
        assertThat(names(owner, "q=nothing-matches")).isEmpty();
        assertThat(names(owner, "q=")).hasSize(3);
    }

    @Test
    void searchTreatsLikeWildcardsAndBackslashesAsLiteralText() throws Exception {
        create(owner, "Plain Co", null, null, null);
        create(owner, "100% Reliable", null, null, null);
        create(owner, "Under_score Co", null, null, null);
        create(owner, "Back\\slash Co", null, null, null);
        create(owner, "Bang! Co", null, null, null);

        assertThat(names(owner, "q=" + enc("%"))).containsExactly("100% Reliable");
        assertThat(names(owner, "q=" + enc("_"))).containsExactly("Under_score Co");
        assertThat(names(owner, "q=" + enc("\\"))).containsExactly("Back\\slash Co");
        assertThat(names(owner, "q=" + enc("!"))).containsExactly("Bang! Co");
        assertThat(names(owner, "q=" + enc("%%"))).isEmpty();
        assertThat(names(owner, "q=" + enc("P_ain"))).isEmpty();
    }

    @Test
    void categoryFilterIsExactAndCaseInsensitive() throws Exception {
        create(owner, "A", null, null, "Plumbing");
        create(owner, "B", null, null, "plumbing");
        create(owner, "C", null, null, "Plumbing Supplies");
        assertThat(names(owner, "category=PLUMBING")).containsExactly("A", "B");
    }

    @Test
    void listNeverReturnsAnotherOrganizationsVendorsEvenWhenTheSearchMatches() throws Exception {
        create(owner, "Needle Mine", null, null, "Shared");
        create(other, "Needle Theirs", "needle", "needle@theirs.example", "Shared");
        create(other, "Haystack Theirs", null, null, null);

        assertThat(names(owner, "q=needle&status=ALL")).containsExactly("Needle Mine");
        assertThat(names(owner, "q=theirs&status=ALL")).isEmpty();
        assertThat(names(owner, "category=Shared&status=ALL")).containsExactly("Needle Mine");
        assertThat(names(other, "status=ALL")).containsExactly("Haystack Theirs", "Needle Theirs");
        owner.client().get(VENDORS + "?status=ALL").andExpect(jsonPath("$.totalItems").value(1));
    }

    /**
     * No N+1: the number of SQL statements of the list endpoint must not grow with the number of vendors/requirements.
     * Hibernate Statistics (enabled in the test profile) counts the statements Hibernate prepares, which is every
     * statement our JPA code runs (Spring Session uses JdbcTemplate and is not counted). Cheapest tool that needs no
     * extra dependency (no datasource-proxy).
     */
    @Test
    void listExecutesABoundedNumberOfStatementsRegardlessOfVendorCount() throws Exception {
        Statistics stats = emf.unwrap(SessionFactory.class).getStatistics();
        ApiClient client = owner.client();

        for (int i = 0; i < 3; i++) {
            create(owner, "Few " + i, "c", "c@x.example", "Cat");
        }
        long few = statementsFor(stats, client);

        for (int i = 0; i < 30; i++) {
            create(owner, "Many " + i, "c", "c@x.example", "Cat");
        }
        client.get(VENDORS + "?size=100").andExpect(jsonPath("$.items.length()").value(33))
                .andExpect(jsonPath("$.items[0].requirementCount").value(3));
        long many = statementsFor(stats, client);

        assertThat(many).as("statements with 33 vendors vs 3 vendors").isEqualTo(few);
        // tenant lookup + page + count (+ maybe nothing else)
        assertThat(many).isLessThanOrEqualTo(4);
    }

    private long statementsFor(Statistics stats, ApiClient client) throws Exception {
        stats.clear();
        client.get(VENDORS + "?size=100").andExpect(status().isOk());
        return stats.getPrepareStatementCount();
    }
}
