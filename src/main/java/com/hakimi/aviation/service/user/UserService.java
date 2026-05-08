package com.hakimi.aviation.service.user;

import com.hakimi.aviation.model.request.user.LoginRequest;
import com.hakimi.aviation.model.request.user.RegisterRequest;
import com.hakimi.aviation.model.request.user.SendCodeRequest;

public interface UserService {

    /**
     * 发送邮箱验证码的方法
     * @param request DTO 包含邮箱号
     * @return 返回是否发送成功（1 OK）
     */
    int sendVerifyCode(SendCodeRequest request);

    int register(RegisterRequest request);

    String login(LoginRequest request);

}
