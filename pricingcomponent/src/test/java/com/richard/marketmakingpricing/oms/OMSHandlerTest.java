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
        OrderEntryEvent event = new OrderEntryEvent();
        event.setParentId(12345L);
        event.setSenderId("CLIENT_ALPHA");
        event.setSide("BUY");
        event.setQty(75);
        event.setLimit(1.1010); 

        // Act
        omsHandler.onEvent(event, 1L, true);

        // Assert
        ArgumentCaptor<LTOrder> captor = ArgumentCaptor.forClass(LTOrder.class);
        verify(mockReplyChannel).onOMSReply(captor.capture());

        LTOrder result = captor.getValue();
        assertEquals(ExecutionReportStatus.FILLED, result.getExecutionReport().getOrdStatus());
        assertEquals(75, result.getExecutionReport().getLastQty());

        // Verify Hedges (75 needed: 50 from LP_A @ 1.1006, 25 from LP_B @ 1.1007)
        List<HedgeOrder> hedges = result.getHedgeOrders();
        assertEquals(2, hedges.size());
        
        HedgeOrder firstHedge = hedges.get(0);
        HedgeOrder secondHedge = hedges.get(1);

        // 1. Check first hedge is the BEST price (cheapest for a BUY)
        assertEquals("LP_A", firstHedge.getTargetCompID());
        assertEquals(1.1006, firstHedge.getPrice(), 0.000001);
        assertEquals(50, firstHedge.getExecutionReport().getLastQty());

        // 2. Check second hedge is the NEXT best price
        assertEquals("LP_B", secondHedge.getTargetCompID());
        assertEquals(1.1007, secondHedge.getPrice(), 0.000001);
        assertEquals(25, secondHedge.getExecutionReport().getLastQty());

        // 3. Explicitly verify the price sequence: first price <= second price
        assertTrue(firstHedge.getPrice() <= secondHedge.getPrice(), 
            "Hedge orders must be sorted by price (best to worst)");
    }
    
    @Test
    void testSuccessfulSellOrderWithHedging() {
        // Arrange: Setup internal quote and market bids
        // Internal Bid: 1.1000 (100 qty)
        omsHandler.onQuoteUpdate(1.1000, 100, 1.1005, 100, 0, 0);

        // Setup Market Bids: LP_C @ 1.0999 (50), LP_D @ 1.0998 (50)
        // Using TreeMap with ReverseOrder for Bids (Highest to Lowest)
        NavigableMap<Double, Map<String, Integer>> bids = new TreeMap<>(Collections.reverseOrder());
        NavigableMap<Double, Map<String, Integer>> asks = new TreeMap<>();
        
        Map<String, Integer> bidLevel1 = new HashMap<>();
        bidLevel1.put("LP_C", 50);
        bids.put(1.0999, bidLevel1);

        Map<String, Integer> bidLevel2 = new HashMap<>();
        bidLevel2.put("LP_D", 50);
        bids.put(1.0998, bidLevel2);

        omsHandler.onFullBookUpdate(bids, asks);

        // Create Sell Event
        OrderEntryEvent event = new OrderEntryEvent();
        event.setParentId(55555L);
        event.setSenderId("CLIENT_ZETA");
        event.setSide("SELL"); // Logic: event.getSide().equalsIgnoreCase("SELL") -> isBuy = false
        event.setQty(80);
        event.setLimit(1.0995); // Aggressive enough to sell at 1.1000

        // Act
        omsHandler.onEvent(event, 4L, true);

        // Assert
        ArgumentCaptor<LTOrder> captor = ArgumentCaptor.forClass(LTOrder.class);
        verify(mockReplyChannel).onOMSReply(captor.capture());

        LTOrder result = captor.getValue();
        assertEquals(ExecutionReportStatus.FILLED, result.getExecutionReport().getOrdStatus());
        assertFalse(result.isSideBuy()); // Confirm it's a Sell
        assertEquals(80, result.getExecutionReport().getLastQty());

        // Verify Hedges (80 needed: 50 from LP_C @ 1.0999, 30 from LP_D @ 1.0998)
        List<HedgeOrder> hedges = result.getHedgeOrders();
        assertEquals(2, hedges.size());

        HedgeOrder firstHedge = hedges.get(0);
        HedgeOrder secondHedge = hedges.get(1);

        // 1. Check first hedge is the BEST price (highest for a SELL)
        assertEquals("LP_C", firstHedge.getTargetCompID());
        assertEquals(1.0999, firstHedge.getPrice(), 0.000001);
        assertEquals(50, firstHedge.getExecutionReport().getLastQty());

        // 2. Check second hedge is the NEXT best price
        assertEquals("LP_D", secondHedge.getTargetCompID());
        assertEquals(1.0998, secondHedge.getPrice(), 0.000001);
        assertEquals(30, secondHedge.getExecutionReport().getLastQty());

        // 3. Explicitly verify price priority: first price >= second price (Selling high to low)
        assertTrue(firstHedge.getPrice() >= secondHedge.getPrice(), 
            "Sell hedge orders must be sorted by price (best/highest to worst/lowest)");
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
        
        // Verify no partial fills were generated (FOK compliance)
        assertTrue(result.getHedgeOrders().isEmpty(), "No hedge orders should be present for a rejected FOK");
    }
}