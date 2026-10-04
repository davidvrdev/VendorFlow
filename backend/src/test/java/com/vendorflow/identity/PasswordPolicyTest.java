package com.vendorflow.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vendorflow.identity.application.PasswordPolicy;
import com.vendorflow.shared.error.RequestValidationException;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/** Unit test: the policy only needs the classpath list, no Spring context. */
class PasswordPolicyTest {

    private final PasswordPolicy policy = new PasswordPolicy();

    @Test
    void listIsTheLargeTopHundredThousandStyleList() throws Exception {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new ClassPathResource("security/common-passwords.txt").getInputStream(), StandardCharsets.UTF_8))) {
            assertThat(r.lines().filter(l -> !l.isBlank()).count()).isGreaterThan(50_000);
        }
    }

    @Test
    void commonPasswordsAreRejectedCaseInsensitively() {
        for (String common : List.of("password1234", "PASSWORD1234", "PassWord1234", "qwertyuiop12")) {
            assertThatThrownBy(() -> policy.validate(common, "someone@example.com"))
                    .as(common).isInstanceOf(RequestValidationException.class);
        }
    }

    @Test
    void lengthIsTwelveToOneHundredTwentyEightCharactersNotBytes() {
        assertThatThrownBy(() -> policy.validate("Zq9!vL2#xT1", "a@b.co")).isInstanceOf(RequestValidationException.class);
        assertThatCode(() -> policy.validate("Zq9!vL2#xT1k", "a@b.co")).doesNotThrowAnyException();
        assertThatCode(() -> policy.validate("ñ".repeat(100) + "Zq9!vL2#xT1k", "a@b.co")).doesNotThrowAnyException();
        assertThatCode(() -> policy.validate("Zq9!".repeat(32), "a@b.co")).doesNotThrowAnyException();
        assertThatThrownBy(() -> policy.validate("Zq9!".repeat(32) + "x", "a@b.co"))
                .isInstanceOf(RequestValidationException.class);
    }

    @Test
    void emailIsRejectedCaseInsensitively() {
        assertThatThrownBy(() -> policy.validate("Someone.Long@Example.com", "someone.long@example.com"))
                .isInstanceOf(RequestValidationException.class);
    }
}
