package com.hakimi.aviation.controller;

import com.hakimi.aviation.alipay.AlipayCallbackUtil;
import com.hakimi.aviation.common.JsonData;
import com.hakimi.aviation.model.request.order.CancelOrderRequest;
import com.hakimi.aviation.service.order.PayService;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("api/v1/pri/order")
public class OrderController {

    @Resource
    private PayService payService;

    @Resource
    private AlipayCallbackUtil alipayCallbackUtil;


    /**
     * 真实业务调用的接口 此接口需要做访问限制 不能连续多次请求
     * @param orderId 订单号
     * @return 包装好的 支付界面 HTML
     */
    @PostMapping("pay")
    public JsonData<String> payOrder(@RequestParam("order_id") Long orderId){

        String formHtml = payService.payOrder(orderId);

        return JsonData.buildSuccess(formHtml,"已收到第三方支付平台响应");
    }

    /**
     * 测试用的接口 直接返回 HTML
     * @param orderId 订单号
     * @return 支付界面的 HTML 代码
     */
    @GetMapping("pay/test")
    public String testPayOrder(@RequestParam("order_id") Long orderId){

        String formHtml = payService.payOrder(orderId);

        return formHtml;
    }

    /**
     * 支付宝回调的接口 在这里接收支付宝回调的 HTTP 请求参数
     * @param request 支付宝回调的 HTTP 请求
     * @return ！！！此接口只允许支付宝调用 同时只能给支付宝返回结果！！！  返回 "success" 通知支付宝 支付成功结束; "failure" 则是失败
     */
    @PostMapping("pay/callback")
    public String alipayCallBack(HttpServletRequest request){

        log.info("接口已被支付宝回调");

        Map<String, String> params = alipayCallbackUtil.verifyAndGetParams(request);
        if (params == null) {
            return "failure";
        }

        log.info("请求通过基本校验");

        // 状态过滤：不是支付成功的回调，直接忽略
        String tradeStatus = params.get("trade_status");
        if (!"TRADE_SUCCESS".equals(tradeStatus) && !"TRADE_FINISHED".equals(tradeStatus)) {
            return "success";
        }

        log.info("请求通过支付状态校验");

        // 提取核心业务参数
        String outTradeNo = params.get("out_trade_no");     // Hakimi-2049...
        String tradeNo = params.get("trade_no");           // 支付宝流水号
        String totalAmount = params.get("total_amount");   // 支付金额

        // 切掉前缀，拿到纯净的雪花 ID
        Long orderId = Long.parseLong(outTradeNo.replace("Hakimi-", ""));


        boolean result = payService.confirmOrder(orderId, tradeNo, totalAmount);

        // 根据处理结果，给支付宝答复
        return result ? "success" : "failure";
    }

    /**
     * 用户退款的接口 必须保障幂等性  只能对未进行支付的接口进行取消
     * 此接口没有 @LoginOptional
     * @param request DTO 只携带 订单号
     * @param servletRequest HTTP 上下文
     * @return 是否操作成功
     */
    @PostMapping("cancel")
    public JsonData<String> cancelOrder(@RequestBody CancelOrderRequest request,HttpServletRequest servletRequest){

        Integer userId = (Integer) servletRequest.getAttribute("user_id");



        return null;
    }



}
