package com.vendorflow.shared.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.DefaultCookieSerializer;

/**
 * Session cookie attributes, set explicitly: HttpOnly (no JS access), SameSite=Lax, name __Host-VF_SESSION when Secure (prod; the prefix makes browsers
 * insist on Secure, Path=/ and no Domain, ASVS V3.4.5) and VF_SESSION on plain http (local/test).
 * We define the serializer ourselves instead of relying on server.servlet.session.cookie.* because Spring Session's
 * filter writes the cookie (not the servlet container), and we want these attributes asserted by tests.
 */
@Configuration(proxyBeanMethods = false)
public class SessionCookieConfig {

    public static final String COOKIE_NAME = "VF_SESSION";
    public static final String SECURE_COOKIE_NAME = "__Host-" + COOKIE_NAME;

    public static String cookieName(boolean secure) {
        return secure ? SECURE_COOKIE_NAME : COOKIE_NAME;
    }

    @Bean
    CookieSerializer cookieSerializer(@Value("${app.security.session-cookie-secure:true}") boolean secure) {
        DefaultCookieSerializer serializer = new DefaultCookieSerializer();
        serializer.setCookieName(cookieName(secure));
        serializer.setUseHttpOnlyCookie(true);
        serializer.setSameSite("Lax");
        serializer.setUseSecureCookie(secure);
        serializer.setCookiePath("/");
        return serializer;
    }
}
