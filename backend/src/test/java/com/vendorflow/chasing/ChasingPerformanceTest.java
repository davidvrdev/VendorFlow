package com.vendorflow.chasing;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalTime;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/** One organization tick with 500 deficient vendors: bounded statements (no per-vendor deficiency/lookup queries). */
@Import(ChasingPerformanceTest.Counting.class)
class ChasingPerformanceTest extends ChasingTestBase {

    static final List<String> STATEMENTS = new CopyOnWriteArrayList<>();

    @TestConfiguration(proxyBeanMethods = false)
    static class Counting {
        @Bean
        static BeanPostProcessor countingPostProcessor() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String name) throws BeansException {
                    return bean instanceof DataSource ds && !Proxy.isProxyClass(bean.getClass()) ? wrap(ds) : bean;
                }
            };
        }
    }

    private static Object invoke(Object target, java.lang.reflect.Method m, Object[] args) throws Throwable {
        try {
            return m.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private static DataSource wrap(DataSource ds) {
        return (DataSource) Proxy.newProxyInstance(ChasingPerformanceTest.class.getClassLoader(),
                new Class<?>[] {DataSource.class}, (p, m, a) -> {
                    Object r = invoke(ds, m, a);
                    return r instanceof Connection c ? wrap(c) : r;
                });
    }

    private static Connection wrap(Connection c) {
        return (Connection) Proxy.newProxyInstance(ChasingPerformanceTest.class.getClassLoader(),
                new Class<?>[] {Connection.class}, (p, m, a) -> {
                    Object r = invoke(c, m, a);
                    if (r instanceof PreparedStatement ps && m.getName().equals("prepareStatement")) {
                        return wrap(PreparedStatement.class, ps, String.valueOf(a[0]));
                    }
                    return r;
                });
    }

    private static <S extends Statement> Object wrap(Class<S> type, S st, String sql) {
        return Proxy.newProxyInstance(ChasingPerformanceTest.class.getClassLoader(), new Class<?>[] {type}, (p, m, a) -> {
            if (m.getName().startsWith("execute")) {
                STATEMENTS.add(sql);
            }
            return invoke(st, m, a);
        });
    }

    @Test
    void fiveHundredVendorsTickStaysWithinAStatementBudget() throws Exception {
        enableDefaults();
        int n = 500;
        Object service = AopProxyUtils.getSingletonTarget(chasing) != null ? AopProxyUtils.getSingletonTarget(chasing)
                : chasing;
        ReflectionTestUtils.setField(service, "dailyCap", 100_000); // the daily cap (default 200) is not what this test measures
        jdbc.update("""
                insert into vendor (id, organization_id, company_name, email, status, created_at, updated_at)
                select gen_random_uuid(), ?::uuid, 'Perf ' || g, 'perf' || g || '@vendor.example.com', 'ACTIVE', now(), now()
                from generate_series(1, ?) g""", org, n);
        jdbc.update("""
                insert into vendor_requirement (id, organization_id, vendor_id, document_type_id, created_at)
                select gen_random_uuid(), v.organization_id, v.id, t.id, now()
                from vendor v join document_type t on t.organization_id = v.organization_id and t.code in ('W9', 'COI')
                where v.organization_id = ?::uuid""", org);
        clockTo(NY, today(NY), LocalTime.of(10, 0));
        run(); // warm-up on the same data would chase; so measure a fresh org instead
        // (the warm-up chased everything; reset the ledger state to measure a cold full run)
        jdbc.update("delete from vendor_chase where organization_id = ?::uuid", org);
        jdbc.update("delete from vendor_chasing where organization_id = ?::uuid", org);
        jdbc.update("delete from notification where organization_id = ?::uuid", org);
        STATEMENTS.clear();
        long t0 = System.nanoTime();
        var result = run();
        long ms = (System.nanoTime() - t0) / 1_000_000;
        List<String> sts = List.copyOf(STATEMENTS);
        long selects = sts.stream().filter(s -> s.stripLeading().toLowerCase().startsWith("select")
                || s.stripLeading().toLowerCase().startsWith("with")).count();
        System.out.println("Chasing tick, " + n + " vendors: chased=" + result.chased() + " statements=" + sts.size()
                + " selects=" + selects + " ms=" + ms);
        assertThat(result.chased()).isEqualTo(n);
        assertThat(sts.size()).as("statements").isLessThanOrEqualTo(n * 9 + 80);
        assertThat(selects).as("read statements: constant, not per vendor").isLessThanOrEqualTo(25);
    }
}
