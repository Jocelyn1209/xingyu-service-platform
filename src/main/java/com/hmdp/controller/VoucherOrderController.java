package com.hmdp.controller;


import com.hmdp.dto.Result;
import com.hmdp.service.IVoucherOrderService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 * @author 虎哥
 */
@RestController
@RequestMapping("/voucher-order")
public class VoucherOrderController {

    @Resource
    private IVoucherOrderService voucherOrderService;

    @PostMapping("seckill/{id}")
    public Result seckillVoucher(@PathVariable("id") Long voucherId) {
        return voucherOrderService.seckillVoucher(voucherId);
    }

    /**
     * 支付订单（乐观锁，防与定时关单并发冲突）
     * @param id 订单id
     * @param payType 支付方式：1余额支付 2支付宝 3微信
     */
    @PostMapping("pay/{id}")
    public Result payVoucherOrder(@PathVariable("id") Long id,
                                  @org.springframework.web.bind.annotation.RequestParam(value = "payType", defaultValue = "1") Integer payType) {
        return voucherOrderService.payVoucherOrder(id, payType);
    }
}
