package com.hakimi.aviation.service.order.impl;

import com.hakimi.aviation.config.RedisKey;
import com.hakimi.aviation.entity.TicketOrder;
import com.hakimi.aviation.enums.BizCodeEnum;
import com.hakimi.aviation.exception.BizException;
import com.hakimi.aviation.mapper.OrderMapper;
import com.hakimi.aviation.model.request.order.CancelOrderRequest;
import com.hakimi.aviation.model.vo.CancelOrderVO;
import com.hakimi.aviation.service.order.OrderService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class OrderServiceImpl implements OrderService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private OrderMapper orderMapper;

    @Autowired
    @Qualifier("rollbackStockScript")
    private DefaultRedisScript<Long> rollbackScript;


    @Override
    @Transactional(rollbackFor = Exception.class)
    public CancelOrderVO cancelOrder(CancelOrderRequest request, Long userId) {

        Long orderId = request.getOrderId();

        Long removeResult = stringRedisTemplate.opsForSet().remove(
                RedisKey.ORDER_UNPAID_KEY + userId,
                orderId.toString()
        );

        if(removeResult == null || removeResult == 0){
            //NOTE 如果删除失败（已被删除）或者为空（异常） 则此次回调已经失败 可能订单被取消、已支付（非正常情况）或者是重复回调
            throw new BizException(BizCodeEnum.ORDER_MISS_OR_EXPIRED);
        }

        int updateRows = orderMapper.cancelUnpaidOrder(orderId);
        if(updateRows == 0){
            //NOTE 到这里说明取消失败：订单不存在 或 订单状态已经为 CANCELLED/不为 UNPAID 直接报告此（未支付）订单不存在
            throw new BizException(BizCodeEnum.ORDER_MISS_OR_EXPIRED);
        }

        Long flightId;
        Integer seatOffset;

        List<Object> hashKeys = new ArrayList<>();
        hashKeys.add("flightId");
        hashKeys.add("seat_offset");
        //到这里说明修改成功，开始释放 Redis 库存 与 座位, 先从 Redis 订单快照获取所需参数，数据库查库兜底
        List<Object> snapshotValues = stringRedisTemplate.opsForHash().multiGet(
                RedisKey.ORDER_SNAPSHOT_KEY + orderId,
                hashKeys
        );

        Object flightIdObj = snapshotValues.get(0);
        Object seatOffsetObj = snapshotValues.get(1);
        if(flightIdObj == null || seatOffsetObj == null){
            //降级到数据库
            log.warn("订单:{}的快照信息丢失 正在降级到数据库查询",orderId);
            TicketOrder ticketOrder = orderMapper.selectById(orderId);
            if(ticketOrder == null){
                throw new BizException(BizCodeEnum.ORDER_MISS_OR_EXPIRED);
            }
            flightId = ticketOrder.getFlightId();
            seatOffset = ticketOrder.getSeatOffset();
        }

        else {
            // 先转成 String，再用 parse 方法转换成对应的数值类型
            flightId = Long.parseLong(String.valueOf(flightIdObj));
            seatOffset = Integer.parseInt(String.valueOf(seatOffsetObj));
        }

        String unpaidKey = RedisKey.ORDER_UNPAID_KEY + userId;

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

        return parseToCancelOrderVO(orderId);
    }

    private CancelOrderVO parseToCancelOrderVO(Long orderId){

        CancelOrderVO cancelOrderVO = new CancelOrderVO();
        cancelOrderVO.setOrderId(orderId);
        cancelOrderVO.setCancelTime(LocalDateTime.now());
        
        return cancelOrderVO;
    }

}
