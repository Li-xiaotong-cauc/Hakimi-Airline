# 哈基米航空 API 文档

本文档描述了哈基米航空后端系统对外提供的 RESTful API 接口。

## 基础说明

- **Base URL**: `/api/v1`
- **通信协议**: HTTP/HTTPS
- **数据交互格式**: `application/json`
- **全局统一响应结构**:
  ```json
  {
    "code": 200,      // 业务状态码 (200=成功，其他=各类业务错误)
    "msg": "成功",    // 提示信息
    "data": {}        // 具体业务数据（可选）
  }
  ```
- **鉴权说明**: 大部分接口需要登录，在 Header 中传递：
  `token: hajimi{your_jwt_token_here}`

---

## 1. 用户模块 (User)

### 1.1 用户注册
- **URL**: `/pub/user/register`
- **Method**: `POST`
- **鉴权**: 否
- **请求体 (Body)**:
  ```json
  {
    "email": "test@example.com",
    "password": "your_password",
    "userName": "Edison"
  }
  ```
- **响应示例**:
  ```json
  {
    "code": 200,
    "msg": "注册成功",
    "data": { ... 用户信息 ... }
  }
  ```

### 1.2 用户登录
- **URL**: `/pub/user/login`
- **Method**: `POST`
- **鉴权**: 否
- **请求体 (Body)**:
  ```json
  {
    "email": "test@example.com",
    "password": "your_password"
  }
  ```
- **响应示例**:
  ```json
  {
    "code": 200,
    "msg": "登录成功",
    "data": "hajimieyJhbGciOiJIUzI1NiJ9..." // 颁发的 JWT
  }
  ```

---

## 2. 航班搜票模块 (Flight)

### 2.1 高性能航班搜索 (推荐)
- **URL**: `/pri/flight/search`
- **Method**: `POST`
- **鉴权**: 是 (`@LoginOptional`，即可以不登录，但登录后可能有更多权限)
- **描述**: 基于 Elasticsearch 与 Redis 缓存的高性能聚合搜索，包含实时库存信息。
- **请求体 (Body)**:
  ```json
  {
    "deptCity": "北京",
    "arrCity": "上海",
    "flightDate": "2024-05-01",
    "sortType": 1   // 1: 按起飞时间最早排序, 2: 按价格从低到高排序
  }
  ```
- **响应示例**:
  ```json
  {
    "code": 200,
    "msg": "航班查询成功",
    "data": [
      {
        "id": 888,
        "flightNo": "HA1001",
        "deptTime": "2024-05-01 08:00:00",
        "totalPrice": 1200.00,
        "availableSeats": 50 // 实时库存
      }
    ]
  }
  ```

### 2.2 数据库降级搜索 (兜底)
- **URL**: `/pri/flight/search_flight`
- **Method**: `POST`
- **鉴权**: 是 (`@LoginOptional`)
- **描述**: 直接查询 MySQL 数据库，用于中间件宕机时的紧急逃生。参数同上。

---

## 3. 预订与交易模块 (Booking)

### 3.1 机票抢票预订
- **URL**: `/pri/flight/booking`
- **Method**: `POST`
- **鉴权**: 是 (必须提供有效的 token)
- **描述**: 核心抢票接口，完成防重校验、扣减库存、原子占座，并生成 15 分钟待支付订单。
- **请求体 (Body)**:
  ```json
  {
    "flightId": 888,
    "userId": 1001,  // 需与 token 中的 userId 一致
    "seatPrefer": "window" // 偏好: window(靠窗), aisle(过道), middle(中间)
  }
  ```
- **响应示例**:
  ```json
  {
    "code": 200,
    "msg": "预订成功，请在15分钟内完成支付",
    "data": {
      "orderNo": "Hakimi-171500001",
      "flightId": 888,
      "totalPrice": 1200.00,
      "exactSeat": "12A", // 分配的具体物理座位
      "status": "UNPAID"
    }
  }
  ```

---

## 4. 支付模块 (Pay)

### 4.1 发起支付宝支付
- **URL**: `/pri/order/pay/alipay` (示例路径，根据 Controller 实际定义为准)
- **Method**: `GET / POST`
- **鉴权**: 是
- **参数**: 
  - `orderId` (Long): 需要支付的订单 ID
- **描述**: 该接口会返回一段由支付宝 SDK 生成的 HTML `<form>` 表单代码。前端接收后直接渲染到页面上，即可跳转至支付宝收银台。

### 4.2 支付宝异步回调通知
- **URL**: `/pri/order/pay/callback`
- **Method**: `POST`
- **鉴权**: 否 (由支付宝网关请求)
- **描述**: 接收支付宝支付成功后的异步通知，验证签名，进行数据库最终一致性对账并释放相关缓存锁。