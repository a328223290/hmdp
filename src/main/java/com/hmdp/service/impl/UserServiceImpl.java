package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
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

    @Resource
    private StringRedisTemplate stringRedisTemplate;
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
        // 采用分布式session
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone, code, LOGIN_CODE_TTL, TimeUnit.MINUTES);

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
        String code = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + loginForm.getPhone());
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
        // 4.1 生成token
        String token = UUID.randomUUID().toString();
        // 4.2 保存用户DTO
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        // 这里还蛮复杂的...因为userDTO中包含非String类型的属性，因此映射到map的时候需要将所有属性的值转为String类型。
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                CopyOptions.create().ignoreNullValue().setFieldValueEditor((key, val) -> val.toString()));
        // 4.3 保存至redis并设置有效期
        stringRedisTemplate.opsForHash().putAll(LOGIN_USER_KEY + token, userMap);
        stringRedisTemplate.expire(LOGIN_USER_KEY + token, LOGIN_USER_TTL, TimeUnit.MINUTES);
        // 5. 返回OK
        return Result.ok(token);
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
