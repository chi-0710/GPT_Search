package io.researchrunner.research;

import java.util.Map;
import java.util.Set;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.bsc.langgraph4j.state.AgentState;
import static org.bsc.langgraph4j.action.AsyncEdgeAction.edge_async;

/** Compiles the public research topology; runtime dependencies live in node closures. */
public final class ResearchWorkflow {
    public static final Set<String> NODES = Set.of("browser", "planner", "human", "researcher",
            "writer", "fact_checker", "repair_sections", "visualizer", "publisher");

    private ResearchWorkflow() { }

    public static CompiledGraph<AgentState> compile(
            Map<String, AsyncNodeAction<AgentState>> actions, Integer planLimit, Integer factLimit)
            throws GraphStateException {
        if (!actions.keySet().equals(NODES)) {
            throw new IllegalArgumentException("研究图必须提供所有节点，禁止缺失节点时回退");
        }
        if ((planLimit != null && planLimit < 0) || (factLimit != null && factLimit < 0)) throw new IllegalArgumentException("修订上限不能为负数");
        var graph = new StateGraph<AgentState>(AgentState::new);
        for (String name : NODES) graph.addNode(name, actions.get(name));
        graph.addEdge("__START__", "browser")
            .addEdge("browser", "planner")
            .addEdge("planner", "human")
            .addConditionalEdges("human", edge_async(state -> reviewRoute(state,
                    "plan_accepted", "plan_revision_count", planLimit)),
                    Map.of("accept", "researcher", "revise", "planner"))
            .addEdge("researcher", "writer")
            .addEdge("writer", "fact_checker")
            .addConditionalEdges("fact_checker", edge_async(state -> reviewRoute(state,
                    "facts_accepted", "fact_check_revision_count", factLimit)),
                    Map.of("accept", "visualizer", "revise", "repair_sections"))
            .addEdge("repair_sections", "writer")
            .addEdge("visualizer", "publisher")
            .addEdge("publisher", "__END__");
        return graph.compile();
    }

    private static String reviewRoute(AgentState state, String accepted, String count, Integer limit) {
        Object decision = state.data().get(accepted);
        if (!(decision instanceof Boolean)) throw new IllegalStateException("审核节点未返回明确决定");
        if (Boolean.TRUE.equals(decision)) return "accept";
        Object attempts = state.data().get(count);
        if (!(attempts instanceof Number number) || number.intValue() < 1) {
            throw new IllegalStateException("审核节点未返回有效修订次数");
        }
        if (limit != null && number.intValue() > limit) throw new IllegalStateException("审核修订达到上限，未通过的报告不会发布");
        return "revise";
    }
}
