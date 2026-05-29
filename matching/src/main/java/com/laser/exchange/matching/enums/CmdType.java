package com.laser.exchange.matching.enums;

import com.laser.exchange.common.codec.AmendOrderCommandEncoder;
import com.laser.exchange.common.codec.CancelOrderCommandEncoder;
import com.laser.exchange.common.codec.PlaceOrderCommandEncoder;
import com.laser.exchange.common.codec.TradeSwitchCommandEncoder;
import com.laser.exchange.common.codec.UpDownSymbolCommandEncoder;

public class CmdType {

    public static final int PLACE_ORDER_COMMAND = PlaceOrderCommandEncoder.TEMPLATE_ID;

    public static final int AMEND_ORDER_COMMAND = AmendOrderCommandEncoder.TEMPLATE_ID;

    public static final int CANCEL_ORDER_COMMAND = CancelOrderCommandEncoder.TEMPLATE_ID;

    /** 上下币 (控制面，不参与 serialNum 校验) */
    public static final int UP_DOWN_SYMBOL_COMMAND = UpDownSymbolCommandEncoder.TEMPLATE_ID;

    /** 币对开关交易 (控制面，不参与 serialNum 校验) */
    public static final int TRADE_SWITCH_COMMAND = TradeSwitchCommandEncoder.TEMPLATE_ID;
}
