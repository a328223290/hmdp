package com.hmdp;

import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.service.IShopService;
import com.hmdp.utils.RedisIdWorker;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.junit4.SpringRunner;

import javax.annotation.Resource;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@SpringBootTest
// !!!太坑爹了，得加入@RunWith注释才可以顺利完成注入。。
// @RunWith是 JUnit 框架中的一个注解，用于指定测试运行器（test runner)。
@RunWith(SpringRunner.class)
public class HmDianPingApplicationTests{

    @Resource
    private RedisIdWorker  redisIdWorker;


    private ExecutorService es = Executors.newFixedThreadPool(500);

    @Resource
    private RedissonClient redissonClient;

    @Test
    public void testIdWorker() throws InterruptedException {
        CountDownLatch countDownLatch = new CountDownLatch(300);

        Runnable task = () -> {
            for (int i = 0; i < 100; i++) {
                long id = redisIdWorker.nextId("order");
                System.out.println("id: " + id);
            }
            countDownLatch.countDown();
        };
        long begin = System.currentTimeMillis();
        for (int i = 0; i < 300; i++) {
            es.execute(task);
        }
        // 主线程等待，直到所有子线程完成工作
        countDownLatch.await();
        long end = System.currentTimeMillis();
        System.out.println("Total time cost: " + (end - begin));
    }

    @Test
    public void testRedisson() throws InterruptedException {
        // 获取锁（可重入），指定锁的名称
        RLock lock = redissonClient.getLock("anyLock");
        // 尝试获取锁，参数分别是：获取锁的最大等待时间（期间会重试），锁自动释放时间，时间单位
        boolean isLock = lock.tryLock(1, 10, TimeUnit.SECONDS);
        // 判断是否获取成功
        if (isLock) {
            try {
                System.out.printf("执行业务。");
            } finally {
                // 释放锁
                lock.unlock();
            }
        }
    }

}
