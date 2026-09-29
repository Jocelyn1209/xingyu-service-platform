package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IVoucherOrderService extends IService<VoucherOrder> {

    Result seckillVoucher(Long voucherId);

    void createVoucherOrder(VoucherOrder voucherOrder);

    /**
     * 订单支付，使用乐观锁防止并发关单和支付冲突
     */
    Result payVoucherOrder(Long orderId, Integer payType);

    /**
     * 定时关单：将超时未支付订单状态改为已取消
     */
    void closeUnpaidOrders();
}
