package com.hmdp.utils;

/**
 * 订单状态机常量
 */
public class OrderStatus {
    /** 未支付（初始状态） */
    public static final int UNPAID = 1;
    /** 已支付 */
    public static final int PAID = 2;
    /** 已核销 */
    public static final int USED = 3;
    /** 已取消（超时关单） */
    public static final int CANCELLED = 4;
}
