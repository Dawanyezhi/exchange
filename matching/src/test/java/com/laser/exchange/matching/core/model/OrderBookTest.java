package com.laser.exchange.matching.core.model;

import com.laser.exchange.common.enums.OrderSideEnum;
import com.laser.exchange.common.enums.OrderStatusEnum;
import com.laser.exchange.common.enums.OrderType;
import com.laser.exchange.common.enums.TimeInForceEnum;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.NavigableMap;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("订单簿 OrderBook 测试")
class OrderBookTest {

    private static final String SYMBOL = "SPOT_BTC_USDT";
    private OrderBook orderBook;

    @BeforeEach
    void setUp() {
        orderBook = new OrderBook(SYMBOL);
    }

    private MatchOrder buildOrder(long orderId, OrderSideEnum side, BigDecimal price, BigDecimal qty) {
        return MatchOrder.builder()
                .orderId(orderId)
                .symbolId(SYMBOL)
                .orderType(OrderType.LIMIT)
                .orderSide(side)
                .timeInForce(TimeInForceEnum.GTC)
                .delegatePrice(price)
                .delegateCount(qty)
                .orderStatus(OrderStatusEnum.NEW)
                .build();
    }

    private MatchOrder buildBuyOrder(long orderId, BigDecimal price, BigDecimal qty) {
        return buildOrder(orderId, OrderSideEnum.BUY, price, qty);
    }

    private MatchOrder buildSellOrder(long orderId, BigDecimal price, BigDecimal qty) {
        return buildOrder(orderId, OrderSideEnum.SELL, price, qty);
    }

    // ==================== 构造函数测试 ====================

    @Nested
    @DisplayName("构造函数")
    class ConstructorTest {

        @Test
        @DisplayName("创建订单簿时应正确设置币对名称")
        void constructor_shouldSetSymbol() {
            assertEquals(SYMBOL, orderBook.getSymbol());
        }

        @Test
        @DisplayName("新建订单簿的买卖盘应为空")
        void constructor_shouldHaveEmptyBooks() {
            assertTrue(orderBook.getBuyOrders().isEmpty());
            assertTrue(orderBook.getSellOrders().isEmpty());
            assertTrue(orderBook.getOrderMap().isEmpty());
        }
    }

    // ==================== isOrderExists 测试 ====================

    @Nested
    @DisplayName("订单存在性检查 isOrderExists")
    class IsOrderExistsTest {

        @Test
        @DisplayName("订单存在时应返回true")
        void isOrderExists_whenExists_shouldReturnTrue() {
            MatchOrder order = buildBuyOrder(1001L, new BigDecimal("50000"), new BigDecimal("1"));
            orderBook.addOrder(order);

            assertTrue(orderBook.isOrderExists(1001L));
        }

        @Test
        @DisplayName("订单不存在时应返回false")
        void isOrderExists_whenNotExists_shouldReturnFalse() {
            assertFalse(orderBook.isOrderExists(9999L));
        }
    }

    // ==================== getOrder 测试 ====================

    @Nested
    @DisplayName("获取订单 getOrder")
    class GetOrderTest {

        @Test
        @DisplayName("订单存在时应返回订单对象")
        void getOrder_whenExists_shouldReturnOrder() {
            MatchOrder order = buildBuyOrder(1001L, new BigDecimal("50000"), new BigDecimal("1"));
            orderBook.addOrder(order);

            MatchOrder result = orderBook.getOrder(1001L);
            assertNotNull(result);
            assertEquals(1001L, result.getOrderId());
        }

        @Test
        @DisplayName("订单不存在时应返回null")
        void getOrder_whenNotExists_shouldReturnNull() {
            assertNull(orderBook.getOrder(9999L));
        }
    }

    // ==================== addOrder 测试 ====================

    @Nested
    @DisplayName("添加订单 addOrder")
    class AddOrderTest {

        @Test
        @DisplayName("买单应加入买单簿")
        void addOrder_buyOrder_shouldAddToBuyBook() {
            MatchOrder buyOrder = buildBuyOrder(1001L, new BigDecimal("50000"), new BigDecimal("1"));
            orderBook.addOrder(buyOrder);

            assertEquals(1, orderBook.getBuyOrders().size());
            assertTrue(orderBook.getSellOrders().isEmpty());
            assertNotNull(orderBook.getOrder(1001L));
        }

        @Test
        @DisplayName("卖单应加入卖单簿")
        void addOrder_sellOrder_shouldAddToSellBook() {
            MatchOrder sellOrder = buildSellOrder(1001L, new BigDecimal("50000"), new BigDecimal("1"));
            orderBook.addOrder(sellOrder);

            assertTrue(orderBook.getBuyOrders().isEmpty());
            assertEquals(1, orderBook.getSellOrders().size());
            assertNotNull(orderBook.getOrder(1001L));
        }

        @Test
        @DisplayName("相同价格的订单应加入同一档位（时间优先）")
        void addOrder_samePrice_shouldAddToSameDepthLine() {
            MatchOrder order1 = buildBuyOrder(1001L, new BigDecimal("50000"), new BigDecimal("1"));
            MatchOrder order2 = buildBuyOrder(1002L, new BigDecimal("50000"), new BigDecimal("2"));

            orderBook.addOrder(order1);
            orderBook.addOrder(order2);

            // 只有一个价格档位
            assertEquals(1, orderBook.getBuyOrders().size());
            // 但有两个订单
            assertEquals(2, orderBook.getOrderMap().size());

            // 验证档位内有两个订单
            DepthLine depthLine = orderBook.getBuyOrders().get(new BigDecimal("50000"));
            assertNotNull(depthLine);
            assertEquals(2, depthLine.getOrders().size());
        }

        @Test
        @DisplayName("不同价格的订单应创建不同档位")
        void addOrder_differentPrice_shouldCreateDifferentDepthLines() {
            MatchOrder order1 = buildBuyOrder(1001L, new BigDecimal("50000"), new BigDecimal("1"));
            MatchOrder order2 = buildBuyOrder(1002L, new BigDecimal("49000"), new BigDecimal("2"));

            orderBook.addOrder(order1);
            orderBook.addOrder(order2);

            assertEquals(2, orderBook.getBuyOrders().size());
            assertEquals(2, orderBook.getOrderMap().size());
        }

        @Test
        @DisplayName("买单簿应按价格从高到低排序（最优买价在前）")
        void addOrder_buyOrders_shouldSortByPriceDescending() {
            orderBook.addOrder(buildBuyOrder(1001L, new BigDecimal("49000"), new BigDecimal("1")));
            orderBook.addOrder(buildBuyOrder(1002L, new BigDecimal("51000"), new BigDecimal("1")));
            orderBook.addOrder(buildBuyOrder(1003L, new BigDecimal("50000"), new BigDecimal("1")));

            NavigableMap<BigDecimal, DepthLine> buyOrders = orderBook.getBuyOrders();
            BigDecimal[] prices = buyOrders.keySet().toArray(new BigDecimal[0]);

            assertEquals(new BigDecimal("51000"), prices[0]); // 最高价在前
            assertEquals(new BigDecimal("50000"), prices[1]);
            assertEquals(new BigDecimal("49000"), prices[2]); // 最低价在后
        }

        @Test
        @DisplayName("卖单簿应按价格从低到高排序（最优卖价在前）")
        void addOrder_sellOrders_shouldSortByPriceAscending() {
            orderBook.addOrder(buildSellOrder(1001L, new BigDecimal("51000"), new BigDecimal("1")));
            orderBook.addOrder(buildSellOrder(1002L, new BigDecimal("49000"), new BigDecimal("1")));
            orderBook.addOrder(buildSellOrder(1003L, new BigDecimal("50000"), new BigDecimal("1")));

            NavigableMap<BigDecimal, DepthLine> sellOrders = orderBook.getSellOrders();
            BigDecimal[] prices = sellOrders.keySet().toArray(new BigDecimal[0]);

            assertEquals(new BigDecimal("49000"), prices[0]); // 最低价在前
            assertEquals(new BigDecimal("50000"), prices[1]);
            assertEquals(new BigDecimal("51000"), prices[2]); // 最高价在后
        }
    }

    // ==================== removeOrder 测试 ====================

    @Nested
    @DisplayName("撤销订单 removeOrder")
    class RemoveOrderTest {

        @Test
        @DisplayName("撤销不存在的订单应静默返回")
        void removeOrder_notExists_shouldDoNothing() {
            MatchOrder order = buildBuyOrder(9999L, new BigDecimal("50000"), new BigDecimal("1"));

            // 不应抛出异常
            assertDoesNotThrow(() -> orderBook.removeOrder(order));
            assertTrue(orderBook.getOrderMap().isEmpty());
        }

        @Test
        @DisplayName("撤销订单后应从orderMap中移除")
        void removeOrder_shouldRemoveFromOrderMap() {
            MatchOrder order = buildBuyOrder(1001L, new BigDecimal("50000"), new BigDecimal("1"));
            orderBook.addOrder(order);

            assertTrue(orderBook.isOrderExists(1001L));

            orderBook.removeOrder(order);

            assertFalse(orderBook.isOrderExists(1001L));
            assertNull(orderBook.getOrder(1001L));
        }

        @Test
        @DisplayName("撤销订单后应从档位中移除")
        void removeOrder_shouldRemoveFromDepthLine() {
            MatchOrder order = buildBuyOrder(1001L, new BigDecimal("50000"), new BigDecimal("1"));
            orderBook.addOrder(order);

            orderBook.removeOrder(order);

            DepthLine depthLine = orderBook.getBuyOrders().get(new BigDecimal("50000"));
            // 档位已被清理
            assertNull(depthLine);
        }

        @Test
        @DisplayName("档位内还有其他订单时不应删除档位")
        void removeOrder_depthLineNotEmpty_shouldKeepDepthLine() {
            MatchOrder order1 = buildBuyOrder(1001L, new BigDecimal("50000"), new BigDecimal("1"));
            MatchOrder order2 = buildBuyOrder(1002L, new BigDecimal("50000"), new BigDecimal("2"));

            orderBook.addOrder(order1);
            orderBook.addOrder(order2);

            orderBook.removeOrder(order1);

            // 档位仍存在
            DepthLine depthLine = orderBook.getBuyOrders().get(new BigDecimal("50000"));
            assertNotNull(depthLine);
            assertEquals(1, depthLine.getOrders().size());
            // 剩余订单是order2
            assertEquals(1002L, depthLine.getOrders().get(0).getOrderId());
        }

        @Test
        @DisplayName("档位内订单全部撤销后应清理空档位")
        void removeOrder_depthLineEmpty_shouldRemoveDepthLine() {
            MatchOrder order = buildBuyOrder(1001L, new BigDecimal("50000"), new BigDecimal("1"));
            orderBook.addOrder(order);

            assertEquals(1, orderBook.getBuyOrders().size());

            orderBook.removeOrder(order);

            assertTrue(orderBook.getBuyOrders().isEmpty());
        }
    }

    // ==================== amendOrder 测试 ====================

    @Nested
    @DisplayName("修改订单 amendOrder")
    class AmendOrderTest {
        @Test
        @DisplayName("修改不存在的订单，原单撤除静默跳过，新单正常入簿")
        void amendOrder_notExists_shouldDoNothing() {
            MatchOrder order = buildBuyOrder(9999L, new BigDecimal("50000"), new BigDecimal("1"));

            assertDoesNotThrow(() -> orderBook.amendOrder(order, order));
            // amendOrder = removeOrder + addOrder，原单不存在时 removeOrder 静默跳过，addOrder 正常执行
            assertEquals(1, orderBook.getOrderMap().size());
            assertNotNull(orderBook.getOrder(9999L));
        }

        @Test
        @DisplayName("修改价格应切换到新档位")
        void amendOrder_changePrice_shouldSwitchDepthLine() {
            MatchOrder order = buildBuyOrder(1001L, new BigDecimal("50000"), new BigDecimal("1"));
            orderBook.addOrder(order);

            // 构建新订单对象：同orderId，新价格
            MatchOrder amendedOrder = buildBuyOrder(1001L, new BigDecimal("51000"), new BigDecimal("1"));
            orderBook.amendOrder(amendedOrder, order);

            // 原档位应被清理
            assertNull(orderBook.getBuyOrders().get(new BigDecimal("50000")));
            // 新档位应存在
            assertNotNull(orderBook.getBuyOrders().get(new BigDecimal("51000")));
            // 订单仍在orderMap中
            assertNotNull(orderBook.getOrder(1001L));
            assertEquals(new BigDecimal("51000"), orderBook.getOrder(1001L).getDelegatePrice());
        }

        @Test
        @DisplayName("修改数量应保持在原档位")
        void amendOrder_changeQty_shouldStayInSameDepthLine() {
            MatchOrder order = buildBuyOrder(1001L, new BigDecimal("50000"), new BigDecimal("1"));
            orderBook.addOrder(order);

            // 构建新订单对象：同orderId同价格，新数量
            MatchOrder amendedOrder = buildBuyOrder(1001L, new BigDecimal("50000"), new BigDecimal("5"));
            orderBook.amendOrder(amendedOrder, order);

            // 档位仍存在
            DepthLine depthLine = orderBook.getBuyOrders().get(new BigDecimal("50000"));
            assertNotNull(depthLine);
            // 订单数量已更新
            assertEquals(new BigDecimal("5"), orderBook.getOrder(1001L).getDelegateCount());
        }
    }

    // ==================== getBook / getOppositeBook 测试 ====================

    @Nested
    @DisplayName("获取订单簿方向 getBook / getOppositeBook")
    class GetBookTest {

        @Test
        @DisplayName("买单应返回买单簿")
        void getBook_buyOrder_shouldReturnBuyOrders() {
            MatchOrder buyOrder = buildBuyOrder(1001L, new BigDecimal("50000"), new BigDecimal("1"));

            assertSame(orderBook.getBuyOrders(), orderBook.getBook(buyOrder));
        }

        @Test
        @DisplayName("卖单应返回卖单簿")
        void getBook_sellOrder_shouldReturnSellOrders() {
            MatchOrder sellOrder = buildSellOrder(1001L, new BigDecimal("50000"), new BigDecimal("1"));

            assertSame(orderBook.getSellOrders(), orderBook.getBook(sellOrder));
        }

        @Test
        @DisplayName("买单的对手盘应是卖单簿")
        void getOppositeBook_buyOrder_shouldReturnSellOrders() {
            MatchOrder buyOrder = buildBuyOrder(1001L, new BigDecimal("50000"), new BigDecimal("1"));

            assertSame(orderBook.getSellOrders(), orderBook.getOppositeBook(buyOrder));
        }

        @Test
        @DisplayName("卖单的对手盘应是买单簿")
        void getOppositeBook_sellOrder_shouldReturnBuyOrders() {
            MatchOrder sellOrder = buildSellOrder(1001L, new BigDecimal("50000"), new BigDecimal("1"));

            assertSame(orderBook.getBuyOrders(), orderBook.getOppositeBook(sellOrder));
        }
    }

    // ==================== getBestBidPrice / getBestAskPrice 测试 ====================

    @Nested
    @DisplayName("最优价格 getBestBidPrice / getBestAskPrice")
    class BestPriceTest {

        @Test
        @DisplayName("买盘为空时最优买价应为null")
        void getBestBidPrice_emptyBook_shouldReturnNull() {
            assertNull(orderBook.getBestBidPrice());
        }

        @Test
        @DisplayName("卖盘为空时最优卖价应为null")
        void getBestAskPrice_emptyBook_shouldReturnNull() {
            assertNull(orderBook.getBestAskPrice());
        }

        @Test
        @DisplayName("最优买价应是买盘中的最高价")
        void getBestBidPrice_shouldReturnHighestBuyPrice() {
            orderBook.addOrder(buildBuyOrder(1001L, new BigDecimal("49000"), new BigDecimal("1")));
            orderBook.addOrder(buildBuyOrder(1002L, new BigDecimal("51000"), new BigDecimal("1")));
            orderBook.addOrder(buildBuyOrder(1003L, new BigDecimal("50000"), new BigDecimal("1")));

            assertEquals(new BigDecimal("51000"), orderBook.getBestBidPrice());
        }

        @Test
        @DisplayName("最优卖价应是卖盘中的最低价")
        void getBestAskPrice_shouldReturnLowestSellPrice() {
            orderBook.addOrder(buildSellOrder(1001L, new BigDecimal("51000"), new BigDecimal("1")));
            orderBook.addOrder(buildSellOrder(1002L, new BigDecimal("49000"), new BigDecimal("1")));
            orderBook.addOrder(buildSellOrder(1003L, new BigDecimal("50000"), new BigDecimal("1")));

            assertEquals(new BigDecimal("49000"), orderBook.getBestAskPrice());
        }
    }

    // ==================== couldMatchImmediately 测试 ====================

    @Nested
    @DisplayName("价格交叉检测 couldMatchImmediately")
    class CouldMatchImmediatelyTest {

        @Test
        @DisplayName("买单 - 卖盘为空时不交叉")
        void couldMatchImmediately_buyOrder_emptySellBook_shouldReturnFalse() {
            MatchOrder buyOrder = buildBuyOrder(1001L, new BigDecimal("50000"), new BigDecimal("1"));

            assertFalse(orderBook.isCross(buyOrder));
        }

        @Test
        @DisplayName("买单 - 价格 >= 最优卖价时交叉")
        void couldMatchImmediately_buyOrder_priceGeAsk_shouldReturnTrue() {
            // 先挂卖单
            orderBook.addOrder(buildSellOrder(1000L, new BigDecimal("50000"), new BigDecimal("1")));

            // 买单价格 = 卖价（交叉）
            MatchOrder buyOrderEqual = buildBuyOrder(1001L, new BigDecimal("50000"), new BigDecimal("1"));
            assertTrue(orderBook.isCross(buyOrderEqual));

            // 买单价格 > 卖价（交叉）
            MatchOrder buyOrderHigher = buildBuyOrder(1002L, new BigDecimal("51000"), new BigDecimal("1"));
            assertTrue(orderBook.isCross(buyOrderHigher));
        }

        @Test
        @DisplayName("买单 - 价格 < 最优卖价时不交叉")
        void couldMatchImmediately_buyOrder_priceLtAsk_shouldReturnFalse() {
            // 先挂卖单
            orderBook.addOrder(buildSellOrder(1000L, new BigDecimal("50000"), new BigDecimal("1")));

            // 买单价格 < 卖价（不交叉）
            MatchOrder buyOrder = buildBuyOrder(1001L, new BigDecimal("49000"), new BigDecimal("1"));
            assertFalse(orderBook.isCross(buyOrder));
        }

        @Test
        @DisplayName("卖单 - 买盘为空时不交叉")
        void couldMatchImmediately_sellOrder_emptyBuyBook_shouldReturnFalse() {
            MatchOrder sellOrder = buildSellOrder(1001L, new BigDecimal("50000"), new BigDecimal("1"));

            assertFalse(orderBook.isCross(sellOrder));
        }

        @Test
        @DisplayName("卖单 - 价格 <= 最优买价时交叉")
        void couldMatchImmediately_sellOrder_priceLeBid_shouldReturnTrue() {
            // 先挂买单
            orderBook.addOrder(buildBuyOrder(1000L, new BigDecimal("50000"), new BigDecimal("1")));

            // 卖单价格 = 买价（交叉）
            MatchOrder sellOrderEqual = buildSellOrder(1001L, new BigDecimal("50000"), new BigDecimal("1"));
            assertTrue(orderBook.isCross(sellOrderEqual));

            // 卖单价格 < 买价（交叉）
            MatchOrder sellOrderLower = buildSellOrder(1002L, new BigDecimal("49000"), new BigDecimal("1"));
            assertTrue(orderBook.isCross(sellOrderLower));
        }

        @Test
        @DisplayName("卖单 - 价格 > 最优买价时不交叉")
        void couldMatchImmediately_sellOrder_priceGtBid_shouldReturnFalse() {
            // 先挂买单
            orderBook.addOrder(buildBuyOrder(1000L, new BigDecimal("50000"), new BigDecimal("1")));

            // 卖单价格 > 买价（不交叉）
            MatchOrder sellOrder = buildSellOrder(1001L, new BigDecimal("51000"), new BigDecimal("1"));
            assertFalse(orderBook.isCross(sellOrder));
        }
    }

    // ==================== printOrderBook 测试 ====================

    @Nested
    @DisplayName("打印订单簿 printOrderBook")
    class PrintOrderBookTest {

        @Test
        @DisplayName("打印订单簿不应抛出异常")
        void printOrderBook_shouldNotThrow() {
            orderBook.addOrder(buildBuyOrder(1001L, new BigDecimal("50000"), new BigDecimal("1")));
            orderBook.addOrder(buildSellOrder(1002L, new BigDecimal("51000"), new BigDecimal("1")));

            assertDoesNotThrow(() -> orderBook.printOrderBook("placeOrder"));
        }
    }

    // ==================== 综合场景测试 ====================

    @Nested
    @DisplayName("综合场景")
    class IntegrationTest {

        @Test
        @DisplayName("模拟完整的下单撤单流程")
        void fullOrderLifecycle() {
            // 1. 挂3个买单
            orderBook.addOrder(buildBuyOrder(1001L, new BigDecimal("49000"), new BigDecimal("1")));
            orderBook.addOrder(buildBuyOrder(1002L, new BigDecimal("50000"), new BigDecimal("2")));
            orderBook.addOrder(buildBuyOrder(1003L, new BigDecimal("50000"), new BigDecimal("3")));

            // 2. 挂2个卖单
            orderBook.addOrder(buildSellOrder(2001L, new BigDecimal("51000"), new BigDecimal("1")));
            orderBook.addOrder(buildSellOrder(2002L, new BigDecimal("52000"), new BigDecimal("2")));

            // 验证初始状态
            assertEquals(2, orderBook.getBuyOrders().size());  // 49000, 50000 两个档位
            assertEquals(2, orderBook.getSellOrders().size()); // 51000, 52000 两个档位
            assertEquals(5, orderBook.getOrderMap().size());
            assertEquals(new BigDecimal("50000"), orderBook.getBestBidPrice());
            assertEquals(new BigDecimal("51000"), orderBook.getBestAskPrice());

            // 3. 撤销一个买单（同档位还有其他订单）
            orderBook.removeOrder(orderBook.getOrder(1002L));
            assertEquals(4, orderBook.getOrderMap().size());
            assertEquals(2, orderBook.getBuyOrders().size()); // 档位仍在

            // 4. 撤销另一个同价位买单（档位应被清理）
            orderBook.removeOrder(orderBook.getOrder(1003L));
            assertEquals(3, orderBook.getOrderMap().size());
            assertEquals(1, orderBook.getBuyOrders().size()); // 50000档位被清理，只剩49000
            assertEquals(new BigDecimal("49000"), orderBook.getBestBidPrice());

            // 5. 改单（改价格）- 构建新订单对象，同orderId新价格
            MatchOrder originalSellOrder = orderBook.getOrder(2001L);
            MatchOrder amendedOrder = buildSellOrder(2001L, new BigDecimal("53000"), new BigDecimal("1"));
            orderBook.amendOrder(amendedOrder, originalSellOrder);

            assertNull(orderBook.getSellOrders().get(new BigDecimal("51000"))); // 原档位清理
            assertNotNull(orderBook.getSellOrders().get(new BigDecimal("53000"))); // 新档位存在
            assertEquals(new BigDecimal("52000"), orderBook.getBestAskPrice()); // 最优卖价变化
        }

        @Test
        @DisplayName("买卖盘价格不交叉时双边挂单正常")
        void bidAskSpread_noMatch() {
            // 买盘最高价 50000
            orderBook.addOrder(buildBuyOrder(1001L, new BigDecimal("50000"), new BigDecimal("1")));
            // 卖盘最低价 51000
            orderBook.addOrder(buildSellOrder(2001L, new BigDecimal("51000"), new BigDecimal("1")));

            // 新买单 49000（低于卖盘，不交叉）
            MatchOrder newBuy = buildBuyOrder(1002L, new BigDecimal("49000"), new BigDecimal("1"));
            assertFalse(orderBook.isCross(newBuy));

            // 新卖单 52000（高于买盘，不交叉）
            MatchOrder newSell = buildSellOrder(2002L, new BigDecimal("52000"), new BigDecimal("1"));
            assertFalse(orderBook.isCross(newSell));
        }
    }
}
