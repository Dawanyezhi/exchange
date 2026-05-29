package com.laser.exchange.matching.core.service;

import com.laser.exchange.common.enums.CancelReasonEnum;
import com.laser.exchange.matching.enums.OpEnum;
import com.laser.exchange.common.enums.StpStrategyEnum;
import com.laser.exchange.matching.core.model.MatchOrder;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

@Slf4j
public class StpStrategyService {

    /**
     * 如何选择自成交策略?
     *  肯定需要规定以taker还是maker的策略为准
     *  为什么？
     *  taker和maker可以设置不一样的策略，但是始终要选择确定的一种策略
     *  我们约定始终用更新的策略作为stp的策略，也就是用taker的策略作为stp的策略
     *
     *  注意：
     *      针对fok，需要特殊处理
     *      对于fok有一个预撮合，遍历但是不能改变对手盘状态，所以需要把需要撤单的列表，额外的暂存一下，
     *      等到确认可以进行撮合（完全成交）
     *      再把集合中的订单拿出来遍历进行撤单
     * @param taker
     * @param maker
     */
    public OpEnum processSTP(MatchOrder taker, MatchOrder maker, List<MatchOrder> pendingRemoves) {

        // 选择自成交策略(使用taker的策略)
        StpStrategyEnum stpStrategyEnum = taker.getStpStrategyEnum();

        // 生效条件: taker和maker都是同一个用户下的订单
        long stpAccountId = taker.getStpAccountId();

        // 无效账户 or 没有设置自成交保护策略 or 默认自成交保护策略，都不进行自成交保护
        if (stpAccountId < 0 || stpStrategyEnum == null || stpStrategyEnum == StpStrategyEnum.DEFAULT) {
            return OpEnum.OP_NORMAL;
        }

        if (stpAccountId == maker.getStpAccountId()) {
            switch (stpStrategyEnum) {
                case CANCEL_TAKER -> {
                    log.info("CANCEL_TAKER, taker id:{}, status:{}, stpAccountId:{}", taker.getOrderId(), taker.getOrderStatus(), taker.getStpAccountId());
                    if (taker.isFOK()) {
                        return OpEnum.OP_BREAK;
                    }
                    taker.cancel(CancelReasonEnum.STP_CANCEL);
                    return OpEnum.OP_BREAK;
                }
                case CANCEL_MAKER -> {
                    log.info("CANCEL_MAKER, maker id:{}, status:{}, stpAccountId:{}", maker.getOrderId(), maker.getOrderStatus(), maker.getStpAccountId());
                    if (taker.isFOK()) {
                        return OpEnum.OP_CONTINUE;
                    }
                    maker.cancel(CancelReasonEnum.STP_CANCEL);
                    // 不直接删除防止出现ConcurrentModificationException
                    pendingRemoves.add(maker);
                    return OpEnum.OP_CONTINUE;
                }
                case CANCEL_BOTH -> {
                    log.info("CANCEL_BOTH, taker id:{}, status:{}, stpAccountId:{}, maker id:{}, status:{}, stpAccountId:{}", taker.getOrderId(), taker.getOrderStatus(), taker.getStpAccountId(), maker.getOrderId(), maker.getOrderStatus(), maker.getStpAccountId());
                    if (taker.isFOK()) {
                        return OpEnum.OP_BREAK;
                    }
                    taker.cancel(CancelReasonEnum.STP_CANCEL);
                    maker.cancel(CancelReasonEnum.STP_CANCEL);
                    // 不直接删除防止出现ConcurrentModificationException
                    pendingRemoves.add(maker);
                    return OpEnum.OP_BREAK;
                }
                default -> {
                    return OpEnum.OP_NORMAL;
                }
            }
        }
        return OpEnum.OP_NORMAL;
    }
}
