package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;


@Component
// 用来生成唯一id的工具类
public class RedisIdWorker {
    /*
        开始时间戳，从2024-01-01开始
     */
    private final long BEGIN_TIMESTAMP = 1704067200L;
    /*
        序列号的位数
     */
    private final int COUNT_BITS = 32;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

//    public RedisIdWorker(StringRedisTemplate stringRedisTemplate) {
//        this.stringRedisTemplate = stringRedisTemplate;
//    }

    public long nextId(String keyPrefix) {
        // 1. 生成时间戳
        LocalDateTime now = LocalDateTime.now();
        long nowSeconds = now.toEpochSecond(ZoneOffset.UTC);
        long timestamp = nowSeconds - BEGIN_TIMESTAMP;

        // 2. 生成序列号
        // 采用redis自增生成唯一ID，以天为单位
        String date = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        // 自增长
        String key = "incr" + keyPrefix + ":" + date;
        long count = stringRedisTemplate.opsForValue().increment(key);
        // 3. 拼接并返回
        // TODO，这里采用了位运算。如果当天的订单数超过了2 ^ 32能表示的范围，其实会出问题。(不过2 ^ 32已经可以表示40亿了，能有这么多订单人都笑死。。)
        return timestamp << COUNT_BITS | count;
    }

//    public static void main(String[] args) {
//        LocalDateTime localDateTime = LocalDateTime.of(2024, 1, 1, 0, 0);
//        long seconds = localDateTime.toEpochSecond(ZoneOffset.UTC);
//        System.out.println("seconds: " + seconds);
//    }
}
