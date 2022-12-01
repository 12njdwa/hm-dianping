package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.ReidsIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.concurrent.*;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 鄢
 * @since 2022-11-23
 */
@Slf4j
@Service
@Cacheable
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {
    @Autowired
    private ISeckillVoucherService seckillVoucherService;
    @Autowired
    private ReidsIdWorker reidsIdWorker;

    @Autowired
    private RedissonClient redissonClient;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    //lua脚本
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    //阻塞队列
    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    @PostConstruct
    private void init() {
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    private void handlerVoucherOrder(VoucherOrder voucherOrder) throws InterruptedException {
        Long userId = voucherOrder.getUserId();
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        boolean success = lock.tryLock(1L, TimeUnit.SECONDS);
        if (!success) {
            log.error("不允许重复下单");
            return;
        }
        try {
            proxy.createVoucherOrder(voucherOrder.getVoucherId(), userId);
        } finally {
            lock.unlock();
        }
    }

    private class VoucherOrderHandler implements Runnable {
        @Override
        public void run() {
            while (true) {
                try {
                    VoucherOrder voucherOrder = orderTasks.take();
                    handlerVoucherOrder(voucherOrder);
                } catch (InterruptedException e) {
                    log.error("订单处理异常", e);
                }
            }
        }
    }


    //抢秒杀券   解决了单一进程的线程安全问题，但对分布式的不同进程无效。
    @Override
    public Result seckillVoucher(Long voucherId) {
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
            return Result.fail("秒杀还没开始");
        }
        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
            return Result.fail("秒杀已经结束");
        }
        if (voucher.getStock() < 1) {
            return Result.fail("库存不足");
        }

        //这里为了解决同一个用户的多线程安全问题，需要加一个锁，又因为不同的用户不需要一起锁，所以只需要锁住唯一的userid就可以了。
        Long userId = UserHolder.getUser().getId();
        synchronized (userId.toString().intern()) {             //因为每次都是不同的对象，所以需要把锁变成每个用户唯一的,intern方法的返回值一定是常量池中唯一的值。
//            spring中的事务是aop的动态代理实现的。此时直接调用createVoucherOrder是调用this的方法,此时的对象是原本对象在执行，而不是代理对象的方法，所以@Transactional是无法生效的。
//            有两种方法可以解决：
//            1.可以把自己注入，再调用注入的动态代理对象的该方法，此时就是代理对象在执行了。
//            2.可以直接调用AopContext.currentProxy()方法可以得到当前的代理对象，再执行。
            IVoucherOrderService voucherOrderService = (IVoucherOrderService) AopContext.currentProxy();
            return voucherOrderService.createVoucherOrder(voucherId, userId);
        }
        //然而上面这种方法在应对分布式的时候依然会有并发安全问题。这是因为userId.toString().intern()这个锁是当前JVM常量池中的，然而在不同的进程中的JVM是完全独立的，这样的话这个锁也就没有了意义。
        //这时候就需要一个中间件来实现分布式锁了（Redis）
    }

    //抢秒杀券   分布式锁
    @Override
    public Result seckillVoucherForDistributed(Long voucherId) throws InterruptedException {
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
            return Result.fail("秒杀还没开始");
        }
        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
            return Result.fail("秒杀已经结束");
        }
        if (voucher.getStock() < 1) {
            return Result.fail("库存不足");
        }

        Long userId = UserHolder.getUser().getId();
        /**         使用自定义的分布式锁，虽然不太全面，但一般情况下够用了。
         *         SimpleReidsLock simpleReidsLock = new SimpleReidsLock("order:" + userId, stringRedisTemplate);
         *         boolean success = simpleReidsLock.getlock(10L);
         *         if (!success) {
         *             return Result.fail("一个人只允许下一单");
         *         }
         *         try {
         *             IVoucherOrderService voucherOrderService = (IVoucherOrderService) AopContext.currentProxy();
         *             return voucherOrderService.createVoucherOrder(voucherId, userId);
         *         }finally {
         *             simpleReidsLock.unlock();
         *         }
         */

        //使用Redisson实现分布式锁
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        boolean success = lock.tryLock(1L, TimeUnit.SECONDS);
        if (!success) {
            return Result.fail("不允许重复下单");
        }
        try {
            IVoucherOrderService voucherOrderService = (IVoucherOrderService) AopContext.currentProxy();
            return voucherOrderService.createVoucherOrder(voucherId, userId);
        } finally {
            lock.unlock();
        }
    }

    private IVoucherOrderService proxy;

//    //抢秒杀券   分布式锁  进一步的性能优化  没看完。
//    @Override
//    public Result seckillVoucherForDistributed2(Long voucherId) throws InterruptedException {
//        //执行lua脚本
//        Long result = stringRedisTemplate.execute(SECKILL_SCRIPT, Collections.emptyList(), voucherId.toString(), UserHolder.getUser().getId().toString());
//        //判断结果lua脚本返回的结果，是0则继续业务，否则报错
//        switch (result.intValue()) {
//            case 1:
//                return Result.fail("库存不足");
//            case 2:
//                return Result.fail("不允许重复下单");
//        }
//
//        VoucherOrder voucherOrder = new VoucherOrder();
//        long orderId = reidsIdWorker.nextId("order");
//        voucherOrder.setId(orderId);
//        voucherOrder.setUserId(UserHolder.getUser().getId());
//        voucherOrder.setVoucherId(voucherId);
//        //保存阻塞队列
//        orderTasks.add(voucherOrder);
//        proxy = (IVoucherOrderService) AopContext.currentProxy();
//        stringRedisTemplate.opsForStream().add();
//        stringRedisTemplate.opsForStream().read();
//
//        return Result.ok(orderId);
//
//    }


    @Transactional
    public Result createVoucherOrder(Long voucherId, Long userId) {
        //查询当前用户的订单数量。如果已经有了，则无法再次购买了。
        int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        if (count > 0) {
            return Result.fail("您已经有了，不要太贪心哦");
        }
        //库存减一
        boolean success = seckillVoucherService.deductstock(voucherId);
        if (!success) {
            return Result.fail("库存减一失败！");
        }
        VoucherOrder voucherOrder = new VoucherOrder();
        long orderId = reidsIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(UserHolder.getUser().getId());
        voucherOrder.setVoucherId(voucherId);
        save(voucherOrder);
        return Result.ok(orderId);
    }
}
