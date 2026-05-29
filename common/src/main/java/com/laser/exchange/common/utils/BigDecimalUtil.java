package com.laser.exchange.common.utils;

import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.util.Objects;

@Slf4j
public class BigDecimalUtil {


    public static final int DEF_SCALE = 16;
    public static final int INT_ZERO = 0;

    public static String format(final BigDecimal num, final Integer precision) {
        if (num == null) {
            return null;
        }
        if (precision == null) {
            return num.stripTrailingZeros().toPlainString();
        }
        return num.setScale(precision, RoundingMode.FLOOR).stripTrailingZeros().toPlainString();
    }

    public static String formatThousand(final BigDecimal num) {
        if (num == null) {
            return "0";
        }
        final DecimalFormat df = new DecimalFormat("#,##0.0000");
        return df.format(num);
    }

    public static String formatPercent(final BigDecimal num) {
        final String pattern = "0.00%";

        if (num == null) {
            return pattern;
        }

        return new DecimalFormat(pattern).format(num);
    }

    public static BigDecimal stringToBigDecimal(final String s) {
        if (StringUtils.isNotBlank(s) && !"null".equalsIgnoreCase(s)) {
            try {
                BigDecimal bigDecimal = new BigDecimal(s);
                if (bigDecimal.scale() > DEF_SCALE) {
                    log.error("stringToBigDecimal scale > 16, {}", s);
                    bigDecimal = bigDecimal.setScale(DEF_SCALE, RoundingMode.HALF_UP);
                }
                return bigDecimal;

            } catch (final Exception e) {
                BigDecimalUtil.log.error("format big decimal error, s:{}", s);
            }
        }
        return BigDecimal.ZERO;
    }

    public static String defaultToString(final BigDecimal val) {
        if (Objects.isNull(val) || val.compareTo(BigDecimal.ZERO) == 0) {
            return "0";
        }

        BigDecimal result = val.scale() <= DEF_SCALE
                ? val
                : val.setScale(DEF_SCALE, RoundingMode.HALF_UP);

        return result.stripTrailingZeros().toPlainString();
    }

    public static String bigDecimalToString(final BigDecimal val) {
        if (Objects.isNull(val) || val.compareTo(BigDecimal.ZERO) == 0) {
            return StringUtils.EMPTY;
        }

        BigDecimal result = val.scale() <= DEF_SCALE
                ? val
                : val.setScale(DEF_SCALE, RoundingMode.HALF_UP);

        return result.stripTrailingZeros().toPlainString();
    }

    public static String formatBigDecimal(final BigDecimal val) {
        return format(val, DEF_SCALE);
    }

    public static String toString(final BigDecimal val) {
        if (val != null) {
            return val.stripTrailingZeros().toPlainString();
        }
        return "0";
    }

    public static String formatScaleString(final BigDecimal val) {
        if (val == null) {
            return null;
        }
        return toString(val.setScale(DEF_SCALE, RoundingMode.HALF_UP));
    }


    public static String formatEmpty(final BigDecimal num, final Integer precision) {
        if (num == null) {
            return "0";
        }
        if (precision == null) {
            return num.stripTrailingZeros().toPlainString();
        }
        return num.setScale(precision, RoundingMode.FLOOR).toPlainString();
    }

    public static String formatZERO(final BigDecimal num, final Integer precision) {
        if (num == null || num.compareTo(BigDecimal.ZERO) == 0) {
            return "";
        }
        if (precision == null) {
            return num.stripTrailingZeros().toPlainString();
        }
        return num.setScale(precision, RoundingMode.FLOOR).toPlainString();
    }

    public static String formatEmptyAndZERO(final BigDecimal num, final Integer precision) {
        if (num == null) {
            return "";
        }
        if (num.compareTo(BigDecimal.ZERO) == 0) {
            return "0";
        }
        if (precision == null) {
            return num.stripTrailingZeros().toPlainString();
        }
        return format(num, precision);
    }

    public static BigDecimal formatBig(final BigDecimal num, final Integer precision) {
        if (num == null) {
            return null;
        }
        if (precision == null) {
            return num;
        }
        return num.setScale(precision, RoundingMode.FLOOR);
    }

    public static BigDecimal formatBigUp(final BigDecimal num, final Integer precision) {
        if (num == null) {
            return null;
        }
        if (precision == null) {
            return num;
        }
        return num.setScale(precision, RoundingMode.CEILING);
    }

    public static String formatStripZero(final BigDecimal num, final Integer precision) {
        if (num == null) {
            return null;
        }
        if (precision == null) {
            return num.stripTrailingZeros().toPlainString();
        }
        return num.setScale(precision, RoundingMode.FLOOR).stripTrailingZeros().toPlainString();
    }

    public static String formatPositive(BigDecimal num, final Integer precision) {
        if (num == null) {
            num = BigDecimal.ZERO;
        }
        if (num.compareTo(BigDecimal.ZERO) < 0) {
            num = BigDecimal.ZERO;
        }
        if (precision == null) {
            return num.stripTrailingZeros().toPlainString();
        }
        return num.setScale(precision, RoundingMode.FLOOR).toPlainString();
    }

    public static String formatUp(final BigDecimal num, final Integer precision) {
        if (num == null) {
            return null;
        }
        if (precision == null) {
            return num.stripTrailingZeros().toPlainString();
        }
        return num.setScale(precision, RoundingMode.CEILING).toPlainString();
    }

    public static BigDecimal formatNum(final BigDecimal num, final Integer precision, final RoundingMode roundingMode) {
        if (num == null) {
            return BigDecimal.ZERO;
        }
        if (precision == null) {
            return num;
        }
        return num.setScale(precision, roundingMode);
    }

    public static String formatLiquidation(final BigDecimal num, final Integer precision) {
        if (num == null) {
            return "0";
        }
        if (num.compareTo(BigDecimal.ZERO) >= 0) {
            return "0";
        }
        if (precision == null) {
            return num.stripTrailingZeros().toPlainString();
        }
        return num.setScale(precision, RoundingMode.CEILING).toPlainString();
    }

    public static String formatFunding(final BigDecimal num, final Integer precision) {
        if (num == null) {
            return null;
        }
        if (precision == null) {
            return num.stripTrailingZeros().toPlainString();
        }
        return num.setScale(precision, RoundingMode.FLOOR).stripTrailingZeros().toPlainString();
    }


    public static BigDecimal add(final BigDecimal a, final BigDecimal b) {
        if (a == null && b == null) {
            return BigDecimal.ZERO;
        }
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.add(b);
    }

    public static BigDecimal addList(final BigDecimal p1, final BigDecimal p2) {
        return formatNull(p1).add(formatNull(p2));
    }

    public static BigDecimal addList(final BigDecimal p1, final BigDecimal p2, final BigDecimal p3) {
        return formatNull(p1).add(formatNull(p2)).add(formatNull(p3));
    }

    public static BigDecimal addList(final BigDecimal p1, final BigDecimal p2, final BigDecimal p3, final BigDecimal p4) {
        return formatNull(p1).add(formatNull(p2)).add(formatNull(p3)).add(formatNull(p4));
    }

    public static BigDecimal addList(final BigDecimal p1, final BigDecimal p2, final BigDecimal p3, final BigDecimal p4, final BigDecimal p5) {
        return formatNull(p1).add(formatNull(p2)).add(formatNull(p3)).add(formatNull(p4)).add(formatNull(p5));
    }

    public static BigDecimal addList(final BigDecimal p1, final BigDecimal p2, final BigDecimal p3, final BigDecimal p4, final BigDecimal p5, final BigDecimal p6) {
        return formatNull(p1).add(formatNull(p2)).add(formatNull(p3)).add(formatNull(p4)).add(formatNull(p5)).add(formatNull(p6));
    }

    public static BigDecimal addList(final BigDecimal p1, final BigDecimal p2, final BigDecimal p3, final BigDecimal p4, final BigDecimal p5, final BigDecimal p6, final BigDecimal p7) {
        return formatNull(p1).add(formatNull(p2)).add(formatNull(p3)).add(formatNull(p4)).add(formatNull(p5)).add(formatNull(p6)).add(formatNull(p7));
    }

    public static BigDecimal addList(final BigDecimal p1, final BigDecimal p2, final BigDecimal p3, final BigDecimal p4, final BigDecimal p5, final BigDecimal p6, final BigDecimal p7, final BigDecimal p8) {
        return formatNull(p1).add(formatNull(p2)).add(formatNull(p3)).add(formatNull(p4)).add(formatNull(p5)).add(formatNull(p6)).add(formatNull(p7)).add(formatNull(p8));
    }

    public static BigDecimal subtract(final BigDecimal a, final BigDecimal b) {
        if (a == null && b == null) {
            return BigDecimal.ZERO;
        }
        if (a == null) {
            return b.negate();
        }
        if (b == null) {
            return a;
        }
        return a.subtract(b);
    }


    public static BigDecimal subtractList(final BigDecimal p1, final BigDecimal p2, final BigDecimal p3) {
        return formatNull(p1).subtract(formatNull(p2)).subtract(formatNull(p3));
    }

    public static BigDecimal max(final BigDecimal a, final BigDecimal b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        if (a.compareTo(b) > 0) {
            return a;
        } else {
            return b;
        }
    }

    public static BigDecimal min(final BigDecimal a, final BigDecimal b) {
        if (a == null) {
            return BigDecimal.ZERO;
        }
        if (b == null) {
            return BigDecimal.ZERO;
        }
        if (a.compareTo(b) < 0) {
            return a;
        } else {
            return b;
        }
    }

//    public static BigDecimal convert(final String value) {
//        return StringUtils.isEmpty(value) ? null : new BigDecimal(value);
//    }

    /**
     * fetch the min value of all values
     */
    public static BigDecimal min4Values(final BigDecimal... values) {
        // return zero for empty array
        if (values == null || values.length == INT_ZERO) {
            return BigDecimal.ZERO;
        }

        BigDecimal smaller = null;
        for (BigDecimal v : values) {
            if (v == null) {
                // exclude null value for comparison
                continue;
            }

            if (smaller == null) {
                // initialize smaller (for the first time) a non-null value
                smaller = v;
            }

            // here, both smaller and v could not be null
            smaller = BigDecimalUtil.min(smaller, v);
        }

        // but if all values are null then return zero
        return smaller == null ? BigDecimal.ZERO : smaller;
    }

    public static boolean notEqual(final BigDecimal a, final BigDecimal b) {
        return !equal(a, b);
    }

    public static boolean equal(final BigDecimal a, final BigDecimal b) {
        if (a == null || b == null) {
            return false;
        }
        return a.compareTo(b) == 0;
    }

    public static boolean equalZero(final BigDecimal val) {
        if (val == null) {
            return false;
        }
        return val.compareTo(BigDecimal.ZERO) == 0;
    }

    public static boolean notEqualZero(final BigDecimal val) {
        return !equalZero(val);
    }

    /**
     * return min value or not null value，default BigDecimal.ZERO
     *
     * @param a
     * @param b
     * @return
     */
    public static BigDecimal min2(final BigDecimal a, final BigDecimal b) {
        return a == null ? (b == null ? BigDecimal.ZERO : b) : (b == null ? a : BigDecimalUtil.min(a, b));
    }

    public static boolean isEmptyOrLessEqualZero(final BigDecimal value) {
        return null == value || BigDecimal.ZERO.compareTo(value) >= 0;
    }

    public static boolean isEmptyOrLessZero(final BigDecimal value) {
        return null == value || BigDecimal.ZERO.compareTo(value) > 0;
    }

    public static boolean existNullOrZero(final BigDecimal value) {
        return value == null || BigDecimal.ZERO.compareTo(value) == 0;
    }

    public static boolean hasNullOrZero(final BigDecimal value) {
        return value == null || BigDecimal.ZERO.compareTo(value) == 0;
    }


    public static BigDecimal formatInt(final BigDecimal value) {
        if (value == null) {
            return null;
        }
        return value.setScale(0, RoundingMode.FLOOR);
    }

    public static BigDecimal maxList(final BigDecimal a, final BigDecimal b, final BigDecimal c) {
        if (a == null || b == null || c == null) {
            throw new IllegalStateException("wrong params = " + a + ", " + b + ", " + c);
        }
        return BigDecimalUtil.max(c, BigDecimalUtil.max(a, b));
    }

    public static BigDecimal minList(final BigDecimal a, final BigDecimal b, final BigDecimal c) {
        return formatMin(c, formatMin(a, b));
    }

    public static BigDecimal minList(final BigDecimal a, final BigDecimal b) {
        return formatMin(a, b);
    }

    private static BigDecimal formatMin(final BigDecimal a, final BigDecimal b) {
        if (a == null) {
            return b == null ? BigDecimal.ZERO : b;
        }

        if (b == null) {
            return a;
        }

        return BigDecimalUtil.min(a, b);
    }

    public static BigDecimal convert(final Object key) {
        if (key == null) {
            return BigDecimal.ZERO;
        }
        return new BigDecimal(key.toString());
    }

    public static BigDecimal convertDefault(final BigDecimal key) {
        if (key == null) {
            return BigDecimal.ZERO;
        }
        return key;
    }

    public static double doubleValue(final BigDecimal bigDecimal) {
        if (bigDecimal == null) {
            return 0;
        }
        return bigDecimal.doubleValue();
    }

    public static int intValue(final BigDecimal bigDecimal) {
        if (bigDecimal == null) {
            return 0;
        }
        return bigDecimal.intValue();
    }

    public static long longValue(final BigDecimal bigDecimal) {
        if (bigDecimal == null) {
            return 0;
        }
        return bigDecimal.longValue();
    }

    public static BigDecimal formatScale(final BigDecimal val) {
        if (val == null) {
            return null;
        }
        return val.setScale(DEF_SCALE, RoundingMode.HALF_UP);
    }

    public static double parseDouble(final BigDecimal val) {
        if (val == null) {
            return 0;
        }
        return val.doubleValue();
    }


    public static BigDecimal formatAdd(final BigDecimal p1, final BigDecimal p2) {
        return formatNull(p1).add(formatNull(p2))
                .setScale(DEF_SCALE, RoundingMode.HALF_UP);
    }

    public static BigDecimal formatAdd(final BigDecimal p1, final BigDecimal p2, final BigDecimal p3) {
        return formatNull(p1).add(formatNull(p2)).add(formatNull(p3))
                .setScale(DEF_SCALE, RoundingMode.HALF_UP);
    }

    public static BigDecimal formatAdd(final BigDecimal p1, final BigDecimal p2, final BigDecimal p3, final BigDecimal p4) {
        return formatNull(p1).add(formatNull(p2)).add(formatNull(p3)).add(formatNull(p4))
                .setScale(DEF_SCALE, RoundingMode.HALF_UP);
    }

    public static BigDecimal formatAdd(final BigDecimal p1, final BigDecimal p2, final BigDecimal p3, final BigDecimal p4, final BigDecimal p5) {
        return formatNull(p1).add(formatNull(p2)).add(formatNull(p3)).add(formatNull(p4)).add(formatNull(p5))
                .setScale(DEF_SCALE, RoundingMode.HALF_UP);
    }

    public static BigDecimal formatSubtract(final BigDecimal p1) {
        return formatNull(p1)
                .setScale(DEF_SCALE, RoundingMode.HALF_UP);
    }

    public static BigDecimal formatSubtract(final BigDecimal p1, final BigDecimal p2) {
        return formatNull(p1).subtract(formatNull(p2))
                .setScale(DEF_SCALE, RoundingMode.HALF_UP);
    }

    public static BigDecimal formatSubtract(final BigDecimal p1, final BigDecimal p2, final BigDecimal p3) {
        return formatNull(p1).subtract(formatNull(p2)).subtract(formatNull(p3))
                .setScale(DEF_SCALE, RoundingMode.HALF_UP);
    }

    public static BigDecimal formatSubtract(final BigDecimal p1, final BigDecimal p2, final BigDecimal p3, final BigDecimal p4) {
        return formatNull(p1).subtract(formatNull(p2)).subtract(formatNull(p3)).subtract(formatNull(p4))
                .setScale(DEF_SCALE, RoundingMode.HALF_UP);
    }

    public static BigDecimal formatSubtract(final BigDecimal p1, final BigDecimal p2, final BigDecimal p3, final BigDecimal p4, final BigDecimal p5) {
        return formatNull(p1).subtract(formatNull(p2)).subtract(formatNull(p3)).subtract(formatNull(p4)).subtract(formatNull(p5))
                .setScale(DEF_SCALE, RoundingMode.HALF_UP);
    }

    public static BigDecimal formatSubtract(final BigDecimal p1, final BigDecimal p2, final BigDecimal p3, final BigDecimal p4, final BigDecimal p5, final BigDecimal p6) {
        return formatNull(p1).subtract(formatNull(p2)).subtract(formatNull(p3)).subtract(formatNull(p4)).subtract(formatNull(p5)).subtract(formatNull(p6))
                .setScale(DEF_SCALE, RoundingMode.HALF_UP);
    }

    public static BigDecimal formatSubtract(final BigDecimal p1, final BigDecimal p2, final BigDecimal p3, final BigDecimal p4, final BigDecimal p5, final BigDecimal p6, final BigDecimal p7) {
        return formatNull(p1).subtract(formatNull(p2)).subtract(formatNull(p3)).subtract(formatNull(p4)).subtract(formatNull(p5)).subtract(formatNull(p6)).subtract(formatNull(p7))
                .setScale(DEF_SCALE, RoundingMode.HALF_UP);
    }

    public static BigDecimal formatSubtract(final BigDecimal p1, final BigDecimal p2, final BigDecimal p3, final BigDecimal p4, final BigDecimal p5, final BigDecimal p6, final BigDecimal p7, final BigDecimal p8) {
        return formatNull(p1).subtract(formatNull(p2)).subtract(formatNull(p3)).subtract(formatNull(p4)).subtract(formatNull(p5)).subtract(formatNull(p6)).subtract(formatNull(p7)).subtract(formatNull(p8))
                .setScale(DEF_SCALE, RoundingMode.HALF_UP);
    }

    public static BigDecimal formatSubtract(final BigDecimal p1, final BigDecimal p2, final BigDecimal p3, final BigDecimal p4,
                                            final BigDecimal p5, final BigDecimal p6, final BigDecimal p7, final BigDecimal p8,
                                            final BigDecimal p9) {
        return formatNull(p1).subtract(formatNull(p2)).subtract(formatNull(p3)).subtract(formatNull(p4)).subtract(formatNull(p5)).
                subtract(formatNull(p6)).subtract(formatNull(p7)).subtract(formatNull(p8)).subtract(formatNull(p9))
                .setScale(DEF_SCALE, RoundingMode.HALF_UP);
    }

    public static BigDecimal formatMultiplyHalfDown(final BigDecimal b1, final BigDecimal b2) {
        return formatMultiplyNull(b1).multiply(formatMultiplyNull(b2))
                .setScale(DEF_SCALE, RoundingMode.HALF_DOWN);
    }


    public static BigDecimal formatMultiply(final BigDecimal b1, final BigDecimal b2) {
        return formatMultiplyNull(b1).multiply(formatMultiplyNull(b2))
                .setScale(DEF_SCALE, RoundingMode.HALF_UP);
    }

    public static BigDecimal formatMultiply(final BigDecimal b1, final BigDecimal b2, final BigDecimal b3) {
        return formatMultiplyNull(b1).multiply(formatMultiplyNull(b2)).multiply(formatMultiplyNull(b3))
                .setScale(DEF_SCALE, RoundingMode.HALF_UP);
    }

    public static BigDecimal formatMultiply(final BigDecimal b1, final BigDecimal b2, final BigDecimal b3, final BigDecimal b4) {
        return formatMultiplyNull(b1).multiply(formatMultiplyNull(b2)).multiply(formatMultiplyNull(b3)).multiply(formatMultiplyNull(b4))
                .setScale(DEF_SCALE, RoundingMode.HALF_UP);
    }

    public static BigDecimal formatMultiply(final BigDecimal b1, final BigDecimal b2, final BigDecimal b3, final BigDecimal b4, final BigDecimal b5) {
        return formatMultiplyNull(b1).multiply(formatMultiplyNull(b2)).multiply(formatMultiplyNull(b3)).multiply(formatMultiplyNull(b4)).multiply(formatMultiplyNull(b5))
                .setScale(DEF_SCALE, RoundingMode.HALF_UP);
    }

    public static BigDecimal formatMultiply(final BigDecimal b1, final BigDecimal b2, final BigDecimal b3, final BigDecimal b4, final BigDecimal b5, final BigDecimal b6) {
        return formatMultiplyNull(b1).multiply(formatMultiplyNull(b2)).multiply(formatMultiplyNull(b3)).multiply(formatMultiplyNull(b4)).multiply(formatMultiplyNull(b5)).multiply(formatMultiplyNull(b6))
                .setScale(DEF_SCALE, RoundingMode.HALF_UP);
    }

    public static BigDecimal formatDivide(final BigDecimal b1, final BigDecimal b2) {
        return formatDivide(b1, b2, DEF_SCALE, RoundingMode.HALF_UP);

    }

    public static BigDecimal formatDivide(final BigDecimal b1, final BigDecimal b2, final int scale) {
        return formatDivide(b1, b2, scale, RoundingMode.HALF_UP);
    }

    public static BigDecimal formatDivide(final BigDecimal b1, final BigDecimal b2, final int scale, RoundingMode roundingMode) {
        if (Objects.isNull(b1) || Objects.isNull(b2) || equalZero(b2)) {
            return BigDecimal.ZERO;
        }
        return b1.divide(b2, scale, roundingMode);
    }

    public static BigDecimal valueOf(final String s) {
        return StringUtils.isEmpty(s)
                ? BigDecimal.ZERO
                : new BigDecimal(s).setScale(DEF_SCALE, RoundingMode.HALF_UP);
    }

    public static BigDecimal valueOf(final double d) {
        return BigDecimal.valueOf(d).setScale(DEF_SCALE, RoundingMode.HALF_UP);
    }

    public static BigDecimal getOrDefault(final BigDecimal bd) {
        return bd == null ? BigDecimal.ZERO : bd;
    }

    /**
     * lessZero
     *
     * @param val not null
     */
    public static boolean lessZero(final BigDecimal val) {
        if (val == null) {
            return false;
        }
        return val.compareTo(BigDecimal.ZERO) < 0;
    }

    /**
     * lessEqualZero
     *
     * @param val not null
     */
    public static boolean lessEqualZero(final BigDecimal val) {
        if (val == null) {
            return false;
        }
        return val.compareTo(BigDecimal.ZERO) <= 0;
    }

    /**
     * greaterThanZero
     *
     * @param val not null
     */
    public static boolean greaterThanZero(final BigDecimal val) {
        if (val == null) {
            return false;
        }
        return val.compareTo(BigDecimal.ZERO) > 0;
    }

    /**
     * greaterOrEqualZero
     *
     * @param val not null
     */
    public static boolean greaterOrEqualZero(final BigDecimal val) {
        if (val == null) {
            return false;
        }
        return val.compareTo(BigDecimal.ZERO) >= 0;
    }

    /**
     * return if value1 and value2 are same side
     * 1.0 2.0 true
     * -1.0 2.0 false
     * -1.0 -2.0 true
     */
    public static boolean isSameSide(@NonNull final BigDecimal value1, @NonNull final BigDecimal value2) {
        return value1.signum() == value2.signum();
    }

    /**
     * make targetVal same as baseVal
     */
    public static BigDecimal sameSide(final BigDecimal baseVal, final BigDecimal targetVal) {
        if (greaterThanZero(baseVal) && lessEqualZero(targetVal)) {
            return targetVal.negate();
        }
        if (lessZero(baseVal) && greaterThanZero(targetVal)) {
            return targetVal.negate();
        }

        return targetVal;
    }

    /**
     * varg1 >= varg2
     *
     * @param varg1
     * @param varg2
     * @return
     */
    public static boolean greaterOrEquals(final BigDecimal varg1, final BigDecimal varg2) {
        if (varg1 == null || varg2 == null) {
            throw new ArithmeticException("greaterOrEquals arg is null");
        }

        if (varg1.compareTo(varg2) >= 0) {
            return true;
        }

        return false;
    }

    /**
     * varg1 <= varg2
     *
     * @param varg1
     * @param varg2
     * @return
     */
    public static boolean lessOrEquals(final BigDecimal varg1, final BigDecimal varg2) {
        if (varg1 == null || varg2 == null) {
            throw new ArithmeticException("greaterOrEquals arg is null");
        }

        if (varg1.compareTo(varg2) <= 0) {
            return true;
        }

        return false;
    }

    /**
     * compare two bigDecimal
     *
     * @param arg1
     * @param arg2
     * @return
     * @throws ArithmeticException
     */
    public static boolean greaterThan(BigDecimal arg1, BigDecimal arg2) throws ArithmeticException {
        if (arg1 == null || arg2 == null) {
            throw new ArithmeticException("greaterThan arg is null");
        }

        return arg1.compareTo(arg2) > 0;
    }

    /**
     * compare two bigDecimal
     *
     * @param arg1
     * @param arg2
     * @return
     * @throws ArithmeticException
     */
    public static boolean lessThan(BigDecimal arg1, BigDecimal arg2) throws ArithmeticException {
        if (arg1 == null || arg2 == null) {
            throw new ArithmeticException("lessThan arg is null");
        }

        return arg1.compareTo(arg2) < 0;
    }

    /**
     * if num>0 return num
     * else return defaultNum
     *
     * @return
     */
    public static BigDecimal getNumUnlessZero(final BigDecimal num, final BigDecimal defaultNum) {
        return Objects.nonNull(num) && num.compareTo(BigDecimal.ZERO) > 0 ? num : defaultNum;
    }

    public static BigDecimal clamp(final BigDecimal value, final BigDecimal min, final BigDecimal max) {
        if (value.compareTo(min) <= 0) {
            return min;
        }
        if (value.compareTo(max) >= 0) {
            return max;
        }
        return value;
    }

    public static BigDecimal formatNull(BigDecimal v) {
        if (v == null) {
            return BigDecimal.ZERO;
        }

        return v;
    }

    public static BigDecimal formatMultiplyNull(BigDecimal v) {
        if (v == null) {
            return BigDecimal.ONE;
        }

        return v;
    }

    public static BigDecimal remainder(BigDecimal dividend, BigDecimal divisor) {
        MathContext mc = new MathContext(DEF_SCALE, RoundingMode.HALF_UP);
        return dividend.remainder(divisor, mc);
    }

    public static boolean isDecimalStringGreaterThanZero(final String value) {
        try {
            return new BigDecimal(value).compareTo(BigDecimal.ZERO)>0;
        } catch (Throwable e) {
            return false;
        }
    }
}