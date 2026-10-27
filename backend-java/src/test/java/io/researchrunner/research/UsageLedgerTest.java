package io.researchrunner.research;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class UsageLedgerTest {
    @Test void unknownPricesAreNotZeroAndPartialKnownCostRemainsSeparate() {
        var ledger=new UsageLedger();ledger.record("writer","test","model",100,20,null);
        assertNull(ledger.snapshot().get("model_cost_usd"));assertEquals("unknown",ledger.snapshot().get("model_cost_status"));
        ledger.record("planner","test","model",1000000,1000000,new UsageLedger.Price(new BigDecimal("2"),new BigDecimal("3")));
        assertEquals(0,new BigDecimal("5").compareTo((BigDecimal)ledger.snapshot().get("known_model_cost_usd")));
        assertNull(ledger.snapshot().get("total_cost_usd"));assertNull(ledger.snapshot().get("model_cost_usd"));
    }
}
