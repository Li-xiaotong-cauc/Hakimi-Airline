package com.hakimi.aviation.consumer;

import com.hakimi.aviation.config.RedisKey;
import com.hakimi.aviation.entity.TicketOrder;
import com.hakimi.aviation.mapper.OrderMapper;
import com.hakimi.aviation.message.config.RabbitMQConfig;
import com.hakimi.aviation.message.order.CancelOrderMessage;
import com.rabbitmq.client.Channel;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;

@Slf4j
@Component
public class OrderConsumer {

    @Resource
    private OrderMapper orderMapper;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    @Qualifier("rollbackStockScript")
    private DefaultRedisScript<Long> rollbackScript;

    /**
     * 监听正常订单创建队列
     * 注意：这里的常量 RabbitMQConfig.FLIGHT_BOOKING_ORDER_QUEUE 需要你在配置类里对齐
     */
    @RabbitListener(queues = RabbitMQConfig.FLIGHT_BOOKING_ORDER_QUEUE)
    public void handleOrderCreateMessage(TicketOrder ticketOrder,
                                         Channel channel,
                                         @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) throws IOException {

        long orderId = ticketOrder.getId();
        log.info(">>> 接收到异步落库消息，准备插入订单: {}", orderId);

        try {

            orderMapper.insert(ticketOrder);

            //数据库落盘成功后，手动向 MQ 发送 ACK 确认
            // 第二个参数 false 代表只确认当前这条消息，不批量确认
            channel.basicAck(deliveryTag, false);
            log.info(">>> 订单 {} 异步落库成功，已 ACK 回执", orderId);

        } catch (DuplicateKeyException e) {
            //幂等性防御：主键冲突说明是重复消费
            log.warn(">>> 订单 {} 发生主键冲突，触发幂等防御，视为消费成功并直接 ACK", orderId);
            //视为成功，直接告诉 MQ 删掉这条重复的消息
            channel.basicAck(deliveryTag, false);

        } catch (Exception e) {
            //其他未知异常（如数据库宕机、字段超长等）
            log.error(">>> 订单 {} 异步落库失败，进入重试机制: {}", orderId, e.getMessage());
            // 触发 NACK，第三个参数 requeue = true 表示让消息重新回到队列排队，等待下次重试
            // 配合指数退避重试策略，按照 1s, 2s, 4s 的间隔重试
            channel.basicNack(deliveryTag, false, true);
        }
    }

    /**
     * 监听死信传递队列的消费者 负责判断支付状态、回滚库存、修改数据库订单状态
     * @param cancelOrderMessage 消息体
     * @param channel MQ 通道 负责手动确认消费
     * @param deliveryTag 上下文
     * @throws IOException 主要由数据库抛出
     */
    @RabbitListener(queues = RabbitMQConfig.ORDER_CANCEL_QUEUE)
    public void handleOrderCancelMessage(
            CancelOrderMessage cancelOrderMessage,
            Channel channel,
            Message message,
            @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) throws IOException {

        Long userId = cancelOrderMessage.getUserId();
        Long flightId = cancelOrderMessage.getFlightId();
        Long orderId = cancelOrderMessage.getOrderId();
        Integer seatOffset = cancelOrderMessage.getSeatOffset();
        //是否为重试消息
        Boolean isRedelivered = message.getMessageProperties().isRedelivered();

        String unpaidKey = RedisKey.ORDER_UNPAID_KEY + userId;

        try{
            //消费者拿到消息，首先确认未支付状态是否还存在
            //Boolean keyExists = stringRedisTemplate.hasKey(unpaidKey); // 探针 先判断缓存是否过期
            Long removeResult = stringRedisTemplate
                    .opsForSet()
                    .remove(unpaidKey,String.valueOf(orderId));

            if (!isRedelivered && Long.valueOf(0).equals(removeResult)) {
                //NOTE delete unpaid 可以确定 此时订单已被取消或已支付
                // isRedelivered 可以确定 当前消息是否是异常重试的消息 防止部分失败导致的数据不一致 如果是必然异常 短暂的部分失败是不可避免的，但可以通过死信队列记录排查
                log.info(">>> 订单 {} 确认为已支付状态，或当前为重复消息，丢弃死信", orderId);
                channel.basicAck(deliveryTag, false);
                return;

            }

            //NOTE 到这里 可能是正常取消，也可能是异常重试，需要数据库乐观更新
            //乐观更新数据库 将数据库中的订单状态修改为“CANCELED” 这里只对status为 “UNPAID”的订单进行修改 避免将正常订单取消
            int updatedRows = orderMapper.cancelUnpaidOrder(orderId);
            if(updatedRows == 0 && !isRedelivered){
                //受影响的行数为0且非重试的消息 说明这是一条重复的消息 确认消费后直接丢弃即可
                channel.basicAck(deliveryTag, false);
                return;
            }
            //TODO 此时的Lua已经不用再删除 unpaid 记录了 但不影响 以后可以考虑去掉这个逻辑
            //回滚库存、释放座位位图 并删除相应的记录 用户之后的操作将不会受到影响
            Long rollbackResult = stringRedisTemplate.execute(
                    rollbackScript,
                    List.of(
                            RedisKey.ROUTE_FLIGHT + flightId,      // KEYS[1]
                            RedisKey.ORDER_NOT_FINISH_KEY + userId,// KEYS[2]
                            unpaidKey                              // KEYS[3]
                    ),
                    String.valueOf(orderId),                   // ARGV[1]: 订单ID，用于清理未支付Set
                    String.valueOf(flightId),                  // ARGV[2]: 航班ID，用于清理行程防重Set
                    "1",                                        // ARGV[3]: 退回的票数，传字符串 "1"
                    String.valueOf(seatOffset)
            );

            //结束 手动确认
            channel.basicAck(deliveryTag, false);
            log.info(">>> 订单 {} 超时取消流程全部执行完毕！", orderId);

        }catch(Exception e){
            log.error(">>> 订单 {} 超时取消处理异常，重新入队等待重试: {}", orderId, e.getMessage());
            // 遇到数据库宕机等异常，NACK 并重试
            channel.basicNack(deliveryTag, false, true);
        }

    }

}
