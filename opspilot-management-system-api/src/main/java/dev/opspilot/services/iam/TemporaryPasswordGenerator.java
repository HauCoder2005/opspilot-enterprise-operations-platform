package dev.opspilot.services.iam;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;

/** Generates one-time temporary credentials without retaining or logging them. */
@Component
public class TemporaryPasswordGenerator {

    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";
    private static final int LENGTH = 16;
    private static final SecureRandom RANDOM = new SecureRandom();

    public String generate() {
        StringBuilder password = new StringBuilder(LENGTH);
        for (int index = 0; index < LENGTH; index++) {
            password.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return password.toString();
    }
}
