package com.hakimi.aviation.service.order;

import com.hakimi.aviation.model.request.order.CancelOrderRequest;
import com.hakimi.aviation.model.request.order.RefundRequest;
import com.hakimi.aviation.model.vo.CancelOrderVO;
import com.hakimi.aviation.model.vo.OrderRefundVO;

public interface OrderService {

    CancelOrderVO cancelOrder(CancelOrderRequest request, Long userId);

    OrderRefundVO refundOrder(RefundRequest request, Long userId);

    void finishRefund(Long orderId, Long userId, Long flightId, int ticketCount);

}
