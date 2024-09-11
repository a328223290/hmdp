package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IVoucherService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.hmdp.utils.RedisConstants.SECKILL_ORDER_KEY;
import static com.hmdp.utils.RedisConstants.SECKILL_STOCK_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private IVoucherOrderService voucherOrderService;

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Autowired
    private RedisIdWorker redisIdWorker;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private RedissonClient redissonClient;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    /*
        采用阻塞队列有2个问题：
        1. 内存限制：阻塞队列有cap限制，如果超过了cap，那么就会出现数据丢失。
        2. 数据安全：由于所有的数据都存在内存里，一旦服务器宕机就完蛋了。
     */
    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    // IVoucherOrderService代理对象
    private IVoucherOrderService proxy;

    @PostConstruct
    // init会在VoucherOrderServiceImpl.java初始化完成后运行，作是把VoucherOrderHandler加入到线程池中，以保证VoucherOrderHandler可以不断获取阻塞队列元素并执行对应逻辑
    void init() {
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    private class VoucherOrderHandler implements Runnable {

        @Override
        public void run() {
            while (true) {
                try {
                    // 1. 获取阻塞队列中的订单信息
                    VoucherOrder voucherOrder = orderTasks.take();
                    // 2. 创建订单
                    handleVoucherOrder(voucherOrder);
                } catch (Exception e) {
                    log.error("创建订单失败，err: {}", e.getMessage());
                }
            }
        }
    }

    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        // 1. 获取用户
        Long userId = voucherOrder.getUserId();
        // 2. 创建锁对象
        RLock lock = redissonClient.getLock(SECKILL_ORDER_KEY + userId);
        // 3. 获取锁
        boolean isLock = lock.tryLock();
        //  4.判断锁是否能获取成功
        // TODO：其实应该不会出现这种情况，因为我们在执行下单接口的时候实际上并没有获取锁，且我们用的是SingleThreadExecutor，这里应该无论如何都是能获取到的。
        if (!isLock) {
            log.error("不允许重复下单。");
            return;
        }
        try {
            // 这里没有办法通过AopContext.currentProxy()来获取代理对象，因为底层是用ThreadLocal做的，而我们这里开启了一个子线程，自然是获取不到。
            // 这里的方案是把代理类加入类成员变量，然后在seckillVoucher里初始化。
            proxy.createVoucherOrder(voucherOrder);
        } catch (Exception e) {
            log.error("创建订单错误。err: {}", e.getMessage());
        }
    }

    @Override
    // 采用lua脚本并采用异步流程优化接口
    public Result seckillVoucher(Long id) {
        // 1. 执行lua脚本
        Long ret = stringRedisTemplate.execute(SECKILL_SCRIPT,
                Collections.emptyList(),
                SECKILL_STOCK_KEY,
                SECKILL_ORDER_KEY);
        // 2. 判断结果
        if (ret != null && ret == 1) {
            return Result.fail("库存不足。");
        } else if (ret != null && ret == 2) {
            return Result.fail("不能重复下单！");
        }
        // 2.1 如果是0，需要加入到阻塞队列里
        Long orderId = redisIdWorker.nextId("order");
        // 把订单id，用户id以及优惠券id都存入到VoucherOrder中，只需要把voucherOrder加入到阻塞队列中即可把这些信息带到阻塞队列中
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(UserHolder.getUser().getId());
        voucherOrder.setVoucherId(id);
        // TODO 保存阻塞队列
        orderTasks.add(voucherOrder);
        // 获取代理对象
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        // 3. 返回订单id
        return Result.ok(orderId);
    }

    @Override
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        // 一人一单
        Long userId = voucherOrder.getUserId();
        Long id = voucherOrder.getVoucherId();
        int count = query()
                .eq("user_id", userId)
                .eq("voucher_id", id).count();
        if (count > 0) {
            log.error("一个人只能购买一次该优惠券。");
            return;
        }
        // 5. 扣减库存
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", id)
                // 乐观锁实现思路1 - 版本号 - 这种情况会导致不能全部卖完
//                .eq("stock", seckillVoucher.getStock())
                // 直接把条件改成stock > 0，依赖mysql的行锁去解决，但这个很难说是一个乐观锁吧。。
                .gt("stock", 0)
                .update();
        if (!success) {
            log.error("库存不足。");
            return;
        }
        // 6. 创建订单
        voucherOrderService.save(voucherOrder);
        log.info("创建订单成功！{}", voucherOrder);
    }


    @Override
//    public Result seckillVoucher(Long id) {
//        // 1. 查询优惠券
//        SeckillVoucher seckillVoucher = seckillVoucherService.getById(id);
//        if (seckillVoucher == null) {
//            return Result.fail("该ID无效。");
//        }
//        // 2. 判断秒杀是否开始
//        if (seckillVoucher.getBeginTime().isAfter(LocalDateTime.now())) {
//            return Result.fail("活动尚未开始。");
//        }
//        // 3. 判断秒杀是否结束
//        if (seckillVoucher.getEndTime().isBefore(LocalDateTime.now())) {
//            return Result.fail("活动已经结束。");
//        }
//        // 4. 判断库存是否充足
//        if (seckillVoucher.getStock() < 1) {
//            return Result.fail("库存不足。");
//        }
//        // 一人一单
//        Long userId = UserHolder.getUser().getId();
//        // 采用synchronized关键字保证一人一单：无法处理集群的情况
//        // ！！！为了保证一人一单且不对所有用户加锁，这里用userId对应的字符串（且必须采用intern方法指向字符串池中的保证是同一个字符串）作为对象锁。
////        synchronized (userId.toString().intern()) {
////            // TODO: 这里还不是特别了解，大概的原理是spring框架调用带有@Transactional注解的方法时，事务想要生效，还得利用代理来生效。
////            IVoucherOrderService voucherOrderService = (IVoucherOrderService) AopContext.currentProxy();
////            return voucherOrderService.createVoucherOrder(id);
////        }
//        // 这样一来就可以保证spring框架先提交事务，再释放锁。防止先释放锁，再提交事务带来的并发问题。（比如事务尚未提交，又有新的线程）
//
//        // 为了解决集群上锁问题，采用分布式锁
////        SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
////        // TODO: 时间稍微设置长一点防止debug的时候过期
////        boolean isLock = lock.tryLock(1200);
////        if (!isLock) {
////            return Result.fail("不能重复下单！");
////        }
////        // 这里采用try-finally语句真的很妙，就算出现报错，也可以及时把锁解开
////        try {
////            IVoucherOrderService voucherOrderService = (IVoucherOrderService) AopContext.currentProxy();
////            return voucherOrderService.createVoucherOrder(id);
////        } finally {
////            lock.unlock();
////        }
//
//        // 改用Redisson提供的lock，可以保证可重入 + 重试
//        RLock lock = redissonClient.getLock("lock:order" + userId);
//        boolean isLock = lock.tryLock();
//        if (!isLock) {
//            return Result.fail("不能重复下单！");
//        }
//        // 这里采用try-finally语句真的很妙，就算出现报错，也可以及时把锁解开
//        try {
//            IVoucherOrderService voucherOrderService = (IVoucherOrderService) AopContext.currentProxy();
//            return voucherOrderService.createVoucherOrder(id);
//        } finally {
//            lock.unlock();
//        }
//    }

    @Transactional
    public Result createVoucherOrder(Long id) {
        // 一人一单
        Long userId = UserHolder.getUser().getId();
        int count = query()
                .eq("user_id", userId)
                .eq("voucher_id", id).count();
        if (count > 0) {
            return Result.fail("一个人只能购买一次该优惠券。");
        }
        // 5. 扣减库存
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", id)
                // 乐观锁实现思路1 - 版本号 - 这种情况会导致不能全部卖完
//                .eq("stock", seckillVoucher.getStock())
                // 直接把条件改成stock > 0，依赖mysql的行锁去解决，但这个很难说是一个乐观锁吧。。
                .gt("stock", 0)
                .update();
        if (!success) {
            return Result.fail("库存不足。");
        }
        // 6. 创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setId(redisIdWorker.nextId("order"));
        voucherOrder.setUserId(UserHolder.getUser().getId());
        voucherOrder.setVoucherId(id);
        voucherOrderService.save(voucherOrder);
        // 7. 返回订单id
        return Result.ok(id);
    }
}
