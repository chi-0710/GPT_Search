package io.researchrunner.research;

import java.io.IOException;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SourceCollectorTest {
    private static SourceCollector.Selection select(String source,List<UUID> ids) {
        return new SourceCollector.Selection(source,ids,List.of("example.com"),List.of(),3);
    }
    private static Map<String,Object> document(UUID id) {
        return Map.of("document_id",id.toString(),"raw_content","文件证据","title","资料.txt","url","资料.txt");
    }

    @Test void fileOnlyNeverCallsSearchAndRejectsOutOfScopeRows() throws Exception {
        UUID id=UUID.randomUUID();var registry=new EvidenceRegistry(UUID.randomUUID());
        var collector=new SourceCollector(select("local",List.of(id)),(q,ids)->{
            assertEquals(List.of(id),ids);return List.of(document(id),document(UUID.randomUUID()));
        },(q,d,n)->{fail("local must not search");return List.of();},(u,d)->{fail("local must not scrape");return null;},registry);
        var result=collector.collect("query",1000,1000);
        assertEquals(1,result.local().size());assertTrue(result.web().isEmpty());assertEquals(1,registry.snapshots().size());
    }

    @Test void hybridUsesWebWithVisibleWarningWhenLocalFails() throws Exception {
        var registry=new EvidenceRegistry(UUID.randomUUID());
        var collector=new SourceCollector(select("hybrid",List.of(UUID.randomUUID())),(q,ids)->{throw new IOException("unavailable");},
                (q,d,n)->List.of(new SearchGateway.Hit("https://example.com/a","标题","摘要")),
                (u,d)->new SearchGateway.Hit(u,"标题","网络完整证据"),registry);
        assertEquals(1,collector.collect("query",1000,1000).web().size());
        assertTrue(registry.warnings().stream().anyMatch(w->w.contains("文件来源")));
    }

    @Test void hybridUsesLocalWhenSearchFailsAndCannotFabricateEmptyEvidence() throws Exception {
        UUID id=UUID.randomUUID();var registry=new EvidenceRegistry(UUID.randomUUID());
        var collector=new SourceCollector(select("hybrid",List.of(id)),(q,ids)->List.of(document(id)),
                (q,d,n)->{throw new IOException("unavailable");},(u,d)->null,registry);
        assertEquals(1,collector.collect("query",1000,1000).local().size());
        assertTrue(registry.warnings().stream().anyMatch(w->w.contains("网络来源")));
        var empty=new SourceCollector(select("hybrid",List.of()),(q,ids)->List.of(),(q,d,n)->List.of(),(u,d)->null,new EvidenceRegistry(UUID.randomUUID()));
        assertThrows(IllegalStateException.class,()->empty.collect("query",1000,1000));
    }

    @Test void cancellationIsNeverConvertedIntoHybridFallback() {
        var collector=new SourceCollector(select("hybrid",List.of(UUID.randomUUID())),(q,ids)->{throw new InterruptedException("cancel");},
                (q,d,n)->{fail("cancel must stop");return List.of();},(u,d)->null,new EvidenceRegistry(UUID.randomUUID()));
        assertThrows(InterruptedException.class,()->collector.collect("query",1000,1000));
    }

    @Test void exposesFrozenSelectionAndSourceForPromptBranching() {
        UUID id=UUID.randomUUID();
        var selection=select("local",List.of(id));
        var collector=new SourceCollector(selection,(q,ids)->List.of(document(id)),(q,d,n)->List.of(),(u,d)->null,
                new EvidenceRegistry(UUID.randomUUID()));
        assertSame(selection,collector.selection(),"selection 必须是只读的冻结选择");
        assertEquals("local",collector.source());
        assertEquals("web",select("web",List.of()).source());
    }

    @Test void sharedRegistryRetainsIdsAcrossInitialAndChapterResearch() throws Exception {
        UUID id=UUID.randomUUID();var registry=new EvidenceRegistry(UUID.randomUUID());
        var collector=new SourceCollector(select("local",List.of(id)),(q,ids)->List.of(document(id)),(q,d,n)->List.of(),(u,d)->null,registry);
        assertEquals(collector.collect("initial",100,100).local(),collector.collect("chapter",100,100).local());
        assertEquals(1,registry.snapshots().size());
    }

    @Test void explicitUrlsAvoidSearchAndScrapeFailureUsesOnlyAvailableSnippet() throws Exception {
        var registry=new EvidenceRegistry(UUID.randomUUID());
        var selection=new SourceCollector.Selection("web",List.of(),List.of("example.com"),List.of("https://example.com/a"),2);
        var collector=new SourceCollector(selection,(q,ids)->{fail("web must not retrieve files");return List.of();},
                (q,d,n)->{fail("explicit URLs must not search");return List.of();},
                (u,d)->new SearchGateway.Hit(u,"title","正文"),registry);
        assertEquals(1,collector.collect("query",100,100).web().size());
        var fallback=new SourceCollector(select("web",List.of()),(q,ids)->List.of(),
                (q,d,n)->List.of(new SearchGateway.Hit("https://example.com/a","title","摘要")),
                (u,d)->{throw new IOException("fetch failed");},new EvidenceRegistry(UUID.randomUUID()));
        assertTrue(fallback.collect("query",100,100).context().contains("摘要"));
    }
}
