package com.hmdp.controller;

import com.hmdp.dto.ChatRequest;
import com.hmdp.dto.Result;
import com.hmdp.service.IChatService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;

/**
 * 智能客服接口
 */
@RestController
@RequestMapping("/chat")
public class ChatController {

    @Resource
    private IChatService chatService;

    @PostMapping
    public Result chat(@RequestBody ChatRequest request) {
        return chatService.chat(request.getMessage());
    }
}
