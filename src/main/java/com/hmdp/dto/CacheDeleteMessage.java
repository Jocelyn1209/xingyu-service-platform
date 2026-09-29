package com.hmdp.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 缓存删除补偿消息
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CacheDeleteMessage implements Serializable {
    private static final long serialVersionUID = 1L;

    /**
     * 待删除的缓存 key
     */
    private String cacheKey;

    /**
     * 重试次数（首次发送为 0）
     */
    private int retryCount;
}
