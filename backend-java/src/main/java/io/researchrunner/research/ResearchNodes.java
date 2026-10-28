package io.researchrunner.research;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.bsc.langgraph4j.state.AgentState;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

/** Actual research actions; model clients, review channels and persistence remain outside graph state. */
public final class ResearchNodes {
    @FunctionalInterface public interface Model {
        String generate(String stage,String instruction,Map<String,Object> input) throws Exception;
    }
    @FunctionalInterface public interface Human {
        Decision review(String title,List<String> sections,int round) throws Exception;
    }
    @FunctionalInterface public interface Visualizer {
        String render(String markdown) throws Exception;
    }
    @FunctionalInterface public interface Publisher {
        void publish(String markdown,List<Map<String,Object>> evidence,List<String> warnings) throws Exception;
    }
    @FunctionalInterface public interface Progress {
        void emit(String stage,String message) throws Exception;
    }
    public record Decision(boolean accepted,String feedback) {
        public Decision { feedback=feedback==null?"":feedback; }
    }
    public record Settings(String query,String tone,List<String> guidelines,boolean followGuidelines,
            int maxSections,int concurrency,Integer draftLimit,int localBudget,int webBudget) {
        public Settings {
            if(query==null || query.isBlank() || maxSections<1 || concurrency<1 || localBudget<1 || webBudget<1
                    || (draftLimit!=null && draftLimit<0))throw new IllegalArgumentException("研究设置无效");
            guidelines=List.copyOf(guidelines);tone=tone==null?"Objective":tone;
        }
    }
    /** Verbatim prompts migrated from the former Python backend plus their Java output protocols. */
    private static final PromptCatalog PROMPTS=PromptCatalog.shared();
    private static final DateTimeFormatter SHORT_DATE=DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter LONG_DATE=DateTimeFormatter.ofPattern("MMMM dd, yyyy",Locale.ENGLISH);
    /** Tone descriptions copied from Python's search/utils/enum.py Tone enum (data, not prompt text). */
    private static final Map<String,String> TONES=Map.ofEntries(
            Map.entry("Objective","Objective (impartial and unbiased presentation of facts and findings)"),
            Map.entry("Formal","Formal (adheres to academic standards with sophisticated language and structure)"),
            Map.entry("Analytical","Analytical (critical evaluation and detailed examination of data and theories)"),
            Map.entry("Persuasive","Persuasive (convincing the audience of a particular viewpoint or argument)"),
            Map.entry("Informative","Informative (providing clear and comprehensive information on a topic)"),
            Map.entry("Explanatory","Explanatory (clarifying complex concepts and processes)"),
            Map.entry("Descriptive","Descriptive (detailed depiction of phenomena, experiments, or case studies)"),
            Map.entry("Critical","Critical (judging the validity and relevance of the research and its conclusions)"),
            Map.entry("Comparative","Comparative (juxtaposing different theories, data, or methods to highlight differences and similarities)"),
            Map.entry("Speculative","Speculative (exploring hypotheses and potential implications or future research directions)"),
            Map.entry("Reflective","Reflective (considering the research process and personal insights or experiences)"),
            Map.entry("Narrative","Narrative (telling a story to illustrate research findings or methodologies)"),
            Map.entry("Humorous","Humorous (light-hearted and engaging, usually to make the content more relatable)"),
            Map.entry("Optimistic","Optimistic (highlighting positive findings and potential benefits)"),
            Map.entry("Pessimistic","Pessimistic (focusing on limitations, challenges, or negative outcomes)"),
            Map.entry("Simple","Simple (written for young readers, using basic vocabulary and clear explanations)"),
            Map.entry("Casual","Casual (conversational and relaxed style for easy, everyday reading)"));
    private final Settings settings;
    private final SourceCollector sources;
    private final EvidenceRegistry evidence;
    private final Model model;
    private final Human human;
    private final Visualizer visualizer;
    private final Publisher publisher;
    private final Progress progress;
    private final BooleanSupplier cancelled;
    private final ObjectMapper json=new ObjectMapper();

    public ResearchNodes(Settings settings,SourceCollector sources,EvidenceRegistry evidence,Model model,
            Human human,Visualizer visualizer,Publisher publisher,Progress progress,BooleanSupplier cancelled) {
        this.settings=settings;this.sources=sources;this.evidence=evidence;this.model=model;this.human=human;
        this.visualizer=visualizer;this.publisher=publisher;this.progress=progress;this.cancelled=cancelled;
    }

    public Map<String,AsyncNodeAction<AgentState>> actions() {
        Map<String,AsyncNodeAction<AgentState>> nodes=new HashMap<>();
        nodes.put("browser",node_async(state->{
            emit("initial_research","正在收集初始研究证据");
            String context=collect(settings.query());
            // Python generate_report_prompt branches on report_source: web lists source URLs,
            // local/hybrid lists source document names.
            String promptKey="web".equals(sources.source())?"initial_research":"initial_research.documents";
            return Map.of("initial_context",context,"initial_research",generate("initial_research",promptKey,
                    vars("question",settings.query(),"context",context,"tone",tone(settings.tone()),
                            "date",LocalDate.now().toString()),
                    Map.of("evidence",context)));
        }));
        nodes.put("planner",node_async(state->{
            emit("planning","正在规划研究章节");
            String initial=text(state,"initial_research"),feedback=text(state,"plan_feedback");
            boolean humanFeedback=!feedback.isBlank() && !"no".equalsIgnoreCase(feedback.strip());
            var variables=vars("today",LocalDate.now().format(SHORT_DATE),"initial_research",initial,
                    "max_sections",settings.maxSections());
            if(humanFeedback)variables.put("human_feedback",feedback);
            var plan=parse(generate("planner",humanFeedback?"planner.feedback":"planner",variables,
                    Map.of("initial_research",initial,"feedback",feedback)));
            if(!plan.path("title").isTextual() || plan.path("title").asText().isBlank() || !plan.path("sections").isArray())throw new IllegalStateException("研究规划格式无效");
            Set<String> titles=new LinkedHashSet<>();
            for(var title:plan.path("sections")) {
                if(!title.isTextual() || title.asText().isBlank())throw new IllegalStateException("研究章节标题无效");
                titles.add(title.asText().strip());
            }
            if(titles.isEmpty())throw new IllegalStateException("规划未生成研究章节");
            return Map.of("title",plan.path("title").asText(),"sections",titles.stream().limit(settings.maxSections()).toList());
        }));
        nodes.put("human",node_async(state->{
            check();int round=count(state,"plan_revision_count");
            var decision=human.review(text(state,"title"),strings(state,"sections"),round);
            check();return Map.of("plan_accepted",decision.accepted(),"plan_feedback",decision.feedback(),
                    "plan_revision_count",round+(decision.accepted()?0:1));
        }));
        nodes.put("researcher",node_async(state->{
            emit("parallel_research","正在研究各章节");
            List<Callable<Map<String,Object>>> tasks=strings(state,"sections").stream()
                    .<Callable<Map<String,Object>>>map(title->()->chapter(title)).toList();
            var chapters=SectionTasks.gather(tasks,settings.concurrency(),Duration.ofSeconds(15),cancelled);
            if(chapters.stream().noneMatch(c->Boolean.TRUE.equals(c.get("supported"))))throw new InsufficientEvidenceException("所有章节均未找到可用证据");
            return Map.of("chapters",chapters);
        }));
        nodes.put("writer",node_async(state->{
            emit("writing","正在汇总研究报告");
            String title=text(state,"title");
            var variables=vars("date",LocalDate.now().format(SHORT_DATE),"query",title,
                    "data",jsonString(chapters(state)));
            String promptKey="writer";
            if(settings.followGuidelines()) {
                promptKey="writer.guidelines";variables.put("guidelines",pythonList(settings.guidelines()));
            }
            return Map.of("report",generate("writer",promptKey,variables,
                    Map.of("title",title,"chapters",chapters(state),"evidence",evidence.snapshots())));
        }));
        nodes.put("fact_checker",node_async(state->{
            emit("fact_checking","正在依据来源证据核查报告");
            var decision=review("fact_checker",vars("draft",text(state,"report")),
                    Map.of("report",text(state,"report"),"chapters",chapters(state),"evidence",evidence.snapshots()));
            return Map.of("facts_accepted",decision.accepted(),"fact_feedback",decision.feedback(),
                    "fact_check_revision_count",count(state,"fact_check_revision_count")+(decision.accepted()?0:1));
        }));
        nodes.put("repair_sections",node_async(state->{
            emit("fact_revision","正在根据事实核查意见修订章节");
            List<Callable<Map<String,Object>>> tasks=chapters(state).stream().<Callable<Map<String,Object>>>map(chapter->()->{
                if(!Boolean.TRUE.equals(chapter.get("supported")))return chapter;
                Map<String,Object> revised=new LinkedHashMap<>(chapter);
                revised.put("body",generate("fact_reviser","fact_reviser",
                        vars("draft",Objects.toString(chapter.get("body"),""),"review",text(state,"fact_feedback")),
                        Map.of("chapter",chapter,"feedback",text(state,"fact_feedback"),"evidence",evidence.snapshots())));
                // Python 事实修订只取 ReviserAgent 的 draft（orchestrator._repair_sections 丢弃
                // revision_notes）；Java 保留事实修订后的章节复审，把事实核查意见作为该轮 revision_notes。
                return reviewChapter(revised,"事实核查意见："+text(state,"fact_feedback"));
            }).toList();
            return Map.of("chapters",SectionTasks.gather(tasks,settings.concurrency(),Duration.ofSeconds(15),cancelled));
        }));
        nodes.put("visualizer",node_async(state->{
            check();String report=visualizer.render(text(state,"report"));check();
            if(report==null || report.isBlank())throw new IllegalStateException("可视化阶段返回空报告");
            return Map.of("report",report);
        }));
        nodes.put("publisher",node_async(state->{
            check();String canonical=evidence.render(text(state,"report"));
            publisher.publish(canonical,evidence.snapshots(),evidence.warnings());
            return Map.of("report",canonical,"published",true);
        }));
        return Map.copyOf(nodes);
    }

    private Map<String,Object> chapter(String title) throws Exception {
        String context;
        try {context=collect(settings.query()+" — "+title);}
        catch(InsufficientEvidenceException empty) {
            evidence.warn("章节资料不足："+title);
            return Map.of("title",title,"body","资料不足：本章节未检索到可用证据。","supported",false);
        }
        String body=generate("section_research","section_research",
                vars("context",context,"main_topic",settings.query(),"current_subtopic",title,
                        "tone",tone(settings.tone()),"date",LocalDate.now().format(LONG_DATE)),
                Map.of("title",title,"evidence",context));
        return reviewChapter(Map.of("title",title,"body",body,"context",context,"supported",true));
    }

    private Map<String,Object> reviewChapter(Map<String,Object> chapter) throws Exception {
        return reviewChapter(chapter,"");
    }

    /**
     * Python 的 reviewer 在 revision_notes 非空时使用 section_reviewer.revision 变体：首轮审阅无 notes，
     * 章节经 section_reviser 修订后使用修订说明；事实修订后的复审以事实核查意见作为本轮 notes。
     */
    private Map<String,Object> reviewChapter(Map<String,Object> chapter,String firstNotes) throws Exception {
        if(!settings.followGuidelines()) {
            emit("section_draft",Objects.toString(chapter.get("body"),""));
            return chapter;
        }
        Map<String,Object> revised=new LinkedHashMap<>(chapter);
        int revision=0;
        String notes=firstNotes;
        while(true) {
            String draft=Objects.toString(revised.get("body"),"");
            LinkedHashMap<String,Object> variables=vars("guidelines",String.join("- ",settings.guidelines()),
                    "draft",draft);
            String promptKey="section_reviewer";
            if(!notes.isBlank()) {
                promptKey="section_reviewer.revision";variables.put("revision_notes",notes);
            }
            var decision=review("section_reviewer",promptKey,variables,
                    Map.of("chapter",revised,"guidelines",settings.guidelines()));
            if(decision.accepted()) {
                emit("section_draft",Objects.toString(revised.get("body"),""));
                return revised;
            }
            if(settings.draftLimit()!=null && revision>=settings.draftLimit())throw new IllegalStateException("章节审阅达到修订上限，仍有未解决问题");
            revision++;
            JsonNode result=parse(generate("section_reviser","section_reviser",
                    vars("draft",draft,"review",decision.feedback()),
                    Map.of("chapter",new LinkedHashMap<>(revised),"feedback",decision.feedback())));
            String revisedBody=jsonText(result,"draft");
            if(revisedBody.isBlank())throw new IllegalStateException("章节修订结果 draft 为空");
            revised.put("body",revisedBody);
            revised.put("revision_notes",jsonText(result,"revision_notes"));
            notes=Objects.toString(revised.get("revision_notes"),"");
        }
    }

    private Decision review(String stage,Map<String,Object> variables,Map<String,Object> input) throws Exception {
        return review(stage,stage,variables,input);
    }
    private Decision review(String stage,String promptKey,Map<String,Object> variables,Map<String,Object> input) throws Exception {
        var decision=parse(generate(stage,promptKey,variables,input));
        if(!decision.path("accepted").isBoolean() || !decision.path("feedback").isTextual())throw new IllegalStateException("审核结果格式无效");
        if(!decision.path("accepted").asBoolean() && decision.path("feedback").asText().isBlank())throw new IllegalStateException("拒绝审核时必须提供修订意见");
        return new Decision(decision.path("accepted").asBoolean(),decision.path("feedback").asText());
    }
    /** Required textual field of a model-produced JSON object. */
    private static String jsonText(JsonNode node,String field) {
        JsonNode value=node.path(field);
        if(!value.isTextual())throw new IllegalStateException("模型 JSON 缺少文本字段："+field);
        return value.asText();
    }
    private String collect(String query) throws Exception {check();return sources.collect(query,settings.localBudget(),settings.webBudget()).context();}
    private String generate(String stage,String promptKey,Map<String,Object> variables,Map<String,Object> input) throws Exception {
        check();Map<String,Object> payload=new LinkedHashMap<>(input);
        payload.put("query",settings.query());payload.put("tone",settings.tone());payload.put("date",LocalDate.now().toString());
        if(settings.followGuidelines())payload.put("guidelines",settings.guidelines());
        String instruction=PROMPTS.instruction(promptKey,variables);
        String output=model.generate(stage,instruction,payload);
        check();if(output==null || output.isBlank())throw new IllegalStateException("模型返回空内容："+stage);return output;
    }
    private JsonNode parse(String output) throws Exception {
        return JsonOutput.parseObject(json, output);
    }
    private String jsonString(Object value) {
        try {return json.writeValueAsString(value);}
        catch(com.fasterxml.jackson.core.JsonProcessingException impossible) {
            throw new IllegalStateException("研究数据无法序列化",impossible);
        }
    }
    /** Python ``str(list)`` formatting used by the migrated writer prompt's ``${guidelines}``. */
    private static String pythonList(List<String> values) {
        StringBuilder output=new StringBuilder("[");
        for(int index=0;index<values.size();index++) {
            if(index>0)output.append(", ");
            output.append('\'').append(values.get(index).replace("\\","\\\\").replace("'","\\'")).append('\'');
        }
        return output.append(']').toString();
    }
    /** Python Tone enum description (search/utils/enum.py) for ``${tone}``. */
    private static String tone(String name) {
        if(name==null || name.isBlank())return TONES.get("Objective");
        String key=name.strip();
        if(key.contains("("))return key;
        return TONES.getOrDefault(key,key);
    }
    private static LinkedHashMap<String,Object> vars(Object... pairs) {
        if(pairs.length%2!=0)throw new IllegalArgumentException("提示词变量必须成对提供");
        LinkedHashMap<String,Object> variables=new LinkedHashMap<>();
        for(int index=0;index<pairs.length;index+=2)variables.put(String.valueOf(pairs[index]),pairs[index+1]);
        return variables;
    }
    private void emit(String stage,String message) throws Exception {check();progress.emit(stage,message);}
    private void check() {if(cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())throw new CancellationException("研究已取消");}
    private static String text(AgentState state,String key) {return Objects.toString(state.data().get(key),"");}
    private static int count(AgentState state,String key) {return ((Number)state.data().getOrDefault(key,0)).intValue();}
    @SuppressWarnings("unchecked") private static List<String> strings(AgentState state,String key) {return (List<String>)state.data().get(key);}
    @SuppressWarnings("unchecked") private static List<Map<String,Object>> chapters(AgentState state) {return (List<Map<String,Object>>)state.data().get("chapters");}
}
