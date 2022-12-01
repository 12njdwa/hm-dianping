package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 * 服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IVoucherOrderService extends IService<VoucherOrder> {

    Result seckillVoucher(Long voucherId);

    Result seckillVoucherForDistributed(Long voucherId) throws InterruptedException;

    //抢秒杀券   分布式锁  进一步的性能优化
//    Result seckillVoucherForDistributed2(Long voucherId) throws InterruptedException;

    Result createVoucherOrder(Long voucherId, Long userId);
}
