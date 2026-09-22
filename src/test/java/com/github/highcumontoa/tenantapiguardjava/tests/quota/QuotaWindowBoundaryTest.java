package com.github.highcumontoa.tenantapiguardjava.tests.quota;

import com.github.highcumontoa.tenantapiguardjava.common.TimeSource;
import com.github.highcumontoa.tenantapiguardjava.config.GuardProperties;
import com.github.highcumontoa.tenantapiguardjava.quota.QuotaDecision;
import com.github.highcumontoa.tenantapiguardjava.quota.QuotaService;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 窗口边界场景(可控时间):
 * 窗口长度 1000ms、2 个子桶 => 桶长 500ms,有效滑动窗口 1000ms。
 * 验证窗口切换后旧计数滚出、新计数生效,不重复计数、不漏计数。
 */
class QuotaWindowBoundaryTest {

    private static final Logger log = LoggerFactory.getLogger(QuotaWindowBoundaryTest.class);

    private static final class MutableTimeSource implements TimeSource {
        private final AtomicLong t = new AtomicLong(0);

        @Override
        public long nowMillis() {
            return t.get();
        }

        void set(long millis) {
            t.set(millis);
        }
    }

    private void check(QuotaDecision d, boolean expected, String label) {
        log.info("input {} -> allowed={} used={} limit={} retryAfterMillis={}",
                label, d.allowed(), d.used(), d.limit(), d.retryAfterMillis());
        assertTrue(d.allowed() == expected,
                label + " expected allowed=" + expected + " but was " + d.allowed());
    }

    @Test
    void countsSlideAcrossBucketAndWindowBoundaries() {
        MutableTimeSource clock = new MutableTimeSource();
        GuardProperties props = new GuardProperties();
        props.getQuota().setWindowMillis(1000);
        props.getQuota().setBucketsPerWindow(2);
        props.getQuota().setDefaultLimit(3);
        QuotaService service = new QuotaService(props, clock);
        String t = "t-boundary";

        // 桶0 [0,500):打满 3 个
        clock.set(100);
        check(service.tryAcquire(t), true, "t=100 req#1");
        check(service.tryAcquire(t), true, "t=100 req#2");
        check(service.tryAcquire(t), true, "t=100 req#3");
        check(service.tryAcquire(t), false, "t=100 req#4 must reject within window");

        // 桶1 [500,1000):桶0 仍在窗口内,合计不得超放
        clock.set(600);
        check(service.tryAcquire(t), false, "t=600 old bucket still counts");

        // t=1100:当前桶 [1000,1500)。桶0 [0,500) 与真实窗口 (100,1100] 在 [100,500) 重叠,
        // 按“永不超放”的保守语义仍计入 => 已用 3,拒绝;桶2槽(slot0)承载桶0计数并被保留。
        clock.set(1100);
        check(service.tryAcquire(t), false, "t=1100 overlapping bucket still counted");

        // 该拒绝不产生新计数;推进到 t=1600(当前桶 [1500,2000))后首次请求,
        // 桶2 [1000,1500) 与真实窗口 (600,1600] 重叠仍被计入(此刻为 0),桶0 已完全滚出。
        clock.set(1600);
        check(service.tryAcquire(t), true, "t=1600 req#1 (bucket0 gone, current fresh)");
        // 同桶再来两个,新桶累计 3 => 打满
        check(service.tryAcquire(t), true, "t=1600 req#2");
        check(service.tryAcquire(t), true, "t=1600 req#3");
        check(service.tryAcquire(t), false, "t=1600 req#4 reject again");

        // 再推进到 t=2100(桶 [2000,2500)):桶2 [1000,1500) 与真实窗口 (1100,2100]
        // 在 [1100,1500) 重叠,其 3 个计数仍被保守计入 => 仍拒绝,不能超放。
        clock.set(2100);
        check(service.tryAcquire(t), false, "t=2100 old bucket still overlaps, reject");

        // t=2600(桶序号5):桶3(序号3,t=1600 产生的 3 个计数)与窗口仍重叠,
        // 按保守语义继续计入 => 仍拒绝。
        clock.set(2600);
        check(service.tryAcquire(t), false, "t=2600 bucket-3 still overlaps, reject");

        // t=3100(桶序号6):桶3 已完全滚出,当前桶全新,重新获得完整配额;
        // 历史计数不被重复计入,验证窗口切换确定性。
        clock.set(3100);
        check(service.tryAcquire(t), true, "t=3100 req#1 after old bucket fully exits");
        check(service.tryAcquire(t), true, "t=3100 req#2");
        check(service.tryAcquire(t), true, "t=3100 req#3");
        check(service.tryAcquire(t), false, "t=3100 req#4 reject");

        log.info("window boundary test completed: rollover reset is deterministic");
    }

    @Test
    void noDoubleCountAfterLargeTimeGap() {
        MutableTimeSource clock = new MutableTimeSource();
        GuardProperties props = new GuardProperties();
        props.getQuota().setWindowMillis(1000);
        props.getQuota().setBucketsPerWindow(2);
        props.getQuota().setDefaultLimit(1);
        QuotaService service = new QuotaService(props, clock);

        clock.set(100);
        QuotaDecision first = service.tryAcquire("t-gap");
        log.info("input t=100 -> allowed={} used={}", first.allowed(), first.used());
        assertTrue(first.allowed());

        // 跨越多个完整窗口后再请求,旧桶必须全部清零,不能被重复计入
        clock.set(50_000);
        QuotaDecision afterGap = service.tryAcquire("t-gap");
        log.info("input t=50000 after long gap -> allowed={} used={} (old counters must be gone)",
                afterGap.allowed(), afterGap.used());
        assertTrue(afterGap.allowed(), "跨越多窗口后必须重新计数");
        assertFalse(afterGap.allowed() && afterGap.used() > 1, "不得把历史计数带入新窗口");
    }
}
