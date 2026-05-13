package com.hakimi.aviation.service.order;

import com.hakimi.aviation.model.request.order.CancelOrderRequest;
import com.hakimi.aviation.model.vo.CancelOrderVO;

public interface OrderService {

    CancelOrderVO cancelOrder(CancelOrderRequest request, Long userId);

}
