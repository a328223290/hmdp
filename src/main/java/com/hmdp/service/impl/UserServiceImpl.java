package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpSession;

import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {


    private final UserMapper userMapper;

    public UserServiceImpl(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    @Override
    public Result sendCode(String phone, HttpSession session) {
        // 1. 校验手机号
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号格式不正确");
        }
        // 2. 生成验证码
        String code = RandomUtil.randomNumbers(6);
        // 3. 保存验证码到Session
        session.setAttribute("code", code);
        // 4. 发送验证码
        log.debug("成功发送验证码：" + code);
        // 5. 返回OK
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        log.debug("[login] -> loginForm: " + loginForm);
        // 1. 校验手机和验证码
        if (loginForm.getPhone() == null || RegexUtils.isPhoneInvalid(loginForm.getPhone())) {
            return Result.fail("手机格式不正确");
        }
        if (loginForm.getCode() == null || RegexUtils.isCodeInvalid(loginForm.getCode())) {
            return Result.fail("验证码格式不正确");
        }
        // 2. 校验验证码
        String code = (String) session.getAttribute("code");
        if (code == null || !code.equals(loginForm.getCode())) {
            return Result.fail("验证码不正确");
        }
        // 3. 根据手机号查询用户，判断用户是否存在
        User user = query().eq("phone", loginForm.getPhone()).one();
        // TODO: 采用lambdaQuery()会报错，很奇怪，暂不清楚原因，可能是mybatis-plus的bug？
//        User user = lambdaQuery().eq(User::getPhone, loginForm.getPhone()).one();
        if (user == null) {
            // 3.1 如果用户不存在，则创建用户
            user = createUserWithPhone(loginForm.getPhone());
        }
        // 4. 保存用户DTO至session
        session.setAttribute("user", BeanUtil.copyProperties(user, UserDTO.class));
        // 5. 返回OK
        return Result.ok();
    }

    public User createUserWithPhone(String phone) {
        // 随机生成一个姓名
        User user = User.builder()
                .phone(phone)
                .nickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10))
                .build();
        this.save(user);
        return user;
    }
}
