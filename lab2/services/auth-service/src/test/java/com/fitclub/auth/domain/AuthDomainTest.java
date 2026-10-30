package com.fitclub.auth.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.fitclub.common.error.DomainException;

class AuthDomainTest {

    @Test
    void emailIsTrimmedAndLowercased() {
        assertThat(Email.of("  Ivan.Petrov@Mail.MD ").value()).isEqualTo("ivan.petrov@mail.md");
    }

    @Test
    void invalidEmailIsRejected() {
        assertThatThrownBy(() -> Email.of("no-at-sign")).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> Email.of("a@b")).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> Email.of("x".repeat(250) + "@a.md")).isInstanceOf(DomainException.class);
    }

    @Test
    void passwordMinimumLengthBoundary() {
        assertThatThrownBy(() -> PasswordPolicy.check("1234567")).hasMessageContaining("8");
        assertThatCode(() -> PasswordPolicy.check("12345678")).doesNotThrowAnyException();
    }

    @Test
    void bcryptLimitIsCountedInUtf8Bytes() {
        assertThatCode(() -> PasswordPolicy.check("a".repeat(72))).doesNotThrowAnyException();
        assertThatThrownBy(() -> PasswordPolicy.check("a".repeat(73)))
                .satisfies(e -> assertThat(((DomainException) e).code()).isEqualTo(AuthErrors.PASSWORD_TOO_LONG));
        // Кириллица — 2 байта на символ: 36 символов = 72 байта (можно), 37 = 74 байта (нельзя)
        assertThatCode(() -> PasswordPolicy.check("я".repeat(36))).doesNotThrowAnyException();
        assertThatThrownBy(() -> PasswordPolicy.check("я".repeat(37))).isInstanceOf(DomainException.class);
    }
}
