package io.researchrunner.research;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.researchrunner.report.ReportFigure;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.core.env.Environment;

/**
 * A3-13 pipeline: generates section illustrations and embeds them as artifact-backed Markdown.
 *
 * <p>Images are returned as {@link ReportFigure}s so publication can save bytes and report
 * state in one transaction. Failures never fail the report: they become warnings.
 */
public final class ReportImages {
    public record Result(String markdown,List<ReportFigure> figures,
            List<Map<String,Object>> images,List<String> warnings) { }

    private static final Pattern HEADING = Pattern.compile("(?m)^#{2,3}\\s+(.+?)\\s*$");
    private static final List<String> EXCLUDED = List.of("引言","结论","参考文献","来源","参考");

    private ReportImages() { }

    public static Result enrich(String markdown,String query,Environment env,ObjectMapper json,
            String publicUrl) {
        if(!env.getProperty("IMAGE_GENERATION_ENABLED",Boolean.class,false)) {
            return new Result(markdown,List.of(),List.of(),List.of());
        }
        int max=env.getProperty("IMAGE_GENERATION_MAX_IMAGES",Integer.class,3);
        if(max<1) return new Result(markdown,List.of(),List.of(),List.of());
        List<String> headings=sections(markdown,max);
        if(headings.isEmpty()) return new Result(markdown,List.of(),List.of(),List.of());
        var figures=new ArrayList<ReportFigure>();
        var images=new ArrayList<Map<String,Object>>();
        var warnings=new ArrayList<String>();
        try {
            ImageGenerator generator=createGenerator(env,json);
            String enriched=markdown;
            for(String heading:headings) {
                try {
                    String prompt="为以下研究报告章节生成一张简洁、专业的信息示意图，不要包含文字、水印或图外装饰。"
                            +"报告主题："+query+"。章节："+heading;
                    var image=generator.generate(prompt);
                    UUID id=UUID.randomUUID();
                    String file="report-image-"+id+".png";
                    figures.add(new ReportFigure(id,file,image.bytes()));
                    String url=publicUrl.replaceAll("/+$","")+"/api/artifacts/"+id;
                    images.add(Map.of("url",url,"alt_text",heading));
                    enriched=insertAfterHeading(enriched,heading,"!["+heading+"]("+url+")");
                } catch(Exception failure) {
                    warnings.add("章节插图生成失败，已跳过："+heading);
                }
            }
            return new Result(enriched,List.copyOf(figures),List.copyOf(images),List.copyOf(warnings));
        } catch(Exception failure) {
            warnings.add("图片生成不可用，报告未插入插图");
            return new Result(markdown,List.of(),List.of(),List.copyOf(warnings));
        }
    }

    record Generated(byte[] bytes,String mime) { }

    interface ImageGenerator { Generated generate(String prompt) throws Exception; }

    private static ImageGenerator createGenerator(Environment env,ObjectMapper json) {
        String provider=env.getProperty("IMAGE_GENERATION_PROVIDER","google").strip().toLowerCase();
        HttpClient client=HttpClient.newHttpClient();
        if(provider.equals("modelslab")) {
            var modelsLab=new ModelsLabImageProvider(client,json,
                    env.getProperty("MODELS_LAB_API_KEY",env.getProperty("MODELSLAB_API_KEY")),
                    env.getProperty("MODELSLAB_BASE_URL","https://modelslab.com/api/v6/images/text2img"),
                    Duration.ofSeconds(120),false);
            return prompt -> { var image=modelsLab.generate(prompt); return new Generated(image.bytes(),image.mimeType()); };
        }
        if(!provider.equals("google") && !provider.equals("gemini")) {
            throw new UnsupportedProviderException(ProviderCatalog.Kind.IMAGE,provider);
        }
        var google=new GoogleImageProvider(client,json,env.getProperty("GOOGLE_API_KEY"),
                env.getProperty("IMAGE_GENERATION_MODEL","gemini-2.5-flash-image"),
                env.getProperty("GOOGLE_IMAGE_BASE_URL","https://generativelanguage.googleapis.com"),
                Duration.ofSeconds(120));
        return prompt -> { var image=google.generate(prompt); return new Generated(image.bytes(),image.mimeType()); };
    }

    private static List<String> sections(String markdown,int max) {
        List<String> headings=new ArrayList<>();
        Matcher matcher=HEADING.matcher(markdown);
        while(matcher.find() && headings.size()<max) {
            String title=matcher.group(1).strip();
            if(title.isEmpty() || EXCLUDED.stream().anyMatch(title::contains)) continue;
            headings.add(title);
        }
        return headings;
    }

    static String insertAfterHeading(String markdown,String heading,String imageMarkdown) {
        Pattern target=Pattern.compile("(?m)^(#{2,3}\\s+"+Pattern.quote(heading)+"\\s*)$");
        Matcher matcher=target.matcher(markdown);
        if(!matcher.find()) return markdown;
        int end=matcher.end();
        return markdown.substring(0,end)+"\n\n"+imageMarkdown+markdown.substring(end);
    }

    /** Kept for the response contract; maps generated images to the legacy research_images shape. */
    static List<Map<String,Object>> infos(List<Map<String,Object>> images) {
        List<Map<String,Object>> result=new ArrayList<>();
        for(var image:images) {
            var item=new LinkedHashMap<String,Object>();
            item.put("url",image.get("url"));item.put("alt_text",image.get("alt_text"));
            result.add(item);
        }
        return List.copyOf(result);
    }
}
