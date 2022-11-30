package com.hmdp;

import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.ReidsIdWorker;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@SpringBootTest
class HmDianPingApplicationTests {

    @Autowired
    private IVoucherOrderService service;

    @Autowired
    private ISeckillVoucherService seckillVoucherService;
    @Autowired
   private ReidsIdWorker reidsIdWorker;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    private ExecutorService es= Executors.newFixedThreadPool(500);
    @Test
    void a() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(500);

        Runnable task=()->{
            for (int i = 0; i < 100; i++) {
                long order = reidsIdWorker.nextId("order");
                System.out.println("id="+order);
                latch.countDown();

            }

        };
        long start=System.currentTimeMillis();
        for (int i = 0; i < 6; i++) {
            es.submit(task);
        }

        latch.await();
        long end=System.currentTimeMillis();
        Thread.sleep(5000);
        System.out.println("time="+(end-start));


    }

    @Test
    void mapper()  {
        boolean deductstock = seckillVoucherService.deductstock(10L);
        System.out.println(deductstock);
    }

}
