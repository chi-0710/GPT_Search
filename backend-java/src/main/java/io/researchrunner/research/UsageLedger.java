package io.researchrunner.research;

import java.math.BigDecimal;
import java.util.*;

/** Run-scoped model usage. Missing prices remain unknown rather than being reported as zero. */
public final class UsageLedger {
    public record Price(BigDecimal inputPerMillion,BigDecimal outputPerMillion) {
        public Price {
            if(inputPerMillion==null || outputPerMillion==null || inputPerMillion.signum()<0 || outputPerMillion.signum()<0)
                throw new IllegalArgumentException("模型单价无效");
        }
    }
    private final List<Map<String,Object>> calls=new ArrayList<>();
    public synchronized void record(String stage,String provider,String model,Integer input,Integer output,Price price) {
        Map<String,Object> call=new LinkedHashMap<>();call.put("stage",stage);call.put("provider",provider);call.put("model",model);
        call.put("input_tokens",input);call.put("output_tokens",output);
        boolean known=input!=null && output!=null && input>=0 && output>=0 && price!=null;
        call.put("cost_usd",known?price.inputPerMillion().multiply(BigDecimal.valueOf(input))
                .add(price.outputPerMillion().multiply(BigDecimal.valueOf(output))).movePointLeft(6):null);
        call.put("cost_status",known?"known":"unknown");calls.add(call);
    }
    public synchronized Map<String,Object> snapshot() {
        BigDecimal knownTotal=BigDecimal.ZERO;boolean complete=!calls.isEmpty();
        for(var call:calls) {
            if(call.get("cost_usd") instanceof BigDecimal cost)knownTotal=knownTotal.add(cost);else complete=false;
        }
        Map<String,Object> result=new LinkedHashMap<>();result.put("model_calls",calls.stream().map(call->Collections.unmodifiableMap(new LinkedHashMap<>(call))).toList());
        result.put("known_model_cost_usd",knownTotal);result.put("model_cost_usd",complete?knownTotal:null);
        result.put("model_cost_status",complete?"known":"unknown");
        // Search, extraction and images have independent billing and are never implied to be free.
        result.put("total_cost_status","unknown");result.put("total_cost_usd",null);
        return Collections.unmodifiableMap(result);
    }
}
