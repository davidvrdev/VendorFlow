package com.vendorflow.shared.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.DefaultCookieSerializer;

/**
 * Session cookie attributes, set explicitly: HttpOnly (no JS access), SameSite=Lax, name VF_SESSION, Secure in prod.
 * We define the serializer ourselves instead of relying on server.servlet.session.cookie.* because Spring Session's
 * filter writes the cookie (not the servlet container), and we want these attributes asserted by tests.
 */
@Configuration(proxyBeanMethods = false)
public class SessionCookieConfig {

    public static final String COOKIE_NAME = "VF_SESSION";

    @Bean
    CookieSerializer cookieSerializer(@Value("${app.security.session-cookie-secure:true}") boolean secure) {
        DefaultCookieSerializer serializer = new DefaultCookieSerializer();
        serializer.setCookieName(COOKIE_NAME);
        serializer.setUseHttpOnlyCookie(true);
        serializer.setSameSite("Lax");
        serializer.setUseSecureCookie(secure);
        serializer.setCookiePath("/");
        return serializer;
    }
}
