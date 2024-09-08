package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.BooleanUtil;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements ILock{
    // name标识服务名称
    private String name;
    private StringRedisTemplate stringRedisTemplate;
    private final String lockPrefix = "lock:";
    // UUID用来标识不同集群
    private final String ID_prefix = UUID.randomUUID().toString(true) + "_";
    // lua脚本对象
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;
    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }

    public SimpleRedisLock(String name, StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.name = name;
    }

    @Override
    public boolean tryLock(long timeoutSecs) {
        // 获取线程标识，注意这个线程表示只能保证同一个JVM下唯一，不能保证集群下唯一
        String threadId = ID_prefix + Thread.currentThread().getId();
        Boolean success = stringRedisTemplate.opsForValue().setIfAbsent(lockPrefix + name, threadId, timeoutSecs, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(success);
    }

    @Override
    // 为了保证原子性，该用lua脚本进行解锁操作
    public void unlock() {
        // TODO：编译器在运行这个语句的时候不会拆成几句吗？
        stringRedisTemplate.execute(
                UNLOCK_SCRIPT,
                Collections.singletonList(lockPrefix + name),
                ID_prefix + Thread.currentThread().getId());
    }

//    @Override
//    public void unlock() {
//        // 获取线程标识
//        String threadId = ID_prefix + Thread.currentThread().getId();
//        // 获取锁中的标识并判断是否一致，防止出现锁误删的情况
//        String lockId = stringRedisTemplate.opsForValue().get(lockPrefix + name);
//        if (threadId.equals(lockId)) {
//            // 如果标识一致，则释放锁
//            stringRedisTemplate.delete(lockPrefix + name);
//        }
//    }
}
