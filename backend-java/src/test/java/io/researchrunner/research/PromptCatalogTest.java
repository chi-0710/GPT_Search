package io.researchrunner.research;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Verifies the verbatim prompt resources, their manifest and PromptCatalog rendering. */
class PromptCatalogTest {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final String HEADER_END="\n-->\n";
    private static final Pattern PLACEHOLDER=Pattern.compile("\\$\\{([A-Za-z0-9_.-]+)}");
    private static final Set<String> STAGES=Set.of("initial_research","initial_research.documents","planner",
            "planner.feedback","section_research","section_reviewer","section_reviewer.revision","section_reviser",
            "fact_checker","fact_reviser","writer","writer.guidelines","visualizer");

    // ---------------------------------------------------------------- helpers

    private static String read(String file) throws IOException {
        try (InputStream stream=PromptCatalog.class.getClassLoader().getResourceAsStream("prompts/"+file)) {
            assertNotNull(stream,"缺少提示词资源："+file);
            return new String(stream.readAllBytes(),StandardCharsets.UTF_8);
        }
    }

    private static JsonNode manifest() throws IOException {
        return JSON.readTree(read("manifest.json"));
    }

    private static Map<String,String> header(String text,String file) {
        assertTrue(text.startsWith("<!-- prompt-migration-v1"),file+" 缺少文件头");
        int end=text.indexOf(HEADER_END);
        assertTrue(end>0,file+" 文件头未结束");
        Map<String,String> fields=new LinkedHashMap<>();
        for(String line:text.substring(0,end).split("\n",-1)) {
            int separator=line.indexOf(": ");
            if(separator>0)fields.put(line.substring(0,separator).trim(),line.substring(separator+2));
        }
        return fields;
    }

    private static String body(String text) {
        return text.substring(text.indexOf(HEADER_END)+HEADER_END.length());
    }

    private static List<String> placeholders(String body) {
        List<String> names=new ArrayList<>();
        Matcher matcher=PLACEHOLDER.matcher(body);
        while(matcher.find())names.add(matcher.group(1));
        return names;
    }

    private static Map<String,Object> filled(PromptCatalog.Stage stage,String role) {
        PromptCatalog.Message message=stage.messages().stream()
                .filter(candidate->candidate.role().equals(role)).findFirst().orElseThrow();
        Map<String,Object> variables=new LinkedHashMap<>();
        for(String name:message.variables())variables.put(name,message.defaults().getOrDefault(name,"值-"+name));
        return variables;
    }

    // ---------------------------------------------------------------- tests

    @Test void sharedCatalogLoadsEveryManifestStageAndResource() {
        PromptCatalog catalog=PromptCatalog.shared();
        assertEquals(STAGES,catalog.stages());
        for(String file:catalog.resourceFiles()) {
            assertNotNull(PromptCatalog.class.getClassLoader().getResourceAsStream("prompts/"+file),"缺少资源 "+file);
        }
        for(String key:catalog.stages()) {
            PromptCatalog.Stage stage=catalog.stage(key);
            assertFalse(stage.messages().isEmpty(),key+" 没有迁移消息");
            assertTrue(stage.messages().stream().anyMatch(message->message.role().equals("user")),key+" 缺少 user 提示词");
            assertFalse(stage.pythonPath().isBlank(),key+" 缺少 Python 来源");
            assertFalse(stage.pythonLines().isBlank(),key+" 缺少 Python 行号");
        }
    }

    @Test void headerHashesManifestHashesAndSourceMetadataAgree() throws IOException {
        JsonNode manifest=manifest();
        for(String key:STAGES) {
            JsonNode stage=manifest.path("stages").path(key);
            assertFalse(stage.isMissingNode(),"manifest 缺少阶段 "+key);
            for(JsonNode message:stage.path("messages")) {
                String file=message.path("file").asText();
                String text=read(file);
                String body=body(text);
                Map<String,String> header=header(text,file);
                assertEquals(key,header.get("stage"),file+" 文件头 stage");
                assertEquals(message.path("role").asText(),header.get("kind"),file+" 文件头 kind");
                assertEquals(stage.path("python").path("path").asText(),header.get("source"),file+" 文件头 source");
                assertEquals(stage.path("python").path("lines").asText(),header.get("lines"),file+" 文件头 lines");
                assertEquals(PromptCatalog.sha256(body),header.get("sha256"),file+" 文件头 sha256 必须等于正文 sha256");
                assertEquals(message.path("sha256").asText(),PromptCatalog.sha256(body),file+" manifest sha256 必须等于正文 sha256");
            }
            if(stage.hasNonNull("protocol")) {
                String file=stage.path("protocol").asText();
                String body=body(read(file));
                assertEquals(PromptCatalog.sha256(body),stage.path("protocol_sha256").asText(),file+" manifest 协议哈希");
                assertFalse(PLACEHOLDER.matcher(body).find(),file+" 协议文件不得包含占位符");
            }
        }
    }

    @Test void placeholderDeclarationsCoverTemplatesExactly() throws IOException {
        JsonNode manifest=manifest();
        for(String key:STAGES) {
            for(JsonNode message:manifest.path("stages").path(key).path("messages")) {
                String file=message.path("file").asText();
                List<String> actual=placeholders(body(read(file)));
                assertEquals(new LinkedHashSet<>(actual),new LinkedHashSet<>(jsonStrings(message.path("variables"))),
                        file+" 占位符必须与 manifest variables 一致");
                var defaultNames=message.path("defaults").fieldNames();
                while(defaultNames.hasNext()) {
                    String name=defaultNames.next();
                    assertTrue(actual.contains(name),file+" defaults 声明了未使用的变量 "+name);
                }
            }
        }
    }

    @Test void renderFillsEveryPlaceholderWithoutResidualTextOrJsonDamage() {
        PromptCatalog catalog=PromptCatalog.shared();
        for(String key:catalog.stages()) {
            for(PromptCatalog.Message message:catalog.stage(key).messages()) {
                Map<String,Object> variables=filled(catalog.stage(key),message.role());
                String rendered=catalog.render(key,message.role(),variables);
                assertFalse(rendered.contains("${"),key+"/"+message.role()+" 渲染后仍有占位符");
                for(Map.Entry<String,Object> entry:variables.entrySet()) {
                    assertTrue(rendered.contains(String.valueOf(entry.getValue())),
                           key+"/"+message.role()+" 未替换变量 "+entry.getKey());
                }
                if(PLACEHOLDER.matcher(message.body()).replaceAll("").contains("{")) {
                    assertTrue(rendered.contains("{"),key+"/"+message.role()+" 渲染破坏了 JSON 花括号");
                }
                if(message.body().contains("table_of_contents")) {
                    assertTrue(rendered.contains("\"table_of_contents\""),"JSON 示例不应当作占位符");
                }
            }
        }
    }

    @Test void instructionAppendsProtocolAndUntrustedMaterialSuffix() {
        PromptCatalog catalog=PromptCatalog.shared();
        String planner=catalog.instruction("planner",filled(catalog.stage("planner"),"user"));
        assertTrue(planner.startsWith(catalog.template("planner","system")),"system 消息必须在最前");
        assertTrue(planner.contains("你是一名研究编辑"));
        assertTrue(planner.contains("Java 输出协议"),"planner 必须附加 Java 输出协议");
        assertTrue(planner.contains(catalog.instructionSuffix()),"必须附加不可信资料声明");

        String writer=catalog.instruction("writer",filled(catalog.stage("writer"),"user"));
        assertTrue(writer.contains("直接返回完整的 Markdown 报告正文"),"writer 协议必须覆盖 Python 的 JSON 输出要求");
        assertFalse(catalog.protocol("initial_research").contains("Java 输出协议"),
                "initial_research 的 Python 提示词已是 Markdown 输出，不应有 Java 协议");
        assertTrue(catalog.protocol("section_reviewer").contains("JSON"));
        assertTrue(catalog.protocol("fact_checker").contains("JSON"));
    }

    @Test void variantsReuseBaseSystemMessageAndSelectTheirConditionalText() {
        PromptCatalog catalog=PromptCatalog.shared();
        var planner=catalog.stage("planner");
        var feedback=catalog.stage("planner.feedback");
        assertEquals("planner",feedback.variantOf());
        assertTrue(catalog.instruction("planner.feedback",
                Map.of("today","01/02/2003","initial_research","摘要","max_sections",3,"human_feedback","补充数据"))
                .startsWith(catalog.template("planner","system")));
        assertTrue(catalog.instruction("planner.feedback",
                Map.of("today","01/02/2003","initial_research","摘要","max_sections",3,"human_feedback","补充数据"))
                .contains("人工反馈：补充数据。你必须依据人工反馈规划章节。"));
        assertFalse(catalog.instruction("planner",
                Map.of("today","01/02/2003","initial_research","摘要","max_sections",3))
                .contains("人工反馈："));

        String withGuidelines=catalog.instruction("writer.guidelines",
                Map.of("date","01/02/2003","query","主题","data","[]","guidelines","['证据支持']"));
        assertTrue(withGuidelines.startsWith(catalog.template("writer","system")));
        assertTrue(withGuidelines.contains("你必须遵循以下要求：['证据支持']"));
    }

    @Test void sourceAndRevisionVariantsUseTheirConditionalText() {
        PromptCatalog catalog=PromptCatalog.shared();
        var documents=catalog.stage("initial_research.documents");
        assertEquals("initial_research",documents.variantOf());
        assertTrue(catalog.protocol("initial_research.documents").isEmpty(),
                "documents 变体没有 Java 输出协议");
        String web=catalog.instruction("initial_research",
                Map.of("question","统计","context","资料","tone","Objective","date","2003-02-01"));
        String local=catalog.instruction("initial_research.documents",
                Map.of("question","统计","context","资料","tone","Objective","date","2003-02-01"));
        assertTrue(web.contains("你必须在报告末尾以参考文献形式列出所有使用过的来源 URL"),web);
        assertTrue(web.contains("[网站链接](url)"),web);
        assertFalse(web.contains("来源文档名称"),web);
        assertTrue(local.contains("你必须在报告末尾以参考文献形式列出所有使用过的来源文档名称"),local);
        assertFalse(local.contains("[网站链接](url)"),"local/hybrid 分支不得要求 URL 超链接");

        var revision=catalog.stage("section_reviewer.revision");
        assertEquals("section_reviewer",revision.variantOf());
        assertEquals(catalog.protocol("section_reviewer"),catalog.protocol("section_reviewer.revision"),
                "修订变体复用基阶段输出协议");
        String first=catalog.instruction("section_reviewer",
                Map.of("guidelines","证据支持","draft","草稿正文"));
        String revised=catalog.instruction("section_reviewer.revision",
                Map.of("guidelines","证据支持","draft","草稿正文","revision_notes","已补充论证"));
        assertFalse(first.contains("修订者已根据你之前的审阅意见"),"首轮审阅不得包含修订说明分支");
        assertTrue(revised.startsWith(catalog.template("section_reviewer","system")),"修订变体复用基阶段系统消息");
        assertTrue(revised.contains("修订者已根据你之前的审阅意见修改了草稿，并提供了以下反馈：\n已补充论证"),revised);
        assertTrue(revised.contains("只有在存在关键问题时才提供额外反馈"));
        assertTrue(revised.contains("要求：证据支持"));
    }

    @Test void missingVariablesAndUndeclaredVariablesFailExplicitly() {
        PromptCatalog catalog=PromptCatalog.shared();
        var error=assertThrows(IllegalStateException.class,()->catalog.instruction("planner",Map.of()));
        assertTrue(error.getMessage().contains("变量缺失"),error.getMessage());
        var unknown=assertThrows(IllegalStateException.class,()->catalog.instruction("planner",
                Map.of("today","x","initial_research","y","max_sections",1,"unexpected","z")));
        assertTrue(unknown.getMessage().contains("未声明"),unknown.getMessage());
        assertThrows(IllegalArgumentException.class,()->catalog.stage("not-a-stage"));
        assertTrue(catalog.protocol("initial_research").isEmpty());
    }

    @Test void catalogRejectsTemplatesWithUndeclaredPlaceholders() throws IOException {
        String demoBody="请检查 ${missing} 占位符";
        String sha=PromptCatalog.sha256(demoBody);
        String resource="<!-- prompt-migration-v1\nstage: demo\nkind: user\nsource: demo.py\nsymbol: Demo\nlines: 1-2"
                +"\nvariables: (none)\ndefaults: {}\nsha256: "+sha+"\n-->\n"+demoBody;
        byte[] resourceBytes=resource.getBytes(StandardCharsets.UTF_8);
        String manifest="{\"version\":1,\"instruction_suffix\":\"\",\"stages\":{\"demo\":{"
                +"\"python\":{\"path\":\"demo.py\",\"symbol\":\"Demo\",\"lines\":\"1-2\"},"
                +"\"messages\":[{\"role\":\"user\",\"file\":\"demo.md\",\"sha256\":\""+sha+"\","
                +"\"variables\":[],\"defaults\":{}}],\"protocol\":null}}}";
        ClassLoader loader=new ClassLoader() {
            @Override public InputStream getResourceAsStream(String name) {
                return name.equals("prompts/demo.md")?new ByteArrayInputStream(resourceBytes):null;
            }
        };
        var error=assertThrows(IllegalStateException.class,
                ()->new PromptCatalog(new ByteArrayInputStream(manifest.getBytes(StandardCharsets.UTF_8)),loader));
        assertTrue(error.getMessage().contains("占位符"),error.getMessage());
    }

    private static List<String> jsonStrings(JsonNode array) {
        List<String> values=new ArrayList<>();
        for(JsonNode node:array)values.add(node.asText());
        return values;
    }
}
