package com.example.demo.rocketmq.controller;

import com.example.demo.rocketmq.producer.BroadcastProducer;
import com.example.demo.rocketmq.producer.DelayProducer;
import com.example.demo.rocketmq.producer.OrderlyProducer;
import com.example.demo.rocketmq.producer.SimpleProducer;
import com.example.demo.rocketmq.producer.TagProducer;
import com.example.demo.rocketmq.producer.TransactionProducer;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/rocketmq")
public class RocketMQDemoController {

    private final SimpleProducer simpleProducer;
    private final OrderlyProducer orderlyProducer;
    private final TagProducer tagProducer;
    private final DelayProducer delayProducer;
    private final TransactionProducer transactionProducer;
    private final BroadcastProducer broadcastProducer;

    public RocketMQDemoController(SimpleProducer simpleProducer,
                                  OrderlyProducer orderlyProducer,
                                  TagProducer tagProducer,
                                  DelayProducer delayProducer,
                                  TransactionProducer transactionProducer,
                                  BroadcastProducer broadcastProducer) {
        this.simpleProducer = simpleProducer;
        this.orderlyProducer = orderlyProducer;
        this.tagProducer = tagProducer;
        this.delayProducer = delayProducer;
        this.transactionProducer = transactionProducer;
        this.broadcastProducer = broadcastProducer;
    }

    @PostMapping("/simple")
    public Map<String, String> sendSimple(@RequestParam(defaultValue = "hello rocketmq") String message) {
        simpleProducer.send(message);
        return Map.of("status", "sent", "topic", "demo-simple-topic", "message", message);
    }

    @PostMapping("/orderly")
    public Map<String, String> sendOrderly(
            @RequestParam(defaultValue = "order-001") String orderKey,
            @RequestParam(defaultValue = "orderly msg") String message) {
        orderlyProducer.send(orderKey, message);
        return Map.of("status", "sent", "topic", "demo-orderly-topic", "orderKey", orderKey, "message", message);
    }

    @PostMapping("/tag")
    public Map<String, String> sendTag(
            @RequestParam(defaultValue = "TagA") String tag,
            @RequestParam(defaultValue = "tag msg") String message) {
        tagProducer.send(tag, message);
        return Map.of("status", "sent", "topic", "demo-tag-topic", "tag", tag, "message", message);
    }

    @PostMapping("/delay")
    public Map<String, String> sendDelay(
            @RequestParam(defaultValue = "delay msg") String message,
            @RequestParam(defaultValue = "3") int delayLevel) {
        delayProducer.send(message, delayLevel);
        return Map.of("status", "sent", "topic", "demo-delay-topic", "delayLevel", String.valueOf(delayLevel), "message", message);
    }

    @PostMapping("/transaction")
    public Map<String, String> sendTransaction(@RequestParam(defaultValue = "tx msg") String message) {
        transactionProducer.send(message);
        return Map.of("status", "sent", "topic", "demo-tx-topic", "feature", "transaction", "message", message);
    }

    @PostMapping("/broadcast")
    public Map<String, String> sendBroadcast(@RequestParam(defaultValue = "broadcast msg") String message) {
        broadcastProducer.send(message);
        return Map.of("status", "sent", "topic", "demo-broadcast-topic", "feature", "broadcast", "message", message);
    }
}
