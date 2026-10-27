package com.example.demo.inventory.interfaces;

import com.example.demo.inventory.InventoryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 库存查询接口（只读，便于观察事件驱动的结果）。
 */
@RestController
@RequestMapping("/api/inventory")
public class InventoryController {

    private final InventoryService inventoryService;

    public InventoryController(InventoryService inventoryService) {
        this.inventoryService = inventoryService;
    }

    @GetMapping("/stock")
    public Map<String, Integer> stock() {
        return inventoryService.stockSnapshot();
    }

    @GetMapping("/reserved")
    public Map<String, Integer> reserved() {
        return inventoryService.reservedSnapshot();
    }
}
