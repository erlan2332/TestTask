package com.erlan.testtask.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;

class SecurityConfigTest {

    private final SecurityConfig config = new SecurityConfig();
    private final PasswordEncoder encoder = config.passwordEncoder();

    @Test
    void configuredUserHasBcryptPassword() {
        UserDetails user = config.userDetailsService("test-user", "test-password", encoder)
                .loadUserByUsername("test-user");

        assertThat(user.getUsername()).isEqualTo("test-user");
        assertThat(user.getAuthorities()).isEmpty();
        assertThat(user.getPassword()).startsWith("$2").isNotEqualTo("test-password");
        assertThat(encoder.matches("test-password", user.getPassword())).isTrue();
        assertThat(encoder.matches("wrong-password", user.getPassword())).isFalse();
    }

    @Test
    void missingOrBlankCredentialsFailConfiguration() {
        assertThatThrownBy(() -> config.userDetailsService("", "test-password", encoder))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("APP_SECURITY_USERNAME");
        assertThatThrownBy(() -> config.userDetailsService("test-user", "", encoder))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("APP_SECURITY_PASSWORD");
        assertThatThrownBy(() -> config.userDetailsService("  ", "test-password", encoder))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> config.userDetailsService("test-user", "  ", encoder))
                .isInstanceOf(IllegalStateException.class);
    }
}
