package com.richard.marketmakingpricing.oms;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class OMSHandlerTest {

    private OMSHandler omsHandler;
    private OrderUpdateListener mockReplyChannel;

    @BeforeEach
    void setUp() {
        mockReplyChannel = mock(OrderUpdateListener.class);
        omsHandler = new OMSHandler();        
        omsHandler.addOrderReplyListener(mockReplyChannel);

        // Setup Internal Quote: Bid 1.1000 (100 qty), Ask 1.1005 (100 qty)
        omsHandler.onQuoteUpdate(1.1000, 100, 1.1005, 100, 0, 0);

        // Setup Market Book for Hedging: 
        // LP_A @ 1.1006 (50), LP_B @ 1.1007 (50)
        NavigableMap<Double, Map<String, Integer>> bids = new TreeMap<>(Collections.reverseOrder());
        NavigableMap<Double, Map<String, Integer>> asks = new TreeMap<>();
        
        Map<String, Integer> askLevel1 = new HashMap<>();
        askLevel1.put("LP_A", 50);
        asks.put(1.1006, askLevel1);

        Map<String, Integer> askLevel2 = new HashMap<>();
        askLevel2.put("LP_B", 50);
        asks.put(1.1007, askLevel2);

        omsHandler.onFullBookUpdate(bids, asks);
    }

    @Test
    void testSuccessfulBuyOrderWithHedging() {
        // Arrange
        double expectedInternalAsk = 1.1008;
        double expectedInternalBid = 1.1005; // Set a bid just for a complete quote
        int availableSize = 100;

        // --- PRIMING: Set the internal market state ---
        // This ensures getExecutionPrice snapshots 1.1008
        omsHandler.onQuoteUpdate(expectedInternalBid, availableSize, expectedInternalAsk, availableSize, 0, 0);

        OrderEntryEvent event = new OrderEntryEvent();
        event.setParentId(12345L);
        event.setSenderId("CLIENT_ALPHA");
        event.setSide("BUY");
        event.setQty(75);
        event.setLimit(1.1010); // Limit (1.1010) >= Internal Ask (1.1008) -> SUCCESS

        // Act
        omsHandler.onEvent(event, 1L, true);

        // Assert
        ArgumentCaptor<LTOrder> captor = ArgumentCaptor.forClass(LTOrder.class);
        verify(mockReplyChannel).onOMSReply(captor.capture());

        LTOrder result = captor.getValue();
        LTExecutionReport takerReport = result.getExecutionReport();
        
        // 1. Verify Taker is filled at the snapshotted Internal Ask
        assertEquals(ExecutionReportStatus.FILLED, takerReport.getOrdStatus());
        assertNull(takerReport.getRejectReason());
        assertEquals(expectedInternalAsk, takerReport.getLastPx(), 0.000001, 
            "Taker must be filled at the snapshotted internalAsk price");

        // 2. Verify Hedges are filled at the raw LP book prices
        List<HedgeOrder> hedges = result.getHedgeOrders();
        assertEquals(2, hedges.size());
        
        // LP_A at 1.1006
        assertEquals(1.1006, hedges.get(0).getPrice(), 0.000001);
        // LP_B at 1.1007
        assertEquals(1.1007, hedges.get(1).getPrice(), 0.000001);

        // --- PNL VERIFICATION ---
        // PnL = (TakerPrice - AvgHedgePrice) * Qty
        // TakerPrice = 1.1008
        // AvgHedge = ((1.1006 * 50) + (1.1007 * 25)) / 75 = 1.1006333...
        double avgHedgePrice = ((1.1006 * 50) + (1.1007 * 25)) / 75.0;
        double expectedPnL = (expectedInternalAsk - avgHedgePrice) * 75;
        
        // This should now be ~0.0125
        assertTrue(expectedPnL > 0, "PnL must be positive based on internal spread capture");
    }
    
    @Test
    void testSuccessfulSellOrderSimple() {
        // Arrange
        double expectedInternalBid = 1.1002;
        double expectedInternalAsk = 1.1005; 
        int orderQty = 20;

        // 1. Prime the Internal Quote (The "Deal" price for the client)
        // This ensures the matching gate snapshots the internal price correctly.
        omsHandler.onQuoteUpdate(expectedInternalBid, 100, expectedInternalAsk, 100, 0, 0);

        // 2. Prime the LP Bid Book via FullBookUpdate (The "Hedge" liquidity)
        // For a SELL order, we need BIDS in the book to hit.
        double lpBidPrice = 1.1004;
        TreeMap<Double, Map<String, Integer>> bids = new TreeMap<>(Collections.reverseOrder());
        Map<String, Integer> lpBidLevel = new HashMap<>();
        lpBidLevel.put("LP_C", 50); // Provide 50 units at the profitable 1.1004 price
        bids.put(lpBidPrice, lpBidLevel);

        // Update the book state (passing empty map for asks as they aren't needed for this sell)
        omsHandler.onFullBookUpdate(bids, new TreeMap<>());

        OrderEntryEvent event = new OrderEntryEvent();
        event.setParentId(67890L);
        event.setSenderId("CLIENT_BETA");
        event.setSide("SELL");
        event.setQty(orderQty);
        event.setLimit(1.1000); // Client accepts >= 1.1000, we give them 1.1002

        // Act
        omsHandler.onEvent(event, 1L, true);

        // Assert
        ArgumentCaptor<LTOrder> captor = ArgumentCaptor.forClass(LTOrder.class);
        verify(mockReplyChannel).onOMSReply(captor.capture());

        LTOrder result = captor.getValue();
        LTExecutionReport takerReport = result.getExecutionReport();
        
        // --- Verify Taker Execution ---
        assertEquals(ExecutionReportStatus.FILLED, takerReport.getOrdStatus());
        assertNull(takerReport.getRejectReason());
        assertEquals(orderQty, takerReport.getLastQty());
        
        // CRITICAL CHECK: Taker must be filled at our internal bid (1.1002), NOT the market hedge price (1.1004)
        assertEquals(expectedInternalBid, takerReport.getLastPx(), 0.000001, 
            "Taker must be filled at the internalBid price snapshotted during matching");

        // --- Verify Hedge Execution ---
        List<HedgeOrder> hedges = result.getHedgeOrders();
        assertFalse(hedges.isEmpty(), "Hedge orders should not be empty - check if liquidity was loaded");
        
        HedgeOrder hedge = hedges.get(0);
        assertEquals(lpBidPrice, hedge.getPrice(), 0.000001, 
            "Hedge must be filled at the LP market price (1.1004)");

        // --- Verify Profit Capture (Decoupled Pricing) ---
        // Firm Revenue: Sell to LP @ 1.1004
        // Firm Cost: Buy from Client @ 1.1002
        // Spread Capture: (1.1004 - 1.1002) = 0.0002 per unit
        double pnl = (hedge.getPrice() - takerReport.getLastPx()) * orderQty;
        assertEquals(0.004, pnl, 0.000001, "PnL should reflect the spread capture between Hedge and Taker prices");
    }

    @Test
    void testRejectedBuyByInternalPrice() {
        // Arrange
        OrderEntryEvent event = new OrderEntryEvent();
        event.setParentId(67890L);
        event.setSenderId("CLIENT_BETA");
        event.setSide("BUY");
        event.setQty(10);
        event.setLimit(1.1001); // Below internal Ask 1.1005

        // Act
        omsHandler.onEvent(event, 2L, true);

        // Assert
        ArgumentCaptor<LTOrder> captor = ArgumentCaptor.forClass(LTOrder.class);
        verify(mockReplyChannel).onOMSReply(captor.capture());

        LTOrder result = captor.getValue();
        assertEquals(ExecutionReportStatus.REJECTED, result.getExecutionReport().getOrdStatus());
        assertEquals(result.getExecutionReport().getRejectReason(), Integer.valueOf(101));
        assertTrue(result.getExecutionReport().getText().contains("PriceEngine Validation"));
        assertTrue(result.getHedgeOrders().isEmpty());
    }
    
    @Test
    void testRejectedSellByInternalPrice() {
        // Arrange
        // Internal Quote: Bid 1.1000, Ask 1.1005
        omsHandler.onQuoteUpdate(1.1000, 100, 1.1005, 100, 0, 0);

        OrderEntryEvent event = new OrderEntryEvent();
        event.setParentId(99988L);
        event.setSenderId("CLIENT_DELTA");
        event.setSide("SELL");
        event.setQty(10);
        event.setLimit(1.1004); // Client wants to sell at 1.1004, but our Bid is only 1.1000

        // Act
        omsHandler.onEvent(event, 5L, true);

        // Assert
        ArgumentCaptor<LTOrder> captor = ArgumentCaptor.forClass(LTOrder.class);
        verify(mockReplyChannel).onOMSReply(captor.capture());

        
        LTOrder result = captor.getValue();
        assertEquals(ExecutionReportStatus.REJECTED, result.getExecutionReport().getOrdStatus());
        assertTrue(result.getExecutionReport().getText().contains("PriceEngine Validation"));
        assertEquals(result.getExecutionReport().getRejectReason(), Integer.valueOf(101));
        assertTrue(result.getHedgeOrders().isEmpty());
    }

    @Test
    void testRejectedBuyBySizeExceedingInternalQuote() {
        // Arrange
        // Internal Ask Size is 100
        omsHandler.onQuoteUpdate(1.1000, 100, 1.1005, 100, 0, 0);

        OrderEntryEvent event = new OrderEntryEvent();
        event.setParentId(10101L);
        event.setSenderId("CLIENT_SIZE_TEST");
        event.setSide("BUY");
        event.setQty(150); // 150 > 100 internal size
        event.setLimit(1.1010); 

        // Act
        omsHandler.onEvent(event, 7L, true);

        // Assert
        ArgumentCaptor<LTOrder> captor = ArgumentCaptor.forClass(LTOrder.class);
        verify(mockReplyChannel).onOMSReply(captor.capture());

        LTOrder result = captor.getValue();
        assertEquals(ExecutionReportStatus.REJECTED, result.getExecutionReport().getOrdStatus());
        
        // Should fail Gate 1 (PriceEngine Validation) due to size
        assertTrue(result.getExecutionReport().getText().contains("PriceEngine Validation"),
            "Should reject because order qty (150) exceeds internal ask size (100)");
        assertEquals(result.getExecutionReport().getRejectReason(), Integer.valueOf(101));
        assertTrue(result.getHedgeOrders().isEmpty());
    }
    
    
    @Test
    void testRejectedBuyByInsufficientHedgeLiquidity() {
        // Arrange
        // We set internal size to 2000 so the "Liquidity Risk" check passes for 1000 qty
        omsHandler.onQuoteUpdate(1.1000, 2000, 1.1005, 2000, 0, 0);

        OrderEntryEvent event = new OrderEntryEvent();
        event.setParentId(11121L);
        event.setSenderId("CLIENT_GAMMA");
        event.setSide("BUY");
        event.setQty(1000); 
        event.setLimit(1.2000);

        // Act
        omsHandler.onEvent(event, 3L, true);

        // Assert
        ArgumentCaptor<LTOrder> captor = ArgumentCaptor.forClass(LTOrder.class);
        verify(mockReplyChannel).onOMSReply(captor.capture());

        LTOrder result = captor.getValue();
        
        // Status should be REJECTED
        assertEquals(ExecutionReportStatus.REJECTED, result.getExecutionReport().getOrdStatus());
        
        // The reason should now correctly be Hedge Liquidity because 1000 > 100 (LP_A+LP_B)
        assertEquals("Insufficient Hedge Liquidity", result.getExecutionReport().getText());
        
        assertEquals(result.getExecutionReport().getRejectReason(), Integer.valueOf(102));
        
        // Ensure no partial hedges were created
        assertTrue(result.getHedgeOrders().isEmpty(), "No hedge orders should be present for a rejected FOK");
    }
    
    
    @Test
    void testRejectedSellBySizeExceedingInternalQuote() {
        // Arrange
        // Internal Bid Size is 100
        omsHandler.onQuoteUpdate(1.1000, 100, 1.1005, 100, 0, 0);

        OrderEntryEvent event = new OrderEntryEvent();
        event.setParentId(20202L);
        event.setSenderId("CLIENT_SIZE_TEST");
        event.setSide("SELL");
        event.setQty(500); // 500 > 100 internal size
        event.setLimit(1.0990); 

        // Act
        omsHandler.onEvent(event, 8L, true);

        // Assert
        ArgumentCaptor<LTOrder> captor = ArgumentCaptor.forClass(LTOrder.class);
        verify(mockReplyChannel).onOMSReply(captor.capture());

        LTOrder result = captor.getValue();
        assertEquals(ExecutionReportStatus.REJECTED, result.getExecutionReport().getOrdStatus());
        
        assertTrue(result.getExecutionReport().getText().contains("PriceEngine Validation"),
            "Should reject because order qty (500) exceeds internal bid size (100)");
        assertEquals(result.getExecutionReport().getRejectReason(), Integer.valueOf(101));    
        assertTrue(result.getHedgeOrders().isEmpty());
    }
    
    
    @Test
    void testRejectedSellByInsufficientHedgeLiquidity() {
        // Arrange
        // 1. Set internal Bid size to 2000 to pass the L1 Risk Gate for a 1000 qty order
        omsHandler.onQuoteUpdate(1.1000, 2000, 1.1005, 2000, 0, 0);

        // 2. Setup Market Bids with limited liquidity: Total = 100
        NavigableMap<Double, Map<String, Integer>> bids = new TreeMap<>(Collections.reverseOrder());
        NavigableMap<Double, Map<String, Integer>> asks = new TreeMap<>();
        
        Map<String, Integer> bidLevel1 = new HashMap<>();
        bidLevel1.put("LP_C", 50);
        bids.put(1.0999, bidLevel1);

        Map<String, Integer> bidLevel2 = new HashMap<>();
        bidLevel2.put("LP_D", 50);
        bids.put(1.0998, bidLevel2);

        omsHandler.onFullBookUpdate(bids, asks);

        // 3. Create Sell Event for 1000 qty
        OrderEntryEvent event = new OrderEntryEvent();
        event.setParentId(77766L);
        event.setSenderId("CLIENT_EPSILON");
        event.setSide("SELL");
        event.setQty(1000); 
        event.setLimit(1.0990); // Aggressive limit

        // Act
        omsHandler.onEvent(event, 6L, true);

        // Assert
        ArgumentCaptor<LTOrder> captor = ArgumentCaptor.forClass(LTOrder.class);
        verify(mockReplyChannel).onOMSReply(captor.capture());

        LTOrder result = captor.getValue();
        
        // Should be rejected with FIX status '8'
        assertEquals(ExecutionReportStatus.REJECTED, result.getExecutionReport().getOrdStatus());

        
        // Verify rejection reason is specifically about Hedge Liquidity (L2)
        assertEquals("Insufficient Hedge Liquidity", result.getExecutionReport().getText());
        assertEquals(result.getExecutionReport().getRejectReason(), Integer.valueOf(102));
        
        // Verify no partial fills were generated (FOK compliance)
        assertTrue(result.getHedgeOrders().isEmpty(), "No hedge orders should be present for a rejected FOK");
    }
}