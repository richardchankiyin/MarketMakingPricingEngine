package com.richard.marketmakingpricing.tca;

import com.richard.marketmakingpricing.oms.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.Map;

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
        order2.setExecutionReport(new LTExecutionReport("CLIENT_B", 2L, ExecutionReportStatus.REJECTED, 0, 0, 0, 111, "Price Error"));
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

    
    @Test
    void testTCAManagerRejectReasonCounting() {
        String clientId = "LT_17";
        
        // 1. Known Reject 101
        LTOrder order101 = createRejectOrder(clientId, true, 100, 1.10, 101, "Price/Size Fail");
        
        // 2. Known Reject 102
        LTOrder order102 = createRejectOrder(clientId, true, 100, 1.10, 102, "Liquidity Fail");

        // Act
        tcaManager.onOMSReply(order101);
        tcaManager.onOMSReply(order101); // Increment count to 2
        tcaManager.onOMSReply(order102);

        // Assert
        ClientMetrics metrics = tcaManager.getClientMetrics(clientId);
        Map<Integer, Integer> dist = metrics.getRejectReasonCounts();
        
        assertEquals(2, dist.get(101));
        assertEquals(1, dist.get(102));
        assertEquals(3, metrics.getRejectCount());
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
    
    /**
     * Helper to create a rejected LTOrder with a specific reject reason code
     */
    /**
     * Helper to create a rejected LTOrder matching your specific constructor
     */
    private LTOrder createRejectOrder(String clientId, boolean isBuy, int qty, double limit, int reasonCode, String reasonText) {
        long now = System.currentTimeMillis();
        long clOrdID = System.nanoTime(); // Match the 'long' requirement
        
        LTOrder order = new LTOrder(System.nanoTime(), clientId, isBuy, qty, limit, now);
        
        // Constructor: (targetClientId, clOrdID, status, px, qty, time, rejectReason, text)
        order.setExecutionReport(new LTExecutionReport(
            clientId, 
            clOrdID, 
            ExecutionReportStatus.REJECTED, 
            0.0, 
            0, 
            now, 
            reasonCode, 
            reasonText
        ));
        
        return order;
    }
}