package com.example.demo.rocketmq.listener;

import org.apache.rocketmq.spring.annotation.RocketMQTransactionListener;
import org.apache.rocketmq.spring.core.RocketMQLocalTransactionListener;
import org.apache.rocketmq.spring.core.RocketMQLocalTransactionState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.Message;

@RocketMQTransactionListener
public class TransactionListenerImpl implements RocketMQLocalTransactionListener {

    private static final Logger log = LoggerFactory.getLogger(TransactionListenerImpl.class);

    @Override
    public RocketMQLocalTransactionState executeLocalTransaction(Message msg, Object arg) {
        log.info("[TransactionListener] executing local transaction for: {}", msg.getPayload());
        try {
            log.info("[TransactionListener] local transaction committed");
            return RocketMQLocalTransactionState.COMMIT;
        } catch (Exception e) {
            log.error("[TransactionListener] local transaction failed", e);
            return RocketMQLocalTransactionState.ROLLBACK;
        }
    }

    @Override
    public RocketMQLocalTransactionState checkLocalTransaction(Message msg) {
        log.info("[TransactionListener] checking local transaction for: {}", msg.getPayload());
        return RocketMQLocalTransactionState.COMMIT;
    }
}
