package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hmdp.utils.OrderStatus;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder>
        implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private RedisIdWorker redisIdWorker;
    @Resource
    private RedissonClient redissonClient;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private KafkaTemplate<String, Object> kafkaTemplate;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    @Override
    public Result seckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        long orderId = redisIdWorker.nextId("order");
        // 1.执行lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString(), String.valueOf(orderId));
        int r = result.intValue();
        // 2.判断结果
        if (result == 1) {
            return Result.fail("库存不足");
        }
        if (result == 2) {
            return Result.fail("重复下单");
        }
        // 有购买资格
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setId(orderId); // 订单ID
        voucherOrder.setUserId(userId); // 用户ID
        voucherOrder.setVoucherId(voucherId); // 优惠券ID

        // 3.指定Topic发送Kafka消息 【秒杀链路消息队列使用】-2
        kafkaTemplate.send("seckill-voucher-order", voucherOrder.toString());

        // 4.返回订单id
        return Result.ok(orderId);
    }

    /**
     * 订单过期时间（分钟），超过未支付则自动关闭
     */
    private static final long ORDER_EXPIRE_MINUTES = 30;

    /**
     * 支付订单：乐观锁，仅当订单为"未支付"时才更新为"已支付"，
     * 防止与定时关单并发时产生"已取消的订单被支付"的并发问题。
     */
    @Override
    public Result payVoucherOrder(Long orderId, Integer payType) {
        // 1. 校验订单归属（防止支付别人的订单）
        Long userId = UserHolder.getUser().getId();
        VoucherOrder order = getById(orderId);
        if (order == null) {
            return Result.fail("订单不存在");
        }
        if (!userId.equals(order.getUserId())) {
            return Result.fail("无权操作该订单");
        }

        // 2. 乐观锁更新：只有 status = UNPAID 才允许更新
        // WHERE id = ? AND status = 1 保证并发安全
        boolean success = update()
                .set("status", OrderStatus.PAID)
                .set("pay_type", payType)
                .set("pay_time", LocalDateTime.now())
                .set("update_time", LocalDateTime.now())
                .eq("id", orderId)
                .eq("status", OrderStatus.UNPAID)
                .update();
        if (!success) {
            return Result.fail("订单已超时或状态异常，请刷新页面");
        }
        return Result.ok(orderId);
    }

    /**
     * 定时关单：扫描超时未支付订单，更新为已取消。
     * 同样使用乐观锁（status = UNPAID 条件），若支付线程先提交，
     * 则关单更新失败，保证支付优先。
     */
    @Override
    public void closeUnpaidOrders() {
        LocalDateTime deadline = LocalDateTime.now().minusMinutes(ORDER_EXPIRE_MINUTES);
        // 查询超时未支付订单
        List<VoucherOrder> unpaidList = list(new LambdaQueryWrapper<VoucherOrder>()
                .eq(VoucherOrder::getStatus, OrderStatus.UNPAID)
                .lt(VoucherOrder::getCreateTime, deadline));
        if (unpaidList.isEmpty()) {
            return;
        }
        for (VoucherOrder order : unpaidList) {
            // 乐观锁关单：仅当订单仍为 UNPAID 时才更新为 CANCELLED
            boolean success = update()
                    .set("status", OrderStatus.CANCELLED)
                    .set("update_time", LocalDateTime.now())
                    .eq("id", order.getId())
                    .eq("status", OrderStatus.UNPAID)
                    .update();
            if (success) {
                log.info("订单超时关闭，orderId={}", order.getId());
            } else {
                log.debug("订单关闭跳过（已支付），orderId={}", order.getId());
            }
        }
    }

    public void createVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        Long voucherId = voucherOrder.getVoucherId();
        // 创建锁对象
        RLock redisLock = redissonClient.getLock("lock:order:" + userId);
        // 尝试获取锁
        boolean isLock = redisLock.tryLock();
        // 判断
        if (!isLock) {
            // 获取锁失败，直接返回失败或者重试
            log.error("不允许重复下单！");
            return;
        }

        try {
            // 6.扣减库存
            boolean success = seckillVoucherService.update()
                    .setSql("stock = stock - 1") // set stock = stock - 1
                    .eq("voucher_id", voucherId).gt("stock", 0) // where id = ? and stock > 0
                    .update();
            if (!success) {
                // 扣减失败
                log.error("库存不足！");
                return;
            }
            // 7.创建订单（初始状态：未支付，记录下单时间用于定时关单扫描）
            voucherOrder.setStatus(OrderStatus.UNPAID);
            voucherOrder.setCreateTime(LocalDateTime.now());
            save(voucherOrder);
        } finally {
            // 释放锁
            redisLock.unlock();
        }
    }
}
