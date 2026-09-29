# 星隅便民服务中台

> 基于 SpringBoot + Redis + Kafka + Caffeine + 大模型的本地生活服务平台后端

## 项目简介

星隅便民服务中台为用户提供商家信息查询、秒杀优惠券、智能客服等功能，同时帮助商家推广优惠信息。通过多级缓存、消息队列、分布式锁等技术手段，保障高并发场景下的系统稳定性与数据一致性。

## 技术栈

| 层 | 技术 |
|----|------|
| 框架 | SpringBoot 2.7.2 |
| ORM | MyBatis-Plus |
| 缓存 | Redis 7.0 + Caffeine（本地缓存） |
| 消息队列 | Kafka |
| 数据库 | MySQL 8.0 |
| 限流 | Redis ZSet 滑动窗口 + AOP 注解 |
| 大模型 | 智谱 AI（GLM-4-Flash） |
| 工具 | Hutool、Lombok、Maven |

## 核心功能

### 1. 用户登录与 Token 管理
- 短信验证码登录（Redis 存储验证码）
- Token 双拦截器刷新机制，避免 Token 过期

### 2. 商铺缓存策略
- **缓存穿透**：缓存空值，过期时间 2 分钟
- **缓存击穿**：逻辑过期方案，重建过程加互斥锁
- **缓存雪崩**：随机 TTL 打散过期时间
- **缓存一致性**：更新数据库后删除缓存，删除失败通过 Kafka 补偿重试

### 3. 滑动窗口限流
- 基于 Redis ZSet 实现滑动窗口计数
- AOP + 自定义注解，支持 IP / 用户 / 方法多维度限流

### 4. 秒杀下单优化
- **Lua 脚本预检**：库存校验 + 一人一单判断，保证原子性
- **Kafka 异步处理**：下单成功后异步消费扣减库存和生成订单
- **本地缓存**：秒杀信息 Caffeine 本地缓存，减少 Redis 压力

### 5. 订单状态机与定时关单
- 订单状态：未支付 → 已支付 → 已核销 → 已取消
- Spring Task 每分钟扫描超时订单，超过 30 分钟未支付自动关闭
- 支付和关单使用乐观锁（`WHERE status = 1`），防止并发冲突

### 6. 智能客服
- 接入智谱 AI 大模型（GLM-4-Flash）
- Redis List 存储用户会话记忆（最近 10 轮，TTL 2 小时）
- Function Calling：支持查询店铺信息、优惠券列表、店铺类型等业务数据

### 7. 其他
- 签到：Redis BitMap 实现，支持连续签到统计
- UV 统计：Redis HyperLogLog 实现
- 好友关注：Redis Set 取交集实现共同关注
- 附近商铺：Redis GEO 地理位置搜索 + 距离排序

## 项目结构

```
src/main/java/com/hmdp/
├── config/           # 配置类（Kafka、Redis 等）
├── consumer/         # Kafka 消费者（秒杀下单、缓存补偿）
├── controller/       # Web 接口层
├── dto/              # 数据传输对象
├── entity/           # 实体类
├── limiter/          # 限流模块（注解 + AOP + Lua）
├── mapper/           # MyBatis-Plus Mapper
├── service/          # 业务层
│   ├── cache/        # 多级缓存实现
│   └── impl/         # 业务实现
├── task/             # 定时任务（订单关单）
└── utils/            # 工具类
```

## 快速启动

### 环境要求
- JDK 8+
- MySQL 8.0
- Redis 7.0
- Kafka 3.x

### 1. 创建数据库

```sql
CREATE DATABASE dingping DEFAULT CHARACTER SET utf8mb4;
```

导入项目根目录下的 SQL 文件初始化表结构。

### 2. 配置环境变量

```bash
# MySQL 密码（可选，默认 root）
export MYSQL_PASSWORD=your_password

# 智谱 AI API Key（智能客服功能需要）
export ZHIPU_API_KEY=your_api_key
```

### 3. 修改配置

编辑 `src/main/resources/application.yaml`：

```yaml
spring:
  datasource:
    url: jdbc:mysql://127.0.0.1:3306/dingping?useSSL=false&serverTimezone=UTC
    username: root
    password: ${MYSQL_PASSWORD:root}
  redis:
    host: localhost
    port: 6379
  kafka:
    bootstrap-servers: localhost:9092
```

### 4. 启动

```bash
mvn clean package -DskipTests
java -jar target/hm-dianping-0.0.1-SNAPSHOT.jar
```

服务默认监听 **8081** 端口。

## 主要接口

| 接口 | 说明 |
|------|------|
| `POST /user/code?phone=` | 发送登录验证码 |
| `POST /user/login` | 短信登录 |
| `GET /shop-type/list` | 查询店铺类型 |
| `GET /shop/of/type` | 按类型查询店铺 |
| `POST /voucher/seckill/{id}` | 秒杀下单 |
| `POST /voucher-order/pay/{id}` | 支付订单 |
| `POST /chat` | 智能客服 |

## 关键设计

### 缓存一致性方案

```
更新请求 → 更新数据库 → 删除缓存
                        ↓ 失败
                    发送 Kafka 消息
                        ↓
                    消费者重试删除（最多 3 次）
                        ↓
                    TTL 兜底自动过期
```

### 秒杀流程

```
用户请求 → Lua 脚本预检（库存+一人一单）
              ↓
        Redis 扣减库存
              ↓
        发送 Kafka 消息
              ↓
        消费者异步生成订单 + MySQL 乐观锁扣减
```

### 限流注解使用

```java
@RateLimiter(key = "seckill", limit = 10, window = 60)
@PostMapping("/seckill/{id}")
public Result seckillVoucher(@PathVariable Long id) {
    return voucherOrderService.seckillVoucher(id);
}
```

## License

本项目仅供学习交流使用。
