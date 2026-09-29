package com.hmdp.consumer;

import com.hmdp.dto.CacheDeleteMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.concurrent.TimeUnit;

/**
 * 缓存删除失败补偿消费者
 * 监听 cache-delete-compensation topic，收到消息后重试删除 Redis 缓存。
 * 若仍失败，继续重发消息（最多3次），最终仍失败则打告警日志（可接监控平台）。
 */
@Slf4j
@Component
public class CacheDeleteConsumer {

    private static final String TOPIC = "cache-delete-compensation";
    private static final int MAX_RETRY = 3;

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private KafkaTemplate<String, Object> kafkaTemplate;

    @KafkaListener(topics = TOPIC, groupId = "cache-delete-group")
    public void onMessage(CacheDeleteMessage message, Acknowledgment ack) {
        try {
            // 幂等删除：Redis DEL 是幂等操作，重复删除无副作用
            Boolean deleted = stringRedisTemplate.delete(message.getCacheKey());
            log.info("缓存补偿删除成功，key={}, deleted={}", message.getCacheKey(), deleted);
            ack.acknowledge();
        } catch (Exception e) {
            if (message.getRetryCount() < MAX_RETRY) {
                // 重试次数+1，重新入队（退避策略由 Kafka 重试机制保证）
                message.setRetryCount(message.getRetryCount() + 1);
                log.warn("缓存补偿删除失败，第{}次重试，key={}", message.getRetryCount(), message.getCacheKey());
                kafkaTemplate.send(TOPIC, message);
                ack.acknowledge(); // 本消息已消费，重发作为新消息
            } else {
                // 超过最大重试次数，告警，人工介入
                log.error("缓存补偿删除彻底失败，已达最大重试次数，key={}", message.getCacheKey());
                ack.acknowledge();
            }
        }
    }
}
