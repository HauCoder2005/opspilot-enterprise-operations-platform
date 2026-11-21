package dev.opspilot.configs.auth;

import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class AuthTokenPropertiesTest {

    @Test
    void decodesBase64SecretAsJwtKeyMaterial() {
        byte[] expectedKey = new SecureRandom().generateSeed(48);
        AuthTokenProperties properties = properties(Base64.getEncoder().encodeToString(expectedKey));

        assertThat(properties.jwtKeyBytes()).containsExactly(expectedKey);
    }

    @Test
    void rejectsInvalidBase64AndDecodedKeysShorterThan256Bits() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> properties("not a base64 secret"))
                .withMessageContaining("Base64");

        String shortKey = Base64.getEncoder().encodeToString(new byte[31]);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> properties(shortKey))
                .withMessageContaining("ít nhất 32 byte");
    }

    private static AuthTokenProperties properties(String encodedSecret) {
        return new AuthTokenProperties(
                "opspilot-api",
                encodedSecret,
                Duration.ofMinutes(15),
                Duration.ofDays(7),
                Duration.ofDays(30),
                new AuthTokenProperties.RefreshCookie("opspilot_rt", true));
    }
}
