package com.example.demo.rabbitmq.controller;

import com.example.demo.rabbitmq.config.RabbitMQConfig;
import com.example.demo.rabbitmq.producer.ConfirmProducer;
import com.example.demo.rabbitmq.producer.DirectProducer;
import com.example.demo.rabbitmq.producer.FanoutProducer;
import com.example.demo.rabbitmq.producer.SimpleProducer;
import com.example.demo.rabbitmq.producer.TopicProducer;
import com.example.demo.rabbitmq.producer.WorkProducer;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/rabbitmq")
public class RabbitMQDemoController {

    private final SimpleProducer simpleProducer;
    private final WorkProducer workProducer;
    private final FanoutProducer fanoutProducer;
    private final DirectProducer directProducer;
    private final TopicProducer topicProducer;
    private final ConfirmProducer confirmProducer;

    public RabbitMQDemoController(SimpleProducer simpleProducer,
                                  WorkProducer workProducer,
                                  FanoutProducer fanoutProducer,
                                  DirectProducer directProducer,
                                  TopicProducer topicProducer,
                                  ConfirmProducer confirmProducer) {
        this.simpleProducer = simpleProducer;
        this.workProducer = workProducer;
        this.fanoutProducer = fanoutProducer;
        this.directProducer = directProducer;
        this.topicProducer = topicProducer;
        this.confirmProducer = confirmProducer;
    }

    @PostMapping("/simple")
    public Map<String, String> sendSimple(@RequestParam(defaultValue = "hello rabbitmq") String message) {
        simpleProducer.send(message);
        return Map.of("status", "sent", "queue", "demo.simple.queue", "message", message);
    }

    @PostMapping("/work")
    public Map<String, String> sendWork(@RequestParam(defaultValue = "work task") String message) {
        workProducer.send(message);
        return Map.of("status", "sent", "queue", "demo.work.queue", "message", message);
    }

    @PostMapping("/fanout")
    public Map<String, String> sendFanout(@RequestParam(defaultValue = "broadcast msg") String message) {
        fanoutProducer.send(message);
        return Map.of("status", "sent", "exchange", "demo.fanout.exchange", "message", message);
    }

    @PostMapping("/direct")
    public Map<String, String> sendDirect(
            @RequestParam(defaultValue = "order.created") String routingKey,
            @RequestParam(defaultValue = "direct msg") String message) {
        directProducer.send(routingKey, message);
        return Map.of("status", "sent", "exchange", "demo.direct.exchange", "routingKey", routingKey, "message", message);
    }

    @PostMapping("/topic")
    public Map<String, String> sendTopic(
            @RequestParam(defaultValue = "order.created") String routingKey,
            @RequestParam(defaultValue = "topic msg") String message) {
        topicProducer.send(routingKey, message);
        return Map.of("status", "sent", "exchange", "demo.topic.exchange", "routingKey", routingKey, "message", message);
    }

    @PostMapping("/confirm")
    public Map<String, String> sendConfirm(@RequestParam(defaultValue = "confirm msg") String message) {
        confirmProducer.send(message);
        return Map.of("status", "sent", "feature", "publisher-confirm", "message", message);
    }

    @PostMapping("/dlx")
    public Map<String, String> sendDlx(@RequestParam(defaultValue = "dlx msg") String message) {
        confirmProducer.sendToQueue(RabbitMQConfig.DLQ_MAIN_QUEUE, message);
        return Map.of("status", "sent", "feature", "dead-letter", "hint", "message will be rejected and routed to DLQ", "message", message);
    }
}
