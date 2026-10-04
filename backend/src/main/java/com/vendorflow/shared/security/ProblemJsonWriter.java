package com.vendorflow.shared.security;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes a ProblemDetail from inside the security filter chain, where MVC message converters and the
 * controller advice do not apply. Serializes an explicit map so we do not depend on how ProblemDetail's
 * "properties" are mixed into Jackson.
 */
@Component
public class ProblemJsonWriter {

    private final JsonMapper jsonMapper;

    public ProblemJsonWriter(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    public void write(HttpServletResponse response, ProblemDetail pd, String instance) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", String.valueOf(pd.getType()));
        body.put("title", pd.getTitle());
        body.put("status", pd.getStatus());
        body.put("detail", pd.getDetail());
        body.put("instance", com.vendorflow.shared.web.SensitivePaths.mask(instance));
        if (pd.getProperties() != null) {
            body.putAll(pd.getProperties());
        }
        // These responses are written by filters that can run before (rate limit) or outside the security header
        // writers, so the two headers that matter for an error body are set here too.
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Cache-Control", "no-store");
        response.setStatus(pd.getStatus());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(jsonMapper.writeValueAsString(body));
        response.getWriter().flush();
    }
}
