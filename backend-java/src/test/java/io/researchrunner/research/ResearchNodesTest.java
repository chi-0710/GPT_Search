package io.researchrunner.research;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.bsc.langgraph4j.GraphInput;
import org.bsc.langgraph4j.RunnableConfig;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ResearchNodesTest {
    private SourceCollector collector(EvidenceRegistry evidence,boolean noChapters) {
        UUID id=UUID.randomUUID();
        return new SourceCollector(new SourceCollector.Selection("local",List.of(id),List.of(),List.of(),3),
                (q,ids)->noChapters && q.contains(" — ")?List.of():List.of(Map.of("document_id",id.toString(),"title","资料","url","资料.txt","raw_content","已核验的来源事实")),
                (q,d,n)->{fail("local research must not search");return List.of();},(u,d)->null,evidence);
    }
    private ResearchNodes.Settings settings() {return new ResearchNodes.Settings("主题","Objective",List.of("证据支持"),true,2,2,1,1000,1000);}

    @Test void actualNodesRepairChaptersThenRewriteAndPublishCanonicalEvidence() throws Exception {
        var evidence=new EvidenceRegistry(UUID.randomUUID());var stages=Collections.synchronizedList(new ArrayList<String>());
        var facts=new AtomicInteger();var publishes=new AtomicInteger();var humanRounds=new AtomicInteger();
        ResearchNodes.Model model=(stage,instruction,input)->{
            stages.add(stage);
            return switch(stage) {
                case "planner" -> "{\"title\":\"研究标题\",\"sections\":[\"章节一\",\"章节二\"]}";
                case "section_reviewer" -> "{\"accepted\":true,\"feedback\":\"\"}";
                case "fact_checker" -> {
                    assertFalse(((List<?>)input.get("evidence")).isEmpty());
                    yield facts.incrementAndGet()==1?"{\"accepted\":false,\"feedback\":\"修正章节事实\"}":"{\"accepted\":true,\"feedback\":\"\"}";
                }
                default -> "研究正文 [E1]";
            };
        };
        var nodes=new ResearchNodes(settings(),collector(evidence,false),evidence,model,
                (title,sections,round)->new ResearchNodes.Decision(humanRounds.incrementAndGet()>1,"调整章节"),report->report,
                (report,snapshots,warnings)->{publishes.incrementAndGet();assertTrue(report.contains("/evidence/1/view"));assertEquals(1,snapshots.size());},
                (stage,message)->assertNotEquals("report",stage),()->false);
        var result=ResearchWorkflow.compile(nodes.actions(),1,1).invoke(GraphInput.noArgs(),RunnableConfig.empty()).orElseThrow();
        assertEquals(true,result.data().get("published"));assertEquals(1,publishes.get());
        assertEquals(2,Collections.frequency(stages,"planner"));assertEquals(2,Collections.frequency(stages,"writer"));
        assertEquals(2,Collections.frequency(stages,"fact_reviser"));
        assertTrue(stages.lastIndexOf("writer")>stages.lastIndexOf("fact_reviser"));
    }

    @Test void allUnsupportedChaptersAndInitialEmptyEvidenceCannotPublish() throws Exception {
        var evidence=new EvidenceRegistry(UUID.randomUUID());
        ResearchNodes.Model model=(stage,instruction,input)->stage.equals("planner")?"{\"title\":\"标题\",\"sections\":[\"章节\"]}":"摘要 [E1]";
        var nodes=new ResearchNodes(settings(),collector(evidence,true),evidence,model,(t,s,r)->new ResearchNodes.Decision(true,""),r->r,
                (r,e,w)->fail("unsupported report must not publish"),(s,m)->{},()->false);
        assertThrows(Exception.class,()->ResearchWorkflow.compile(nodes.actions(),1,1).invoke(GraphInput.noArgs(),RunnableConfig.empty()));
        assertTrue(evidence.warnings().stream().anyMatch(w->w.contains("章节资料不足")));
        var empty=new SourceCollector(new SourceCollector.Selection("hybrid",List.of(),List.of(),List.of(),1),(q,ids)->List.of(),(q,d,n)->List.of(),(u,d)->null,evidence);
        var emptyNodes=new ResearchNodes(settings(),empty,evidence,(s,i,p)->{fail("empty evidence must not call model");return "";},
                (t,s,r)->new ResearchNodes.Decision(true,""),r->r,(r,e,w)->fail("must not publish"),(s,m)->{},()->false);
        assertThrows(Exception.class,()->ResearchWorkflow.compile(emptyNodes.actions(),1,1).invoke(GraphInput.noArgs(),RunnableConfig.empty()));
    }

    @Test void unresolvedDraftReviewStopsWithoutPublishing() throws Exception {
        var evidence=new EvidenceRegistry(UUID.randomUUID());var revisions=new AtomicInteger();
        var reviewerPrompts=Collections.synchronizedList(new ArrayList<String>());
        var nodes=new ResearchNodes(settings(),collector(evidence,false),evidence,(stage,instruction,input)->{
            if(stage.equals("planner"))return "{\"title\":\"标题\",\"sections\":[\"章节\"]}";
            if(stage.equals("section_reviewer")) {
                reviewerPrompts.add(instruction);
                return "{\"accepted\":false,\"feedback\":\"缺少论证\"}";
            }
            if(stage.equals("section_reviser")) {
                revisions.incrementAndGet();
                return "{\"draft\":\"正文 [E1]\",\"revision_notes\":\"已补充论证\"}";
            }
            return "正文 [E1]";
        },(t,s,r)->new ResearchNodes.Decision(true,""),r->r,(r,e,w)->fail("must not publish"),(s,m)->{},()->false);
        assertThrows(Exception.class,()->ResearchWorkflow.compile(nodes.actions(),1,1).invoke(GraphInput.noArgs(),RunnableConfig.empty()));
        assertEquals(1,revisions.get());
        assertEquals(2,reviewerPrompts.size(),"拒绝后必须再复审一次才达到修订上限");
        assertFalse(reviewerPrompts.get(0).contains("修订者已根据你之前的审阅意见"));
        assertTrue(reviewerPrompts.get(1).contains("修订者已根据你之前的审阅意见修改了草稿，并提供了以下反馈：\n已补充论证"),
                "章节修订后的复审必须使用 revision 变体");
    }

    @Test void webSourceUsesWebReferencePromptBranch() throws Exception {
        var evidence=new EvidenceRegistry(UUID.randomUUID());
        var initialPrompts=Collections.synchronizedList(new ArrayList<String>());
        var selection=new SourceCollector.Selection("web",List.of(),List.of(),List.of(),2);
        var collector=new SourceCollector(selection,(q,ids)->{fail("web must not retrieve files");return List.of();},
                (q,d,n)->List.of(new SearchGateway.Hit("https://example.com/a","标题","摘要")),
                (u,d)->new SearchGateway.Hit(u,"标题","网络完整证据"),evidence);
        var nodes=new ResearchNodes(settings(),collector,evidence,(stage,instruction,input)->{
            if(stage.equals("initial_research"))initialPrompts.add(instruction);
            return switch(stage) {
                case "planner" -> "{\"title\":\"标题\",\"sections\":[\"章节\"]}";
                case "section_reviewer","fact_checker" -> "{\"accepted\":true,\"feedback\":\"\"}";
                default -> "正文 [E1]";
            };
        },(t,s,r)->new ResearchNodes.Decision(true,""),r->r,(r,e,w)->{},(s,m)->{},()->false);
        ResearchWorkflow.compile(nodes.actions(),1,1).invoke(GraphInput.noArgs(),RunnableConfig.empty()).orElseThrow();
        assertEquals(1,initialPrompts.size());
        String initial=initialPrompts.get(0);
        assertTrue(initial.contains("你必须在报告末尾以参考文献形式列出所有使用过的来源 URL"),initial);
        assertTrue(initial.contains("[网站链接](url)"));
        assertFalse(initial.contains("来源文档名称"),"web 来源必须使用 initial_research 变体");
    }

    @Test void cancellationAtStartPreventsModelsAndPersistence() throws Exception {
        var evidence=new EvidenceRegistry(UUID.randomUUID());
        var nodes=new ResearchNodes(settings(),collector(evidence,false),evidence,(s,i,p)->{fail("must not call");return "";},
                (t,s,r)->new ResearchNodes.Decision(true,""),r->r,(r,e,w)->fail("must not publish"),(s,m)->{},()->true);
        assertThrows(Exception.class,()->ResearchWorkflow.compile(nodes.actions(),1,1).invoke(GraphInput.noArgs(),RunnableConfig.empty()));
    }
}
