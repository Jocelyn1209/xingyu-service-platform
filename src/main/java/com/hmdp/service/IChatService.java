package com.hmdp.service;

import com.hmdp.dto.Result;

/**
 * 智能客服服务接口
 */
public interface IChatService {

    /**
     * 处理用户聊天消息
     * @param message 用户输入
     * @return AI 回复
     */
    Result chat(String message);
}
