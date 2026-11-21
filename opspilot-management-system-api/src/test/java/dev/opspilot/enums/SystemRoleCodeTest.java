package dev.opspilot.enums;

import dev.opspilot.models.iam.Role;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class SystemRoleCodeTest {

    private static final List<String> EXPECTED_CODES = List.of(
            "OWNER",
            "ORG_ADMIN",
            "REGIONAL_MANAGER",
            "BRANCH_MANAGER",
            "SHIFT_LEADER",
            "SALES_STAFF",
            "INVENTORY_STAFF",
            "EMPLOYEE"
    );

    @Test
    void containsExactlyTheEightSystemRoleCodes() {
        assertThat(Arrays.stream(SystemRoleCode.values()).map(Enum::name))
                .containsExactlyElementsOf(EXPECTED_CODES);
    }

    @Test
    void codesMatchSeedSystemRolesMigration() throws IOException {
        String migration;
        try (var input = getClass().getResourceAsStream("/db/migration/V1__initial_schema.sql")) {
            assertThat(input).as("V1 migration resource").isNotNull();
            migration = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }

        Matcher function = Pattern.compile(
                        "CREATE OR REPLACE FUNCTION seed_system_roles.*?FROM \\(VALUES(.*?)\\) AS v\\(code, name\\)",
                        Pattern.DOTALL)
                .matcher(migration);
        assertThat(function.find()).as("seed_system_roles function").isTrue();

        List<String> seededCodes = Pattern.compile("\\('([A-Z_]+)'\\s*,")
                .matcher(function.group(1))
                .results()
                .map(result -> result.group(1))
                .toList();

        assertThat(seededCodes).containsExactlyElementsOf(EXPECTED_CODES);
    }

    @Test
    void recognizesEverySystemRoleByItsDatabaseCode() {
        assertThat(EXPECTED_CODES)
                .allSatisfy(code -> assertThat(SystemRoleCode.valueOf(code).name()).isEqualTo(code));
    }

    @Test
    void roleStillAcceptsCustomCodeOutsideSystemRoleEnum() {
        Role customRole = new Role(1L, "CUSTOM_ROLE", "Custom Role");

        assertThat(customRole.getCode()).isEqualTo("CUSTOM_ROLE");
        assertThat(Arrays.stream(SystemRoleCode.values()).map(Enum::name))
                .doesNotContain(customRole.getCode());
    }
}
