package com.hmdp.utils;

import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

public class LoginInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 1. 获取Session
        HttpSession session = request.getSession();
        // 2. 从Session中获取用户
        User user = (User) session.getAttribute("user");
        // 3. 判断用户是否存在
        // 3.1 用户不存在，拦截
        if (user == null) {
            // 401表示未授权
            response.setStatus(401);
            return false;
        }
        // 3.2 用户存在，保存用户到ThreadLocal
        UserDTO userDTO = UserDTO.builder()
                .id(user.getId())
                .nickName(user.getNickName())
                .icon(user.getIcon())
                .build();
        UserHolder.saveUser(userDTO);
        // 4. 放行
        return true;
    }

    @Override
    public void postHandle(HttpServletRequest request, HttpServletResponse response, Object handler, ModelAndView modelAndView) throws Exception {
        // 及时删除remove ThreadLocal的数据，防止内存泄漏
        UserHolder.removeUser();
    }
}
