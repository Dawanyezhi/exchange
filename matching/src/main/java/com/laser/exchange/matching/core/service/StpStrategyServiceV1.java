package com.laser.exchange.matching.core.service;

import com.laser.exchange.common.enums.CancelReasonEnum;
import com.laser.exchange.common.enums.StpStrategyEnum;
import com.laser.exchange.matching.core.model.MatchOrderV1;
import com.laser.exchange.matching.enums.OpEnum;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * V1 自成交保护策略服务
 *
 * <p>与 V0 StpStrategyService 的差异：
 * <ul>
 *   <li>MatchOrder -> MatchOrderV1</li>
 *   <li>List&lt;MatchOrder&gt; -> List&lt;MatchOrderV1&gt;</li>
 * </ul>
 *
 * <p>业务逻辑完全一致 — STP 策略不涉及 BigDecimal 运算。</p>
 */
@Slf4j
public class StpStrategyServiceV1 {

    /**
     * 处理自成交保护
     *
     * <p>策略选择规则：始终使用 taker 的策略作为 STP 策略。</p>
     *
     * @param taker          主动成交方
     * @param maker          被动成交方（挂单方）
     * @param pendingRemoves 需要延迟移除的订单列表（避免 ConcurrentModificationException）
     * @return OpEnum 操作指令
     */
    public OpEnum processSTP(MatchOrderV1 taker, MatchOrderV1 maker, List<MatchOrderV1> pendingRemoves) {

        // 选择自成交策略（使用 taker 的策略）
        StpStrategyEnum stpStrategyEnum = taker.getStpStrategyEnum();

        // 生效条件: taker 和 maker 都是同一个用户下的订单
        long stpAccountId = taker.getStpAccountId();

        // 无效账户 or 没有设置自成交保护策略 or 默认策略，均不执行保护
        if (stpAccountId < 0 || stpStrategyEnum == null || stpStrategyEnum == StpStrategyEnum.DEFAULT) {
            return OpEnum.OP_NORMAL;
        }

        if (stpAccountId == maker.getStpAccountId()) {
            switch (stpStrategyEnum) {
                case CANCEL_TAKER -> {
                    log.info("CANCEL_TAKER, taker id:{}, status:{}, stpAccountId:{}",
                            taker.getOrderId(), taker.getOrderStatus(), taker.getStpAccountId());
                    if (taker.isFOK()) {
                        return OpEnum.OP_BREAK;
                    }
                    taker.cancel(CancelReasonEnum.STP_CANCEL);
                    return OpEnum.OP_BREAK;
                }
                case CANCEL_MAKER -> {
                    log.info("CANCEL_MAKER, maker id:{}, status:{}, stpAccountId:{}",
                            maker.getOrderId(), maker.getOrderStatus(), maker.getStpAccountId());
                    if (taker.isFOK()) {
                        return OpEnum.OP_CONTINUE;
                    }
                    maker.cancel(CancelReasonEnum.STP_CANCEL);
                    // 不直接删除，防止 ConcurrentModificationException
                    pendingRemoves.add(maker);
                    return OpEnum.OP_CONTINUE;
                }
                case CANCEL_BOTH -> {
                    log.info("CANCEL_BOTH, taker id:{}, status:{}, stpAccountId:{}, maker id:{}, status:{}, stpAccountId:{}",
                            taker.getOrderId(), taker.getOrderStatus(), taker.getStpAccountId(),
                            maker.getOrderId(), maker.getOrderStatus(), maker.getStpAccountId());
                    if (taker.isFOK()) {
                        return OpEnum.OP_BREAK;
                    }
                    taker.cancel(CancelReasonEnum.STP_CANCEL);
                    maker.cancel(CancelReasonEnum.STP_CANCEL);
                    // 不直接删除，防止 ConcurrentModificationException
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
