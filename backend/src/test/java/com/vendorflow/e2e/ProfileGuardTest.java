package com.vendorflow.e2e;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vendorflow.shared.profile.ProfileGuard;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class ProfileGuardTest {

    @Test
    void e2eTogetherWithProdRefusesToStart() {
        assertThatThrownBy(() -> ProfileGuard.check(List.of("prod", "e2e"))).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("e2e").hasMessageContaining("prod");
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("local", "e2e", "prod");
        assertThatThrownBy(() -> new ProfileGuard(env)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void everyOtherCombinationIsAllowed() {
        assertThatCode(() -> ProfileGuard.check(List.of("local", "e2e"))).doesNotThrowAnyException();
        assertThatCode(() -> ProfileGuard.check(List.of("prod"))).doesNotThrowAnyException();
        assertThatCode(() -> ProfileGuard.check(List.of("test"))).doesNotThrowAnyException();
        assertThatCode(() -> ProfileGuard.check(List.of())).doesNotThrowAnyException();
    }
}
