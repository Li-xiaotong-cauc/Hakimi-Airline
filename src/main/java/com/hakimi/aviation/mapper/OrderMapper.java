package com.hakimi.aviation.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hakimi.aviation.entity.TicketOrder;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface OrderMapper extends BaseMapper<TicketOrder> {

    List<Long> getOrderHistory(@Param("user_id") Long userId);

    int cancelUnpaidOrder(@Param("order_id") Long orderId);

    int updateStatusToPaid(@Param("order_id") Long orderId, @Param("pay_trade_no") String tradeNo);

    int updateStatusToRefunding(@Param("order_id") Long orderId,@Param("user_id") Long userId);

    int updateStatusToRefunded(@Param("order_id") Long orderId,@Param("user_id") Long userId);

    //退款消息发送失败时的补偿：将 REFUNDING 回滚为 PAID，允许用户重试
    int revertRefundingToPaid(@Param("order_id") Long orderId,@Param("user_id") Long userId);

    String selectStatusByOrderId(@Param("order_id") Long orderId);

    Integer selectSeatOffsetById(@Param("order_id") Long orderId);

}
