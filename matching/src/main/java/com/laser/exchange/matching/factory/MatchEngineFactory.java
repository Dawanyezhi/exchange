package com.laser.exchange.matching.factory;

import com.laser.exchange.matching.core.engine.MatchEngine;
import com.laser.exchange.matching.core.engine.MatchEngineV1;
import com.laser.exchange.matching.enums.VersionEnum;

/**
 * 撮合引擎工厂 — 按版本创建引擎实例
 * <p>
 * V0 和 V1 接口不兼容（V0 用 BigDecimal, V1 用 long），调用方需自行强转。
 * </p>
 */
public class MatchEngineFactory {

    public static Object create(VersionEnum version) {
        return switch (version) {
            case V0 -> new MatchEngine();
            case V1 -> new MatchEngineV1();
        };
    }
}
