package com.hmdp;

import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.service.IShopService;
import com.hmdp.utils.RedisIdWorker;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.junit4.SpringRunner;

import javax.annotation.Resource;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@SpringBootTest
// !!!太坑爹了，得加入@RunWith注释才可以顺利完成注入。。
// @RunWith是 JUnit 框架中的一个注解，用于指定测试运行器（test runner)。
@RunWith(SpringRunner.class)
public class HmDianPingApplicationTests{

    @Resource
    private RedisIdWorker  redisIdWorker;

    private IShopService shopService;

    private ExecutorService es = Executors.newFixedThreadPool(500);

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
    public void testShopService() {
        Result result = shopService.queryShopById(1L);
        System.out.printf(result.toString());
    }

}
