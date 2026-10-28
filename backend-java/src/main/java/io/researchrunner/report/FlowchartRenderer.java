package io.researchrunner.report;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.*;
import java.util.List;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;

/** Bounded native renderer for plain Mermaid flowcharts; never executes scripts or external resources. */
public final class FlowchartRenderer {
    private static final Pattern NODE=Pattern.compile("([A-Za-z][A-Za-z0-9_]*)\\[\\\"?([^\\[\\]\\\"<>]+)\\\"?\\]");
    public byte[] png(String diagram) throws Exception {
        String body=diagram.strip().replaceFirst("^```mermaid\\s*","").replaceFirst("\\s*```$","");
        String[] lines=body.split("\\R");
        if(lines.length<2 || !lines[0].strip().matches("(?:flowchart|graph) (?:TD|TB|LR|RL|BT)"))throw new IllegalArgumentException("仅支持基础 Mermaid 流程图");
        Map<String,String> nodes=new LinkedHashMap<>();List<String[]> edges=new ArrayList<>();
        for(int i=1;i<lines.length;i++) {
            String line=lines[i].strip();if(line.isEmpty())continue;
            var matcher=NODE.matcher(line);var replaced=new StringBuilder();
            while(matcher.find()) {
                String id=matcher.group(1),label=matcher.group(2).strip();
                if(label.isEmpty() || label.codePointCount(0,label.length())>40)throw new IllegalArgumentException("流程图节点文字应为 1 至 40 个字符");
                if(nodes.containsKey(id) && !nodes.get(id).equals(label))throw new IllegalArgumentException("流程图节点重复定义");
                nodes.put(id,label);matcher.appendReplacement(replaced,id);
            }
            matcher.appendTail(replaced);line=replaced.toString().replaceFirst(";$","").strip();
            String[] chain=line.split("\\s*-->\\s*");
            for(String id:chain)if(!id.matches("[A-Za-z][A-Za-z0-9_]*"))throw new IllegalArgumentException("流程图包含不支持的语法");
            for(int j=1;j<chain.length;j++)edges.add(new String[]{chain[j-1],chain[j]});
        }
        if(nodes.isEmpty() || nodes.size()>24 || edges.size()>48)throw new IllegalArgumentException("流程图规模超限");
        for(var edge:edges)if(!nodes.containsKey(edge[0]) || !nodes.containsKey(edge[1]) || edge[0].equals(edge[1]))throw new IllegalArgumentException("流程图边引用无效");
        int width=1360,height=((nodes.size()+2)/3)*230+60;
        var image=new BufferedImage(width,height,BufferedImage.TYPE_INT_RGB);var graphics=image.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setColor(Color.WHITE);graphics.fillRect(0,0,width,height);
            Font font;try(var input=FlowchartRenderer.class.getResourceAsStream("/fonts/NotoSansSC.ttf")) {
                if(input==null)throw new IllegalStateException("缺少中文图表字体");font=Font.createFont(Font.TRUETYPE_FONT,input).deriveFont(26f);
            }
            graphics.setFont(font);Map<String,Rectangle> boxes=new LinkedHashMap<>();int position=0;
            for(String id:nodes.keySet()){boxes.put(id,new Rectangle(50+(position%3)*440,30+(position/3)*230,380,160));position++;}
            graphics.setColor(new Color(65,90,120));graphics.setStroke(new BasicStroke(3));
            for(var edge:edges) {
                var from=boxes.get(edge[0]);var to=boxes.get(edge[1]);
                double dx=to.getCenterX()-from.getCenterX(),dy=to.getCenterY()-from.getCenterY();
                double scale=Math.min(dx==0?Double.POSITIVE_INFINITY:190/Math.abs(dx),dy==0?Double.POSITIVE_INFINITY:80/Math.abs(dy));
                int x1=(int)(from.getCenterX()+dx*scale),y1=(int)(from.getCenterY()+dy*scale);
                int x2=(int)(to.getCenterX()-dx*scale),y2=(int)(to.getCenterY()-dy*scale);
                graphics.drawLine(x1,y1,x2,y2);double angle=Math.atan2(dy,dx);
                for(double turn:List.of(-0.5,0.5))graphics.drawLine(x2,y2,x2-(int)(18*Math.cos(angle+turn)),y2-(int)(18*Math.sin(angle+turn)));
            }
            for(var entry:boxes.entrySet()) {
                var box=entry.getValue();graphics.setColor(new Color(237,244,253));graphics.fillRoundRect(box.x,box.y,box.width,box.height,20,20);
                graphics.setColor(new Color(44,69,99));graphics.drawRoundRect(box.x,box.y,box.width,box.height,20,20);
                List<String> wrapped=new ArrayList<>();StringBuilder line=new StringBuilder();var metrics=graphics.getFontMetrics();
                for(int point:nodes.get(entry.getKey()).codePoints().toArray()) {
                    String glyph=new String(Character.toChars(point));
                    if(metrics.stringWidth(line+glyph)>340){wrapped.add(line.toString());line.setLength(0);}line.append(glyph);
                }
                if(!line.isEmpty())wrapped.add(line.toString());
                int y=box.y+(box.height-wrapped.size()*34)/2+27;
                for(String text:wrapped){graphics.drawString(text,box.x+(box.width-metrics.stringWidth(text))/2,y);y+=34;}
            }
        } finally {graphics.dispose();}
        var bytes=new ByteArrayOutputStream();ImageIO.write(image,"png",bytes);return bytes.toByteArray();
    }
}
