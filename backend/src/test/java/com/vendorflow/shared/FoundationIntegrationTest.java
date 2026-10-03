package com.vendorflow.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vendorflow.support.IntegrationTest;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

class FoundationIntegrationTest extends IntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired Clock clock;

    @Test
    void flywayAppliedBaselineMigration() {
        Integer applied = jdbc.queryForObject(
                "select count(*) from flyway_schema_history where version = '1' and success", Integer.class);
        assertThat(applied).isEqualTo(1);
    }

    @Test
    void clockBeanIsUtc() {
        assertThat(clock.getZone()).isEqualTo(ZoneOffset.UTC);
    }

    @Test
    void healthIsPublicAndHidesDetails() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist());
    }

    @Test
    void livenessAndReadinessProbesArePublic() throws Exception {
        mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
    }

    @Test
    void otherActuatorEndpointsAreNotPublic() throws Exception {
        mvc.perform(get("/actuator/env")).andExpect(status().isUnauthorized());
    }

    @Test
    void anonymousApiCallGets401ProblemJsonWithRequestId() throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/anything"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(header().exists("X-Request-Id"))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.type").value("https://vendorflow.app/problems/unauthenticated"))
                .andReturn();
        String headerId = result.getResponse().getHeader("X-Request-Id");
        assertThat(result.getResponse().getContentAsString()).contains("\"requestId\":\"" + headerId + "\"");
    }

    @Test
    void unauthenticatedResponseDoesNotCreateSession() throws Exception {
        mvc.perform(get("/api/v1/anything"))
                .andExpect(header().doesNotExist("Set-Cookie"));
    }

    @Test
    void securityHeadersAreKept() throws Exception {
        mvc.perform(get("/api/v1/anything"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"));
    }

    @Test
    void validIncomingRequestIdIsEchoed() throws Exception {
        mvc.perform(get("/api/v1/anything").header("X-Request-Id", "abc-12345-trace"))
                .andExpect(header().string("X-Request-Id", "abc-12345-trace"))
                .andExpect(jsonPath("$.requestId").value("abc-12345-trace"));
    }

    @Test
    void invalidIncomingRequestIdIsReplacedByUuid() throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/anything").header("X-Request-Id", "<script>alert(1)</script>"))
                .andReturn();
        String id = result.getResponse().getHeader("X-Request-Id");
        assertThat(id).doesNotContain("<script>");
        assertThat(UUID.fromString(id)).isNotNull();
    }

    @Test
    @WithMockUser
    void unexpectedExceptionReturns500WithoutLeakingMessage() throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/test-probe/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("An unexpected error occurred."))
                .andExpect(jsonPath("$.requestId").exists())
                .andReturn();
        assertThat(result.getResponse().getContentAsString())
                .doesNotContain("secret-db-password")
                .doesNotContain("RuntimeException");
    }

    @Test
    @WithMockUser
    void notFoundExceptionReturns404Problem() throws Exception {
        mvc.perform(get("/api/v1/test-probe/missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("https://vendorflow.app/problems/not-found"))
                .andExpect(jsonPath("$.requestId").exists());
    }

    @Test
    @WithMockUser
    void invalidBodyReturns400WithFieldErrors() throws Exception {
        mvc.perform(post("/api/v1/test-probe/validate").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\",\"email\":\"not-an-email\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://vendorflow.app/problems/validation"))
                .andExpect(jsonPath("$.requestId").exists())
                .andExpect(jsonPath("$.errors.length()").value(2))
                .andExpect(jsonPath("$.errors[?(@.field=='name')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field=='email')]").exists());
    }

    @Test
    @WithMockUser
    void invalidRequestParamReturns400WithFieldErrors() throws Exception {
        mvc.perform(get("/api/v1/test-probe/param").param("size", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("size"));
    }

    @Test
    @WithMockUser
    void unsafeMethodWithoutCsrfTokenIsForbiddenAsProblemJson() throws Exception {
        mvc.perform(post("/api/v1/test-probe/validate")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.requestId").exists());
    }
}
