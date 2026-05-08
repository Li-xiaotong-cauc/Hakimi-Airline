package com.hakimi.aviation.model.vo;

import lombok.Data;

@Data
public class TicketOrderVO {

    private String orderNo;

    private Long flightId;

    private String passengerName;
    //确定的座位号 例如：12A
    private String exactSeat;

    private java.math.BigDecimal totalPrice;

    private String status;

    private int isFinished;

    private java.time.LocalDateTime createdAt;
}
