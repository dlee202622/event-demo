package com.example.demo.loyalty;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "eventdriven.outbox.relay-enabled=false",
        "spring.rabbitmq.listener.simple.auto-startup=false"
})
class LoyaltyPersistenceTest {

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
            .withDatabaseName("demo_loyalty")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired
    private LoyaltyPointsService loyaltyPointsService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("积分累加：同一客户多次发放落库并累加")
    void earn_shouldAccumulateAndPersist() {
        assertEquals(79, loyaltyPointsService.earn("C-PERSIST-1", 79));
        assertEquals(89, loyaltyPointsService.earn("C-PERSIST-1", 10));

        assertEquals(89, jdbcTemplate.queryForObject(
                "SELECT points FROM loyalty_points WHERE customer_id = 'C-PERSIST-1'", Integer.class));
    }

    @Test
    @DisplayName("积分按客户隔离")
    void pointsArePerCustomer() {
        loyaltyPointsService.earn("C-PERSIST-2", 5);
        loyaltyPointsService.earn("C-PERSIST-3", 2);

        assertEquals(5, loyaltyPointsService.pointsOf("C-PERSIST-2"));
        assertEquals(2, loyaltyPointsService.pointsOf("C-PERSIST-3"));
        assertEquals(0, loyaltyPointsService.pointsOf("C-PERSIST-404"));
    }
}
