package com.laser.exchange.matching.core.model;

import com.laser.exchange.common.enums.*;
import lombok.*;

import java.util.Objects;

/**
 * V1 撮合订单 — 使用 long 替代 BigDecimal
 *
 * <p>核心优化点：
 * <ul>
 *   <li>delegatePrice / delegateCount / dealtCount 全部使用 long（调用方需按精度乘好再传入）</li>
 *   <li>消除 BigDecimal 对象分配，降低 GC 压力</li>
 *   <li>数量比较从 compareTo 变为直接算术运算</li>
 * </ul>
 *
 * <p>与 V0 MatchOrder 的差异：
 * <ul>
 *   <li>BigDecimal delegatePrice  -> long delegatePrice</li>
 *   <li>BigDecimal delegateCount  -> long delegateCount</li>
 *   <li>BigDecimal dealtCount     -> long dealtCount</li>
 *   <li>移除 buildNewMatchOrder(PlaceOrderRequest)，V1 不依赖 PlaceOrderRequest</li>
 *   <li>修复 V0 构造器 MatchOrder(long orderId) 中无效 new MatchOrder() 的 bug</li>
 * </ul>
 */
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
@ToString
public class MatchOrderV1 {

    /**
     * 订单号，雪花算法生成，long 型，天然支持时间优先
     */
    private long orderId;

    /**
     * 客户端订单号，用于推送和查询场景
     */
    private String clientOid;

    /**
     * 用户编码
     */
    private long accountId;

    /**
     * 标的名称 如：SPOT_BTC_USDT UC_BTC_USDT (u本位) DC_BTC（币本位）
     */
    private String symbolId;

    /**
     * 订单类型
     */
    private OrderType orderType;

    /**
     * 买卖方向
     */
    private OrderSideEnum orderSide;

    /**
     * 生效类型
     */
    private TimeInForceEnum timeInForce;

    /**
     * 委托价格（long，调用方按精度预处理）
     */
    private long delegatePrice;

    /**
     * 委托数量（long，调用方按精度预处理）
     */
    private long delegateCount;

    /**
     * 成交数量（long，初始为 0）
     */
    private long dealtCount;

    /**
     * 订单状态
     */
    private OrderStatusEnum orderStatus;

    /**
     * 共识时间戳
     */
    private long createTime;
    private long updateTime;

    /**
     * 自成交保护账户 id
     */
    private long stpAccountId;

    /**
     * 自成交保护策略
     */
    private StpStrategyEnum stpStrategyEnum;

    private CancelReasonEnum cancelReason;

    // ======================== 便捷构造器 ========================

    /**
     * 通过 orderId 快速构建新订单（修复 V0 构造器 bug：不再创建无用的临时对象）
     */
    public MatchOrderV1(long orderId) {
        this.orderId = orderId;
        this.orderStatus = OrderStatusEnum.NEW;
        this.dealtCount = 0L;
        this.cancelReason = CancelReasonEnum.NONE;
    }

    // ======================== 订单类型判断 ========================

    public boolean isLimit() {
        return orderType == OrderType.LIMIT;
    }

    public boolean isMarket() {
        return orderType == OrderType.MARKET;
    }

    public boolean isBuy() {
        return this.orderSide == OrderSideEnum.BUY;
    }

    public boolean isFOK() {
        return this.timeInForce == TimeInForceEnum.FOK;
    }

    // ======================== 状态变更 ========================

    public void reject() {
        this.orderStatus = OrderStatusEnum.REJECTED;
    }

    public void cancel(CancelReasonEnum cancelReason) {
        this.cancelReason = cancelReason;
        this.orderStatus = OrderStatusEnum.CANCELLED;
    }

    public boolean isMatchOver() {
        return this.orderStatus.over();
    }

    // ======================== 数量计算（long 直接运算，无 BigDecimal 开销）========================

    /**
     * 剩余数量 = 委托数量 - 已成交数量
     */
    public long getRemainingQuantity() {
        return delegateCount - dealtCount;
    }

    /**
     * 更新成交数量并刷新状态
     */
    public void updateFilledQuantity(long matchedQty) {
        dealtCount += matchedQty;
        if (fullFilled()) {
            this.orderStatus = OrderStatusEnum.FULL_FILLED;
        } else if (isPartiallyFilled()) {
            this.orderStatus = OrderStatusEnum.PARTIALLY_FILLED;
        }
    }

    /**
     * 完全成交：已成交数量 >= 委托数量
     */
    public boolean fullFilled() {
        return dealtCount >= delegateCount;
    }

    /**
     * 部分成交：已成交数量 > 0 且未完全成交
     */
    private boolean isPartiallyFilled() {
        return dealtCount > 0 && !fullFilled();
    }

    /**
     * 计算本次可成交数量 = min(本单剩余, 对手剩余)
     */
    public long getMatchedQty(long oppoRemaining) {
        return Math.min(getRemainingQuantity(), oppoRemaining);
    }

    // ======================== 深拷贝 ========================

    /**
     * 深拷贝 — 所有字段均为原始类型 / 枚举 / String，无需额外克隆
     */
    public MatchOrderV1 deepCopy() {
        MatchOrderV1 copy = new MatchOrderV1();
        copy.orderId = this.orderId;
        copy.clientOid = this.clientOid;
        copy.accountId = this.accountId;
        copy.symbolId = this.symbolId;
        copy.orderType = this.orderType;
        copy.orderSide = this.orderSide;
        copy.timeInForce = this.timeInForce;
        copy.delegatePrice = this.delegatePrice;
        copy.delegateCount = this.delegateCount;
        copy.dealtCount = this.dealtCount;
        copy.orderStatus = this.orderStatus;
        copy.createTime = this.createTime;
        copy.updateTime = this.updateTime;
        copy.stpAccountId = this.stpAccountId;
        copy.stpStrategyEnum = this.stpStrategyEnum;
        copy.cancelReason = this.cancelReason;
        return copy;
    }

    // ======================== equals / hashCode（与 V0 一致）========================

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        MatchOrderV1 that = (MatchOrderV1) o;
        return orderId == that.orderId
                && accountId == that.accountId
                && Objects.equals(symbolId, that.symbolId)
                && orderType == that.orderType
                && orderSide == that.orderSide
                && timeInForce == that.timeInForce;
    }

    @Override
    public int hashCode() {
        return Objects.hash(orderId, accountId, symbolId, orderType, orderSide, timeInForce);
    }
}
