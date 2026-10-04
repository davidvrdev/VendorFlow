package com.vendorflow.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vendorflow.support.IntegrationTest;
import com.vendorflow.support.TestAccounts;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

/**
 * Measures database round trips (statement executions) of typical authenticated GETs, so that a regression such as
 * a second membership query per request or an extra session write is caught. No new dependency: the DataSource is
 * wrapped with JDK proxies that record every statement execution (Hibernate statistics would miss Spring Session's
 * JdbcTemplate writes, which are part of what we measure).
 *
 * <p>Numbers and the optimizations behind them: docs/SECURITY.md "Database round trips per request".
 */
@Import(DbRoundTripsTest.CountingDataSourceConfig.class)
class DbRoundTripsTest extends IntegrationTest {

    static final List<String> STATEMENTS = new CopyOnWriteArrayList<>();

    @TestConfiguration(proxyBeanMethods = false)
    static class CountingDataSourceConfig {
        @Bean
        static BeanPostProcessor countingDataSourcePostProcessor() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
                    if (bean instanceof DataSource ds && !Proxy.isProxyClass(bean.getClass())) {
                        return proxyDataSource(ds);
                    }
                    return bean;
                }
            };
        }
    }

    /** Proxies a DataSource, its Connections and their (Prepared)Statements; records SQL on every execution. */
    private static DataSource proxyDataSource(DataSource target) {
        return (DataSource) Proxy.newProxyInstance(DbRoundTripsTest.class.getClassLoader(),
                new Class<?>[] {DataSource.class}, (proxy, method, args) -> {
                    try {
                        Object result = method.invoke(target, args);
                        return result instanceof Connection c ? proxyConnection(c) : result;
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    private static Connection proxyConnection(Connection connection) {
        return (Connection) Proxy.newProxyInstance(DbRoundTripsTest.class.getClassLoader(),
                new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                    try {
                        Object result = method.invoke(connection, args);
                        if (result instanceof PreparedStatement ps && method.getName().equals("prepareStatement")) {
                            return proxyStatement(PreparedStatement.class, ps, String.valueOf(args[0]));
                        }
                        if (result instanceof Statement st && method.getName().equals("createStatement")) {
                            return proxyStatement(Statement.class, st, null);
                        }
                        return result;
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    private static <S extends Statement> Object proxyStatement(Class<S> type, S statement, String sql) {
        return Proxy.newProxyInstance(DbRoundTripsTest.class.getClassLoader(), new Class<?>[] {type},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if (name.equals("execute") || name.equals("executeQuery") || name.equals("executeUpdate")
                            || name.equals("executeBatch") || name.equals("executeLargeUpdate")) {
                        STATEMENTS.add(sql != null ? sql : String.valueOf(args != null && args.length > 0 ? args[0] : "?"));
                    }
                    try {
                        return method.invoke(statement, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    @Autowired MockMvc mvc;
    @Autowired JsonMapper json;
    @Autowired JdbcTemplate jdbc;

    TestAccounts.Account owner;

    @BeforeEach
    void setUp() throws Exception {
        owner = new TestAccounts(mvc, json, jdbc).signup("Round Trip Org");
        owner.client().post("/api/v1/vendors", Map.of("companyName", "Acme", "contactName", "Carl"))
                .andExpect(status().isCreated());
    }

    private List<String> measure(String path) throws Exception {
        owner.client().get(path).andExpect(status().isOk()); // warm up (JIT, Hibernate metadata, caches)
        STATEMENTS.clear();
        owner.client().get(path).andExpect(status().isOk());
        List<String> statements = List.copyOf(STATEMENTS);
        System.out.println("DB round trips for GET " + path + ": " + statements.size());
        statements.forEach(s -> System.out.println("    " + s.replaceAll("\\s+", " ").substring(0,
                Math.min(150, s.replaceAll("\\s+", " ").length()))));
        return statements;
    }

    private static long count(List<String> statements, String fragment) {
        return statements.stream().filter(s -> s.toLowerCase().contains(fragment)).count();
    }

    @Test
    void authenticatedGetsStayWithinTheirRoundTripBudget() throws Exception {
        // Fixed overhead of ANY authenticated request: 1 session read + 1 membership/role check (TenantContextFilter)
        // + 1 session write (last access time; needed for the sliding idle timeout) = 3. Business queries come on top.
        for (String path : List.of("/api/v1/me", "/api/v1/organization", "/api/v1/vendors",
                "/api/v1/dashboard/summary")) {
            List<String> statements = measure(path);
            assertThat(count(statements, "spring_session")).as(path + " session statements").isEqualTo(2);
            assertThat(count(statements, "from membership m1_0 join organization o1_0")).as(path + " membership checks")
                    .isLessThanOrEqualTo(2); // /me also lists all of the user's organizations
        }
        assertThat(measure("/api/v1/me")).as("GET /me").hasSizeLessThanOrEqualTo(5);
        assertThat(measure("/api/v1/organization")).as("GET /organization").hasSizeLessThanOrEqualTo(4);
        assertThat(measure("/api/v1/vendors")).as("GET /vendors").hasSizeLessThanOrEqualTo(6);
        assertThat(measure("/api/v1/dashboard/summary")).as("GET /dashboard/summary").hasSizeLessThanOrEqualTo(5);
    }

    @Test
    void anonymousRequestsNeverTouchTheDatabase() throws Exception {
        var anonymous = new TestAccounts(mvc, json, jdbc).newClient();
        STATEMENTS.clear();
        anonymous.get("/api/v1/auth/csrf").andExpect(status().isNoContent());
        anonymous.get("/api/v1/me").andExpect(status().isUnauthorized());
        assertThat(STATEMENTS).as("no session row, no query for anonymous traffic").isEmpty();
    }
}
