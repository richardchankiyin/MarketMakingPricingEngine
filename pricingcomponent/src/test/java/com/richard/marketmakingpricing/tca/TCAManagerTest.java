package com.richard.marketmakingpricing.tca;

import com.richard.marketmakingpricing.oms.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class TCAManagerTest {

    private TCAManager tcaManager;
    private TCAUpdateListener mockListener;

    @BeforeEach
    void setUp() {
        mockListener = mock(TCAUpdateListener.class);
        tcaManager = new TCAManager();
        tcaManager.addListener(mockListener);
    }

    @Test
    void testFirmAndClientMetricsCalculation() {
        // --- Scenario 1: CLIENT_A Successful Buy ---
        // Client buys from us @ 1.1005, we hedge @ 1.1000 (Profit: 5 pips)
        LTOrder order1 = createFilledOrder("CLIENT_A", true, 100, 1.1005, 1.1000);
        tcaManager.onOMSReply(order1);

        // --- Scenario 2: CLIENT_B Rejected Order ---
        LTOrder order2 = new LTOrder(2L, "CLIENT_B", true, 100, 1.1000, System.currentTimeMillis());
        order2.setExecutionReport(new LTExecutionReport("CLIENT_B", 2L, ExecutionReportStatus.REJECTED, 0, 0, 0, "Price Error"));
        tcaManager.onOMSReply(order2);

        // Capture the metrics sent to the listener
        ArgumentCaptor<ClientMetrics> clientCaptor = ArgumentCaptor.forClass(ClientMetrics.class);
        ArgumentCaptor<FirmMetrics> firmCaptor = ArgumentCaptor.forClass(FirmMetrics.class);
        
        // Verify broadcast happened twice (once for each order)
        verify(mockListener, times(2)).onTCAUpdate(clientCaptor.capture(), firmCaptor.capture());

        FirmMetrics finalFirm = firmCaptor.getValue();
        ClientMetrics statsA = clientCaptor.getAllValues().get(0);

        // Assert Firm Metrics
        assertEquals(2, finalFirm.getTotalOrders(), "Total orders should include rejections");
        assertEquals(1, finalFirm.getTotalFills());
        assertEquals(0.5, finalFirm.getFillRate(), 0.01); // 1/2 = 50%
        assertTrue(finalFirm.getTotalPnL() > 0);

        // Assert Client A Metrics
        assertEquals("CLIENT_A", statsA.getClientId());
        assertEquals(1, statsA.getFillCount());
        assertEquals(4.54, statsA.getAverageBps(), 0.1); // (0.0005 / 1.1005) * 10000
    }

    @Test
    void testToxicityDetectionForSpecificClient() {
        String toxicClientId = "HFT_ARBITRAGE";

        // Simulate 10 fills where we lose money (Hedge price worse than Taker price)
        // Taker sells to us @ 1.1000, we hedge @ 1.0995 (Loss: 5 pips per trade)
        for (int i = 0; i < 10; i++) {
            LTOrder badOrder = createFilledOrder(toxicClientId, false, 100, 1.1000, 1.0995);
            tcaManager.onOMSReply(badOrder);
        }

        // Capture the last broadcast
        ArgumentCaptor<ClientMetrics> clientCaptor = ArgumentCaptor.forClass(ClientMetrics.class);
        verify(mockListener, atLeastOnce()).onTCAUpdate(clientCaptor.capture(), any());

        ClientMetrics toxicStats = clientCaptor.getValue();

        // Assert Toxicity
        assertTrue(toxicStats.isToxic(), "Client should be marked toxic after 10 loss-making trades");
        assertTrue(toxicStats.getAverageBps() < 0);
        assertEquals(10, toxicStats.getFillCount());
    }

    /**
     * Helper to create a fully populated LTOrder with a single Hedge child
     */
    private LTOrder createFilledOrder(String clientId, boolean isBuy, int qty, double takerPx, double hedgePx) {
        LTOrder order = new LTOrder(System.nanoTime(), clientId, isBuy, qty, takerPx + 0.01, System.currentTimeMillis());
        
        // Set Taker Fill
        order.setExecutionReport(new LTExecutionReport(clientId, 1L, ExecutionReportStatus.FILLED, takerPx, qty, 0, ""));
        
        // Set Hedge Fill
        HedgeOrder ho = new HedgeOrder("LP_1", isBuy, qty, hedgePx, 0);
        ho.setExecutionReport(new HedgeExecutionReport("LP_1", 2L, ExecutionReportStatus.FILLED, hedgePx, qty, 0));
        order.addHedgeOrder(ho);
        
        return order;
    }
}