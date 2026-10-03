package com.vendorflow.support;

import jakarta.servlet.http.Cookie;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import tools.jackson.databind.json.JsonMapper;

/**
 * A tiny "browser" on top of MockMvc: keeps cookies between calls (VF_SESSION, XSRF-TOKEN), sends the CSRF header like
 * the real frontend does (value of the XSRF-TOKEN cookie read right before the request), and updates its cookie jar
 * from every response, including expiry. MockMvc itself has no cookie handling, so sessions are passed explicitly.
 */
public class ApiClient {

    public static final String SESSION_COOKIE = "VF_SESSION";
    public static final String CSRF_COOKIE = "XSRF-TOKEN";
    public static final String CSRF_HEADER = "X-XSRF-TOKEN";

    private final MockMvc mvc;
    private final JsonMapper json;
    private final Map<String, String> cookies = new LinkedHashMap<>();
    private String remoteAddr = "127.0.0.1";

    public ApiClient(MockMvc mvc, JsonMapper json) {
        this.mvc = mvc;
        this.json = json;
    }

    public ApiClient remoteAddr(String remoteAddr) {
        this.remoteAddr = remoteAddr;
        return this;
    }

    public String cookie(String name) {
        return cookies.get(name);
    }

    public void setCookie(String name, String value) {
        cookies.put(name, value);
    }

    public void removeCookie(String name) {
        cookies.remove(name);
    }

    /** A second browser holding the same cookies (e.g. to replay an old session cookie). */
    public ApiClient copy() {
        ApiClient other = new ApiClient(mvc, json).remoteAddr(remoteAddr);
        other.cookies.putAll(cookies);
        return other;
    }

    /** Primes the CSRF cookie the way the frontend does. */
    public ApiClient primeCsrf() throws Exception {
        get("/api/v1/auth/csrf");
        return this;
    }

    public ResultActions get(String path) throws Exception {
        return perform(HttpMethod.GET, path, null, true);
    }

    public ResultActions post(String path, Object body) throws Exception {
        return perform(HttpMethod.POST, path, body, true);
    }

    public ResultActions patch(String path, Object body) throws Exception {
        return perform(HttpMethod.PATCH, path, body, true);
    }

    /** Unsafe request WITHOUT the CSRF header (to prove it is rejected). */
    public ResultActions postWithoutCsrf(String path, Object body) throws Exception {
        return perform(HttpMethod.POST, path, body, false);
    }

    public ResultActions perform(HttpMethod method, String path, Object body, boolean csrfHeader) throws Exception {
        MockHttpServletRequestBuilder builder = MockMvcRequestBuilders.request(method, path);
        builder.with(request -> {
            request.setRemoteAddr(remoteAddr);
            return request;
        });
        cookies.forEach((name, value) -> builder.cookie(new Cookie(name, value)));
        if (csrfHeader && cookies.containsKey(CSRF_COOKIE)) {
            builder.header(CSRF_HEADER, cookies.get(CSRF_COOKIE));
        }
        if (body != null) {
            builder.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        }
        ResultActions actions = mvc.perform(builder);
        absorbCookies(actions.andReturn().getResponse());
        return actions;
    }

    private void absorbCookies(MockHttpServletResponse response) {
        for (String header : response.getHeaders("Set-Cookie")) {
            String[] parts = header.split(";");
            int eq = parts[0].indexOf('=');
            if (eq < 0) {
                continue;
            }
            String name = parts[0].substring(0, eq).trim();
            String value = parts[0].substring(eq + 1).trim();
            boolean expired = value.isEmpty() || header.toLowerCase().contains("max-age=0");
            if (expired) {
                cookies.remove(name);
            } else {
                cookies.put(name, value);
            }
        }
    }
}
