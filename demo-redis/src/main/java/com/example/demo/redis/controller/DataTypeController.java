package com.example.demo.redis.controller;

import com.example.demo.redis.support.DemoResult;
import com.example.demo.redis.types.DataTypeService;
import com.example.demo.redis.types.DataTypeService.TypeInfo;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 9 种数据类型的独立案例入口。
 *
 * <p>推荐从 {@code GET /api/redis/types} 看目录，再逐个执行；
 * {@code /all} 会串行跑完九种，返回按目录顺序排列的结果，适合一次性看全貌。</p>
 */
@RestController
@RequestMapping("/api/redis/types")
public class DataTypeController {

    private final DataTypeService service;

    /** 注入数据类型服务；控制器只负责路由，不含业务逻辑。 */
    public DataTypeController(DataTypeService service) {
        this.service = service;
    }

    /** 类型目录：名称、展示名、一句话场景，顺序即页面导航顺序。 */
    @GetMapping
    public List<TypeInfo> catalog() {
        return service.catalog();
    }

    /** 串行执行全部九种类型；用 LinkedHashMap 保留目录顺序，JSON 输出稳定。 */
    @GetMapping("/all")
    public Map<String, DemoResult> all() {
        Map<String, DemoResult> results = new LinkedHashMap<>();
        for (TypeInfo info : service.catalog()) {
            results.put(info.name(), service.run(info.name()));
        }
        return results;
    }

    /**
     * 执行单个类型，例如 {@code /api/redis/types/zset}。
     *
     * @param type 目录中的 name；未知类型返回 400 并列出可选值
     */
    @GetMapping("/{type}")
    public DemoResult run(@PathVariable String type) {
        return service.run(type);
    }
}
