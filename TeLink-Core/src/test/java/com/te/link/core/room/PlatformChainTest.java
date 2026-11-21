package com.te.link.core.room;

import com.te.link.platform.message.PushMessageQuery;
import com.te.link.platform.message.PushMessageStatus;
import com.te.link.platform.operations.NodeOperationWorker;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 不连 Redis、MySQL 或模型，核对状态顺序和“未启用时不执行”的边界。
 *
 * @Author: Talen
 */
public class PlatformChainTest {
    @Test
    public void messageStatusRanksMatchDeliveryOrder() {
        assertTrue(PushMessageStatus.RECEIVED.getRank() < PushMessageStatus.ACCEPTED.getRank());
        assertTrue(PushMessageStatus.ACCEPTED.getRank() < PushMessageStatus.BUSINESS_PROCESSED.getRank());
        assertTrue(PushMessageStatus.BUSINESS_PROCESSED.getRank() < PushMessageStatus.ROUTING.getRank());
        assertTrue(PushMessageStatus.FAILED.getRank() < PushMessageStatus.DELIVERED.getRank());
    }

    @Test
    public void numericMessageFiltersStaySeparateFromOrderBy() {
        String sql = PushMessageQuery.SELECT_PAGE_BASE
                + PushMessageQuery.AND_UID
                + PushMessageQuery.AND_ROOM_ID
                + PushMessageQuery.ORDER_PAGE;
        assertTrue(sql.contains("?\nORDER BY") || sql.contains("?\r\nORDER BY"));
    }

    @Test
    public void disabledWorkerDoesNotTouchTheJvm() throws Exception {
        AtomicBoolean gc = new AtomicBoolean();
        NodeOperationWorker worker = new NodeOperationWorker(null, null, () -> {
            throw new AssertionError("snapshot");
        }, new NodeOperationWorker.Actions() {
            @Override
            public void requestGc() {
                gc.set(true);
            }

            @Override
            public void restart() {
                gc.set(true);
            }
        });
        worker.tick();
        assertFalse(gc.get());
    }
}
