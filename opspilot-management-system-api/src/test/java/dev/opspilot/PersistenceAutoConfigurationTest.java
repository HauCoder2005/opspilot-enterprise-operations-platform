package dev.opspilot;

import com.zaxxer.hikari.HikariDataSource;
import dev.opspilot.security.filters.RequestIdFilter;
import jakarta.persistence.EntityManagerFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

@SpringBootTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=none"
})
class PersistenceAutoConfigurationTest {

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18")
            .withDatabaseName("opspilot_persistence_test")
            .withUsername("opspilot_test")
            .withPassword("opspilot_test");

    static {
        POSTGRES.start();
    }

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private NamedParameterJdbcTemplate namedParameterJdbcTemplate;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private RequestIdFilter requestIdFilter;

    @Autowired
    private FilterChainProxy securityFilterChain;

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @AfterAll
    static void stopPostgres() {
        POSTGRES.stop();
    }

    @Test
    void autoConfiguresJpaJdbcClientAndOneSharedHikariDataSource() {
        assertThat(applicationContext.getBeansOfType(DataSource.class)).hasSize(1);
        assertThat(applicationContext.getBeansOfType(JdbcClient.class)).hasSize(1);
        assertThat(applicationContext.getBeansOfType(NamedParameterJdbcTemplate.class)).hasSize(1);
        assertThat(applicationContext.getBeansOfType(EntityManagerFactory.class)).hasSize(1);
        assertThat(applicationContext.getBeansOfType(PlatformTransactionManager.class)).hasSize(1);
        assertThat(applicationContext.getBeansOfType(Flyway.class)).isEmpty();

        assertThat(dataSource).isInstanceOf(HikariDataSource.class);
        assertThat(((HikariDataSource) dataSource).getPoolName()).isEqualTo("OpsPilotHikariPool");
        assertThat(namedParameterJdbcTemplate.getJdbcOperations())
                .isInstanceOfSatisfying(JdbcTemplate.class,
                        jdbcTemplate -> assertThat(jdbcTemplate.getDataSource()).isSameAs(dataSource));
        assertThat(entityManagerFactory.isOpen()).isTrue();
        assertThat(transactionManager).isInstanceOf(JpaTransactionManager.class);
        assertThat(((JpaTransactionManager) transactionManager).getDataSource()).isSameAs(dataSource);
        assertThat(environmentProperty("spring.jpa.open-in-view")).isEqualTo("false");
        assertThat(environmentProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("none");
        assertThat(environmentProperty("logging.pattern.console")).contains("%X{traceId:-}");
        assertThat(applicationContext.getBeansOfType(RequestIdFilter.class)).hasSize(1);
    }

    @Test
    void executesReadOnlyJdbcQueryInsideJpaManagedTransactionWithoutCreatingSchema() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setReadOnly(true);

        Integer result = transaction.execute(status -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            return jdbcClient.sql("SELECT 1").query(Integer.class).single();
        });

        Long publicTableCount = jdbcClient.sql("""
                SELECT count(*)
                FROM information_schema.tables
                WHERE table_schema = 'public'
                """).query(Long.class).single();
        String serverMajorVersion = jdbcClient.sql("SHOW server_version_num")
                .query(String.class)
                .single();

        assertThat(result).isEqualTo(1);
        assertThat(publicTableCount).isZero();
        assertThat(serverMajorVersion).startsWith("18");
    }

    @Test
    void requestIdAndConfiguredSecurityChainReturnStandardUnauthorizedResponse() throws Exception {
        MockMvc mockMvc = standaloneSetup(new ProtectedTestController())
                .addFilters(requestIdFilter, securityFilterChain)
                .build();

        mockMvc.perform(get("/api/v1/protected-test"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().exists(RequestIdFilter.REQUEST_ID_HEADER))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    private String environmentProperty(String name) {
        return applicationContext.getBean(Environment.class).getProperty(name);
    }

    @RestController
    private static final class ProtectedTestController {

        @GetMapping("/api/v1/protected-test")
        String protectedEndpoint() {
            return "must-not-be-called";
        }
    }
}
