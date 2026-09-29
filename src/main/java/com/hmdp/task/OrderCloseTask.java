package com.hmdp.task;

import com.hmdp.service.IVoucherOrderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

/**
 * 未支付订单到期自动关闭任务
 * 每分钟扫描一次超时未支付订单，通过乐观锁将其状态改为已取消，
 * 与支付接口并发时，支付/关单二者只能成功一个。
 */
@Slf4j
@Component
public class OrderCloseTask {

    @Resource
    private IVoucherOrderService voucherOrderService;

    /**
     * 每分钟执行一次关单扫描
     */
    @Scheduled(cron = "0 * * * * ?")
    public void closeUnpaidOrders() {
        log.debug("开始扫描超时未支付订单...");
        voucherOrderService.closeUnpaidOrders();
    }
}
