package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.conditions.query.QueryChainWrapper;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.entity.ShopType;
import com.hmdp.entity.Voucher;
import com.hmdp.service.IChatService;
import com.hmdp.service.IShopService;
import com.hmdp.service.IShopTypeService;
import com.hmdp.service.IVoucherService;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 智能客服：接入智谱AI大模型（GLM-4-Flash）
 * - 基于 Function Calling（tools/tool_calls 协议）查询平台业务数据
 * - Redis List 存储会话记忆，仅持久化最终的自然语言轮次，避免 tool_calls 序列被截断
 */
@Slf4j
@Service
public class ChatServiceImpl implements IChatService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private IShopService shopService;
    @Resource
    private IShopTypeService shopTypeService;
    @Resource
    private IVoucherService voucherService;

    // 智谱AI API 配置（API Key 从环境变量读取）
    private static final String API_URL = "https://open.bigmodel.cn/api/paas/v4/chat/completions";
    private static final String API_KEY = System.getenv("ZHIPU_API_KEY") != null
            ? System.getenv("ZHIPU_API_KEY")
            : "your-api-key";
    private static final String MODEL = "glm-4-flash";

    // Redis 会话记忆 key 前缀
    private static final String CHAT_MEMORY_KEY = "chat:memory:";
    // 最大记忆轮次（一轮 = user + assistant 两条）
    private static final int MAX_MEMORY_ROUNDS = 10;
    // Function Calling 最大连续工具调用轮数，防止模型循环调用
    private static final int MAX_TOOL_ROUNDS = 3;

    @Override
    public Result chat(String message) {
        if (StrUtil.isBlank(message)) {
            return Result.fail("消息不能为空");
        }
        if (UserHolder.getUser() == null) {
            return Result.fail("请先登录");
        }
        Long userId = UserHolder.getUser().getId();

        try {
            // 1. 读取 Redis 会话记忆，构建 messages（system + 历史 + 当前用户消息）
            String memoryKey = CHAT_MEMORY_KEY + userId;
            List<String> memoryList = stringRedisTemplate.opsForList().range(memoryKey, 0, -1);
            JSONArray messages = buildMessages(memoryList, message);

            // 2. 调用大模型，内部完成 Function Calling 闭环
            String reply = chatWithTools(messages);

            // 3. 持久化最终的自然语言轮次到 Redis
            saveMemory(memoryKey, message, reply);
            return Result.ok(reply);
        } catch (Throwable t) {
            // 兜底：捕获含 Error 在内的所有异常，打印完整堆栈并返回可读错误
            log.error("智能客服处理失败: {}", t.toString(), t);
            return Result.fail("服务异常: " + t.getClass().getSimpleName() + " - " + t.getMessage());
        }
    }

    // ==================== Function Calling 核心流程 ====================

    /**
     * 携带 tools 调用大模型：
     * 模型若返回 tool_calls，则本地执行函数并把结果回传，循环直到模型给出自然语言回答
     */
    private String chatWithTools(JSONArray messages) {
        JSONArray tools = buildTools();
        for (int round = 0; round < MAX_TOOL_ROUNDS; round++) {
            JSONObject requestBody = new JSONObject();
            requestBody.set("model", MODEL);
            requestBody.set("messages", messages);
            requestBody.set("tools", tools);
            requestBody.set("tool_choice", "auto");
            requestBody.set("temperature", 0.7);
            requestBody.set("max_tokens", 1024);

            JSONObject responseMessage;
            try {
                String response = cn.hutool.http.HttpRequest.post(API_URL)
                        .header("Authorization", "Bearer " + API_KEY)
                        .header("Content-Type", "application/json")
                        .body(requestBody.toString())
                        .timeout(30000)
                        .execute()
                        .body();
                JSONObject jsonResponse = JSONUtil.parseObj(response);
                if (!jsonResponse.containsKey("choices")) {
                    log.error("智谱AI响应异常: {}", response);
                    return "抱歉，AI服务暂时不可用，请稍后再试。";
                }
                responseMessage = jsonResponse.getJSONArray("choices").getJSONObject(0).getJSONObject("message");
            } catch (Exception e) {
                log.error("调用智谱AI失败", e);
                return "抱歉，AI服务暂时不可用，请稍后再试。";
            }

            // 模型没有请求工具调用，说明已经给出最终自然语言回答
            JSONArray toolCalls = responseMessage.getJSONArray("tool_calls");
            if (toolCalls == null || toolCalls.isEmpty()) {
                return responseMessage.getStr("content");
            }

            // 把模型的 tool_calls 消息原样加入上下文
            messages.add(responseMessage);

            // 逐个执行本地函数，并以 role=tool 回传结果
            for (int i = 0; i < toolCalls.size(); i++) {
                JSONObject toolCall = toolCalls.getJSONObject(i);
                String toolCallId = toolCall.getStr("id");
                JSONObject function = toolCall.getJSONObject("function");
                String functionName = function.getStr("name");
                String arguments = function.getStr("arguments");

                String result;
                try {
                    result = executeFunction(functionName, arguments);
                } catch (Exception e) {
                    log.error("执行工具函数失败: name={}, args={}", functionName, arguments, e);
                    result = JSONUtil.createObj().set("error", "工具执行异常: " + e.getMessage()).toString();
                }

                JSONObject toolMessage = new JSONObject();
                toolMessage.set("role", "tool");
                toolMessage.set("tool_call_id", toolCallId);
                toolMessage.set("content", result);
                messages.add(toolMessage);
            }
        }
        log.warn("Function Calling 超过最大轮数 {}，强制结束", MAX_TOOL_ROUNDS);
        return "抱歉，处理您的请求时调用工具次数过多，请换个问法试试。";
    }

    /**
     * 定义可被模型调用的工具（JSON Schema 描述参数）
     */
    private JSONArray buildTools() {
        // 1. 查询店铺类型列表
        JSONObject queryTypes = new JSONObject();
        queryTypes.set("type", "function");
        queryTypes.set("function", new JSONObject()
                .set("name", "query_shop_types")
                .set("description", "查询平台上所有的商家/店铺分类，例如美食、KTV、酒店等。用户询问有哪些分类、有什么类型商家时调用。")
                .set("parameters", new JSONObject()
                        .set("type", "object")
                        .set("properties", new JSONObject())
                        .set("required", new JSONArray())));

        // 2. 查询店铺
        JSONObject queryShops = new JSONObject();
        queryShops.set("type", "function");
        queryShops.set("function", new JSONObject()
                .set("name", "query_shops")
                .set("description", "按店铺名称关键字和/或分类ID查询平台店铺信息（地址、评分、人均消费、营业时间），最多返回5条。")
                .set("parameters", new JSONObject()
                        .set("type", "object")
                        .set("properties", new JSONObject()
                                .set("keyword", new JSONObject()
                                        .set("type", "string")
                                        .set("description", "店铺名称关键字，例如：火锅、海底捞"))
                                .set("typeId", new JSONObject()
                                        .set("type", "integer")
                                        .set("description", "店铺分类ID，可先调用 query_shop_types 获取")))
                        .set("required", new JSONArray())));

        // 3. 查询优惠券
        JSONObject queryVouchers = new JSONObject();
        queryVouchers.set("type", "function");
        queryVouchers.set("function", new JSONObject()
                .set("name", "query_vouchers")
                .set("description", "查询平台优惠券/秒杀券列表，可按店铺ID过滤，最多返回10条。")
                .set("parameters", new JSONObject()
                        .set("type", "object")
                        .set("properties", new JSONObject()
                                .set("shopId", new JSONObject()
                                        .set("type", "integer")
                                        .set("description", "店铺ID，可选")))
                        .set("required", new JSONArray())));

        JSONArray tools = new JSONArray();
        tools.add(queryTypes);
        tools.add(queryShops);
        tools.add(queryVouchers);
        return tools;
    }

    /**
     * 根据模型给出的函数名和参数执行本地数据库查询，返回 JSON 字符串
     */
    private String executeFunction(String name, String argumentsJson) {
        JSONObject args = StrUtil.isBlank(argumentsJson)
                ? new JSONObject()
                : JSONUtil.parseObj(argumentsJson);
        switch (name) {
            case "query_shop_types":
                return doQueryShopTypes();
            case "query_shops":
                return doQueryShops(args.getStr("keyword"), args.getLong("typeId"));
            case "query_vouchers":
                return doQueryVouchers(args.getLong("shopId"));
            default:
                return JSONUtil.createObj().set("error", "未知工具: " + name).toString();
        }
    }

    // ==================== 具体工具实现（查库） ====================

    private String doQueryShopTypes() {
        List<ShopType> types = shopTypeService.query().orderByAsc("sort").list();
        JSONArray arr = new JSONArray();
        for (ShopType t : types) {
            arr.add(new JSONObject().set("id", t.getId()).set("name", t.getName()));
        }
        return JSONUtil.createObj().set("total", arr.size()).set("types", arr).toString();
    }

    private String doQueryShops(String keyword, Long typeId) {
        QueryChainWrapper<Shop> query = shopService.query();
        if (StrUtil.isNotBlank(keyword)) {
            query.like("name", keyword.trim());
        }
        if (typeId != null) {
            query.eq("type_id", typeId);
        }
        List<Shop> shops = query.last("LIMIT 5").list();

        JSONArray arr = new JSONArray();
        for (Shop s : shops) {
            arr.add(new JSONObject()
                    .set("id", s.getId())
                    .set("name", s.getName())
                    .set("typeId", s.getTypeId())
                    .set("area", s.getArea())
                    .set("address", s.getAddress())
                    // 评分在库中乘10存储，返回时还原
                    .set("score", s.getScore() == null ? null : s.getScore() / 10.0)
                    .set("avgPrice", s.getAvgPrice())
                    .set("openHours", s.getOpenHours()));
        }
        if (arr.isEmpty()) {
            return JSONUtil.createObj().set("total", 0).set("message", "未找到符合条件的店铺").toString();
        }
        return JSONUtil.createObj().set("total", arr.size()).set("shops", arr).toString();
    }

    private String doQueryVouchers(Long shopId) {
        QueryChainWrapper<Voucher> query = voucherService.query();
        if (shopId != null) {
            query.eq("shop_id", shopId);
        }
        List<Voucher> vouchers = query.last("LIMIT 10").list();

        JSONArray arr = new JSONArray();
        for (Voucher v : vouchers) {
            arr.add(new JSONObject()
                    .set("id", v.getId())
                    .set("shopId", v.getShopId())
                    .set("title", v.getTitle())
                    .set("subTitle", v.getSubTitle())
                    // 金额按分存储，返回元
                    .set("payValue", v.getPayValue() == null ? null : v.getPayValue() / 100.0)
                    .set("actualValue", v.getActualValue() == null ? null : v.getActualValue() / 100.0)
                    .set("rules", v.getRules()));
        }
        if (arr.isEmpty()) {
            return JSONUtil.createObj().set("total", 0).set("message", "暂无可用优惠券").toString();
        }
        return JSONUtil.createObj().set("total", arr.size()).set("vouchers", arr).toString();
    }

    // ==================== 会话记忆 ====================

    private JSONArray buildMessages(List<String> memoryList, String currentMessage) {
        JSONArray messages = new JSONArray();

        JSONObject systemMsg = new JSONObject();
        systemMsg.set("role", "system");
        systemMsg.set("content", "你是星隅便民服务中台的智能客服助手。"
                + "平台提供商家信息查询、优惠券/秒杀券查询等服务。"
                + "当用户询问平台相关业务数据时，请调用提供的工具查询后再回答，不要编造数据；"
                + "工具返回金额单位为元、评分为1-5分。"
                + "请用简洁友好的语言回答。当前时间：" + java.time.LocalDateTime.now());
        messages.add(systemMsg);

        // 历史记忆只包含纯 user/assistant 文本轮次，天然是合法的消息序列
        if (memoryList != null) {
            for (String memory : memoryList) {
                if (StrUtil.isNotBlank(memory)) {
                    messages.add(JSONUtil.parseObj(memory));
                }
            }
        }

        JSONObject userMsg = new JSONObject();
        userMsg.set("role", "user");
        userMsg.set("content", currentMessage);
        messages.add(userMsg);
        return messages;
    }

    /**
     * 只持久化最终的自然语言问答轮次，保证历史序列不含孤立的 tool_calls
     */
    private void saveMemory(String key, String userMsg, String aiReply) {
        stringRedisTemplate.opsForList().rightPush(key,
                new JSONObject().set("role", "user").set("content", userMsg).toString());
        stringRedisTemplate.opsForList().rightPush(key,
                new JSONObject().set("role", "assistant").set("content", aiReply).toString());
        stringRedisTemplate.opsForList().trim(key, -MAX_MEMORY_ROUNDS * 2L, -1);
        stringRedisTemplate.expire(key, 2, TimeUnit.HOURS);
    }
}
