package com.laser.exchange.matching.core.model;

import com.laser.exchange.common.PlaceOrderRequest;
import com.laser.exchange.common.enums.*;
import com.laser.exchange.common.utils.BigDecimalUtil;
import lombok.*;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * 撮合订单
 */
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
@ToString
public class MatchOrder {

    /**
     * 订单号 使用idGenerator生成，long型，雪花算法
     * 为了方便实现时间优先
     */
    private long orderId;

    /**
     * 客户端订单号，用户推送场景，或者查询场景，增加客户端端置信度
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
     * 委托价格
     */
    private BigDecimal delegatePrice;

    /**
     * 委托数量
     */
    private BigDecimal delegateCount;

    /**
     * 成交数量
     */
    private BigDecimal dealtCount;

    /**
     * 订单状态
     */
    private OrderStatusEnum orderStatus;

    /**
     * 在aeron共识系统中，时间统一采用的是共识时间戳，
     */
    private long createTime;
    private long updateTime;

    /**
     * 自成交保护的账户id（挂单maker的stpAccount和taker的stpAccount如果相等，就代表可能发生自成交）
     * 确保在子账号下单的场景下，有一个唯一的用户编码能够进行关联（eg：母账号和子账号之间也需要进行自成交保护，那么确保母子账号使用相同的stpAccountId，一般来说用母账户的accountId作为stpAccountId）
     */
    private long stpAccountId;

    /**
     * 自成交保护策略
     */
    private StpStrategyEnum stpStrategyEnum;

    private CancelReasonEnum cancelReason;

    public boolean isLimit() {
        return orderType == OrderType.LIMIT;
    }

    public boolean isMarket() {
        return orderType == OrderType.MARKET;
    }

    public void reject() {
        this.orderStatus = OrderStatusEnum.REJECTED;
    }

    public void cancel(CancelReasonEnum cancelReason) {
        this.cancelReason = cancelReason;
        this.orderStatus = OrderStatusEnum.CANCELLED;
    }

    public boolean isBuy() {
        return this.orderSide == OrderSideEnum.BUY;
    }

    /**
     * @return
     */
    public MatchOrder(long orderId) {
        MatchOrder matchOrder = new MatchOrder();
        matchOrder.setOrderId(orderId);
        matchOrder.setOrderStatus(OrderStatusEnum.NEW);
        matchOrder.setDealtCount(BigDecimal.ZERO);
        matchOrder.setCancelReason(CancelReasonEnum.NONE);
    }


    public boolean isMatchOver() {
        return this.orderStatus.over();
    }

    /**
     * 剩余数量 = 代理数量 - 成交数量
     *
     * @return
     */
    public BigDecimal getRemainingQuantity() {
        return BigDecimalUtil.subtract(this.delegateCount, this.dealtCount);
    }

    public void updateFilledQuantity(BigDecimal matchedQty) {

        this.dealtCount = BigDecimalUtil.add(this.dealtCount, matchedQty);

        if (fullFilled()) {
            this.orderStatus = OrderStatusEnum.FULL_FILLED;
        } else if (isPartiallyFilled()) {
            this.orderStatus = OrderStatusEnum.PARTIALLY_FILLED;
        }
    }

    /**
     * 部分成交，成交量大于零且小于委托数量
     *
     * @return
     */
    private boolean isPartiallyFilled() {
        return BigDecimalUtil.greaterThanZero(this.dealtCount) && !fullFilled();
    }

    /**
     * 完全成交
     * 成交数量 >= 委托数量
     *
     * @return
     */
    public boolean fullFilled() {
        return BigDecimalUtil.greaterOrEquals(this.dealtCount, this.delegateCount);
    }

    public boolean isFOK() {
        return this.timeInForce == TimeInForceEnum.FOK;
    }

    public BigDecimal getMatchedQty(BigDecimal oppoOrderRemainingQty) {
        return this.getRemainingQuantity().min(oppoOrderRemainingQty);
    }

    /**
     * 深拷贝当前订单，生成一个独立的副本
     */
    public MatchOrder deepCopy() {
        MatchOrder copy = new MatchOrder();
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

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        MatchOrder that = (MatchOrder) o;
        return orderId == that.orderId && accountId == that.accountId && Objects.equals(symbolId, that.symbolId) && orderType == that.orderType && orderSide == that.orderSide && timeInForce == that.timeInForce;
    }

    @Override
    public int hashCode() {
        return Objects.hash(orderId, accountId, symbolId, orderType, orderSide, timeInForce);
    }

    public MatchOrder buildNewMatchOrder(long timestamp, String symbolId, PlaceOrderRequest placeOrderRequest) {
        this.setOrderId(placeOrderRequest.getOrderId());
        this.setClientOid(placeOrderRequest.getClientOid());
        this.setAccountId(placeOrderRequest.getAccountId());
        this.setSymbolId(symbolId);
        this.setOrderType(placeOrderRequest.getOrderType());
        this.setOrderSide(placeOrderRequest.getOrderSide());
        this.setTimeInForce(placeOrderRequest.getTimeInForce());
        this.setDelegatePrice(placeOrderRequest.getDelegatePrice());
        this.setDelegateCount(placeOrderRequest.getDelegateCount());
        this.setDealtCount(BigDecimal.ZERO);
        this.setOrderStatus(OrderStatusEnum.NEW);
        this.setCancelReason(CancelReasonEnum.NONE);
        this.setStpAccountId(placeOrderRequest.getStpAccountId());
        this.setStpStrategyEnum(placeOrderRequest.getStpStrategyEnum());
        this.setCreateTime(timestamp);
        return this;
    }
}
