package com.laser.exchange.matching.enums;

import com.laser.exchange.common.codec.AmendOrderCommandEncoder;
import com.laser.exchange.common.codec.CancelOrderCommandEncoder;
import com.laser.exchange.common.codec.PlaceOrderCommandEncoder;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.agrona.collections.Int2ObjectHashMap;

import java.util.Map;

@AllArgsConstructor(access = AccessLevel.PRIVATE)
public enum CommandType {

    PLACE_ORDER_COMMAND(PlaceOrderCommandEncoder.TEMPLATE_ID),
    AMEND_ORDER_COMMAND(AmendOrderCommandEncoder.TEMPLATE_ID),
    CANCEL_ORDER_COMMAND(CancelOrderCommandEncoder.TEMPLATE_ID)
    ;

    private static final Map<Integer, CommandType> CMD_TYPE_MAP = new Int2ObjectHashMap<>();

    static {
        for (CommandType ct : CommandType.values()) {
            CMD_TYPE_MAP.put(ct.cmdType, ct);
        }
    }

    @Getter
    private int cmdType;

    public static CommandType of(int cmdType) {
        return CMD_TYPE_MAP.get(cmdType);
    }
}
