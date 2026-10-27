package com.example.demo.inventory;

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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "eventdriven.outbox.relay-enabled=false",
        "spring.rabbitmq.listener.simple.auto-startup=false"
})
class InventoryPersistenceTest {

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
            .withDatabaseName("demo_inventory")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired
    private InventoryService inventoryService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("预占：available 减少、reserved 增加，并落库")
    void reserve_shouldPersistAvailableAndReserved() {
        assertTrue(inventoryService.reserve("P-1001", 3));

        assertEquals(97, jdbcTemplate.queryForObject(
                "SELECT available FROM inventory_stock WHERE product_id = 'P-1001'", Integer.class));
        assertEquals(3, jdbcTemplate.queryForObject(
                "SELECT reserved FROM inventory_stock WHERE product_id = 'P-1001'", Integer.class));
    }

    @Test
    @DisplayName("释放：按预占量归还，且不会超过预占")
    void release_shouldReturnReservedStock() {
        assertTrue(inventoryService.reserve("P-1002", 5));

        inventoryService.release("P-1002", 5);
        assertEquals(50, jdbcTemplate.queryForObject(
                "SELECT available FROM inventory_stock WHERE product_id = 'P-1002'", Integer.class));

        inventoryService.release("P-1002", 5);
        assertEquals(50, jdbcTemplate.queryForObject(
                "SELECT available FROM inventory_stock WHERE product_id = 'P-1002'", Integer.class));
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT reserved FROM inventory_stock WHERE product_id = 'P-1002'", Integer.class));
    }

    @Test
    @DisplayName("库存不足：预占失败且不改动数据")
    void reserve_shouldFailWhenInsufficient() {
        assertFalse(inventoryService.reserve("P-1003", 25));

        assertEquals(20, jdbcTemplate.queryForObject(
                "SELECT available FROM inventory_stock WHERE product_id = 'P-1003'", Integer.class));
    }
}
