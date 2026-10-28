package io.researchrunner.research;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Loads the verbatim Python prompts migrated into {@code prompts/*.md} and renders their
 * {@code ${name}} placeholders.
 *
 * <p>Prompt bodies were migrated verbatim from the former Python implementation; resource
 * headers keep their Python source/hash provenance. The Python verifier referenced there was
 * retired together with the Python backend, so this class only performs placeholder
 * substitution. {@link String#format} is deliberately not used: JSON braces are
 * ordinary prompt text and only {@code ${name}} is a placeholder.
 *
 * <p>Every stage also carries an optional Java output protocol ({@code <stage>.protocol.md}),
 * which is appended after the verbatim messages instead of editing them, and the manifest
 * instruction suffix (the untrusted-material warning).
 */
public final class PromptCatalog {
    private static final String HEADER_FIRST = "<!-- prompt-migration-v1";
    private static final String HEADER_END = "\n-->\n";
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([A-Za-z0-9_.-]+)}");
    private static final ObjectMapper JSON = new ObjectMapper();

    /** One migrated message (system or user) of a Python prompt. */
    public record Message(String role, String file, String sha256, List<String> variables,
            Map<String, String> defaults, String body) {
        public Message {
            variables = List.copyOf(variables);
            defaults = Map.copyOf(defaults);
        }
    }

    /** One stage of the research graph: Python messages, source metadata and Java protocol. */
    public record Stage(String key, String pythonPath, String pythonSymbol, String pythonLines,
            String pythonConfiguration, List<Message> messages, String protocolFile,
            String protocolSha256, String protocolBody, String variantOf, String notes) {
        public Stage {
            messages = List.copyOf(messages);
        }

        public Set<String> declaredVariables() {
            Set<String> declared = new LinkedHashSet<>();
            for (Message message : messages) declared.addAll(message.variables());
            return declared;
        }
    }

    private final Map<String, Stage> stages;
    private final String instructionSuffix;

    private PromptCatalog() {
        this(openManifest(), PromptCatalog.class.getClassLoader());
    }

    /**
     * Builds a catalog from manifests/resources supplied by tests.
     *
     * @param manifest JSON manifest stream
     * @param resources class loader used to read {@code /prompts/<file>}
     */
    public PromptCatalog(InputStream manifest, ClassLoader resources) {
        Objects.requireNonNull(manifest, "缺少提示词清单");
        Objects.requireNonNull(resources, "缺少资源加载器");
        try {
            JsonNode root = JSON.readTree(manifest.readAllBytes());
            this.instructionSuffix = root.path("instruction_suffix").asText("");
            JsonNode stageNodes = root.path("stages");
            if (!stageNodes.isObject() || stageNodes.isEmpty()) throw new IllegalStateException("提示词清单未声明任何阶段");
            Map<String, Stage> loaded = new LinkedHashMap<>();
            var names = stageNodes.fieldNames();
            while (names.hasNext()) {
                String key = names.next();
                loaded.put(key, loadStage(key, stageNodes.path(key), resources));
            }
            this.stages = Map.copyOf(loaded);
        } catch (IOException invalid) {
            throw new IllegalStateException("提示词清单无法解析：" + invalid.getMessage(), invalid);
        }
    }

    /** Shared catalog backed by the classpath manifest. */
    public static PromptCatalog shared() {
        return Holder.INSTANCE;
    }

    private static final class Holder {
        private static final PromptCatalog INSTANCE = new PromptCatalog();
    }

    public Set<String> stages() {
        return stages.keySet();
    }

    public Stage stage(String key) {
        Stage stage = stages.get(key);
        if (stage == null) throw new IllegalArgumentException("未知提示词阶段：" + key);
        return stage;
    }

    /** Java output protocol for a stage, or an empty string when the Python prompt already fits. */
    public String protocol(String key) {
        return stage(key).protocolBody();
    }

    public String instructionSuffix() {
        return instructionSuffix;
    }

    /** Raw verbatim body of the first message with the given role. */
    public String template(String key, String role) {
        for (Message message : messagesFor(stage(key))) {
            if (message.role().equals(role)) return message.body();
        }
        return "";
    }

    /** Renders the given role (system/user) for a stage. */
    public String render(String key, String role, Map<String, Object> variables) {
        Stage stage = stage(key);
        Set<String> declared = declaredVariables(stage);
        List<String> unknown = variables.keySet().stream().filter(name -> !declared.contains(name)).toList();
        if (!unknown.isEmpty()) throw new IllegalStateException("提示词变量未声明：" + key + " -> " + unknown);
        return renderRole(stage, role, variables);
    }

    /**
     * Full instruction sent to the model: migrated system message, migrated user message,
     * Java protocol and the untrusted-material suffix, joined by blank lines.
     */
    public String instruction(String key, Map<String, Object> variables) {
        Stage stage = stage(key);
        Set<String> declared = declaredVariables(stage);
        List<String> unknown = variables.keySet().stream().filter(name -> !declared.contains(name)).toList();
        if (!unknown.isEmpty()) throw new IllegalStateException("提示词变量未声明：" + key + " -> " + unknown);
        List<String> parts = new ArrayList<>();
        for (String role : List.of("system", "user")) {
            String rendered = renderRole(stage, role, variables);
            if (!rendered.isBlank()) parts.add(rendered);
        }
        if (!stage.protocolBody().isBlank()) parts.add(stage.protocolBody());
        if (!instructionSuffix.isBlank()) parts.add(instructionSuffix);
        return String.join("\n\n", parts);
    }

    // ------------------------------------------------------------------ internals

    /** Variants reuse the system message (and thus the persona) of their base stage. */
    private List<Message> messagesFor(Stage stage) {
        if (stage.variantOf() == null) return stage.messages();
        Stage base = stage(stage.variantOf());
        List<Message> messages = new ArrayList<>();
        for (Message message : base.messages()) {
            if (message.role().equals("system")) messages.add(message);
        }
        messages.addAll(stage.messages());
        return messages;
    }

    private Set<String> declaredVariables(Stage stage) {
        Set<String> declared = new LinkedHashSet<>();
        for (Message message : messagesFor(stage)) declared.addAll(message.variables());
        return declared;
    }

    private String renderRole(Stage stage, String role, Map<String, Object> variables) {
        List<String> rendered = new ArrayList<>();
        for (Message message : messagesFor(stage)) {
            if (!message.role().equals(role)) continue;
            rendered.add(renderBody(stage.key(), message, variables));
        }
        return String.join("\n\n", rendered);
    }

    private static String renderBody(String stage, Message message, Map<String, Object> variables) {
        Map<String, String> values = new LinkedHashMap<>(message.defaults());
        for (String name : message.variables()) {
            if (variables.containsKey(name)) values.put(name, Objects.toString(variables.get(name), ""));
        }
        for (String name : message.variables()) {
            if (!values.containsKey(name)) {
                throw new IllegalStateException("提示词变量缺失：" + stage + "/" + message.role() + " -> ${" + name + "}");
            }
        }
        Matcher matcher = PLACEHOLDER.matcher(message.body());
        StringBuilder output = new StringBuilder();
        while (matcher.find()) {
            String name = matcher.group(1);
            if (!values.containsKey(name)) {
                throw new IllegalStateException("未知占位符：" + stage + "/" + message.role() + " -> ${" + name + "}");
            }
            matcher.appendReplacement(output, Matcher.quoteReplacement(values.get(name)));
        }
        matcher.appendTail(output);
        if (output.indexOf("${") >= 0) {
            throw new IllegalStateException("未知占位符未展开：" + stage + "/" + message.role());
        }
        return output.toString();
    }

    private static Stage loadStage(String key, JsonNode node, ClassLoader resources) {
        String pythonPath = node.path("python").path("path").asText("");
        String pythonSymbol = node.path("python").path("symbol").asText("");
        String pythonLines = node.path("python").path("lines").asText("");
        String pythonConfiguration = node.path("python").path("configuration").asText("");
        List<Message> messages = new ArrayList<>();
        for (JsonNode entry : node.path("messages")) {
            String role = entry.path("role").asText("");
            String file = entry.path("file").asText("");
            String sha = entry.path("sha256").asText("");
            if (!Set.of("system", "user").contains(role) || file.isBlank() || sha.isBlank()) {
                throw new IllegalStateException("提示词清单条目无效：" + key);
            }
            List<String> variables = new ArrayList<>();
            for (JsonNode variable : entry.path("variables")) variables.add(variable.asText());
            Map<String, String> defaults = new LinkedHashMap<>();
            var defaultNames = entry.path("defaults").fieldNames();
            while (defaultNames.hasNext()) {
                String name = defaultNames.next();
                defaults.put(name, entry.path("defaults").path(name).asText());
            }
            Parsed parsed = readResource(resources, file);
            verifyResource(key, role, file, sha, pythonPath, pythonLines, parsed);
            Set<String> placeholders = placeholders(parsed.body());
            if (!placeholders.equals(new LinkedHashSet<>(variables))) {
                throw new IllegalStateException("提示词占位符与清单不一致：" + key + "/" + role + " " + placeholders + " != " + variables);
            }
            messages.add(new Message(role, file, sha, variables, defaults, parsed.body()));
        }
        if (messages.isEmpty()) throw new IllegalStateException("提示词阶段没有消息：" + key);
        String protocolFile = node.path("protocol").isNull() ? null : node.path("protocol").asText(null);
        String protocolSha = node.path("protocol_sha256").isNull() ? null : node.path("protocol_sha256").asText(null);
        String protocolBody = "";
        if (protocolFile != null && !protocolFile.isBlank()) {
            Parsed protocol = readResource(resources, protocolFile);
            if (protocolSha == null || protocolSha.isBlank() || !protocolSha.equals(protocol.sha256())) {
                throw new IllegalStateException("协议文件哈希与清单不一致：" + protocolFile);
            }
            protocolBody = protocol.body();
            if (protocol.body().contains("${")) throw new IllegalStateException("协议文件不得包含占位符：" + protocolFile);
        }
        return new Stage(key, pythonPath, pythonSymbol, pythonLines, pythonConfiguration,
                List.copyOf(messages), protocolFile, protocolSha, protocolBody,
                node.path("variant_of").isNull() ? null : node.path("variant_of").asText(null),
                node.path("notes").asText(""));
    }

    private static void verifyResource(String key, String role, String file, String manifestSha,
            String pythonPath, String pythonLines, Parsed parsed) {
        if (!parsed.sha256().equalsIgnoreCase(manifestSha)) {
            throw new IllegalStateException("提示词正文哈希与清单不一致：" + file);
        }
        Map<String, String> fields = parsed.header();
        if (!key.equals(fields.getOrDefault("stage", ""))) {
            throw new IllegalStateException("提示词文件头 stage 不一致：" + file);
        }
        if (!role.equals(fields.getOrDefault("kind", ""))) {
            throw new IllegalStateException("提示词文件头 kind 不一致：" + file);
        }
        if (!Objects.equals(fields.get("source"), pythonPath)) {
            throw new IllegalStateException("提示词文件头 source 与清单不一致：" + file);
        }
        if (!Objects.equals(fields.get("lines"), pythonLines)) {
            throw new IllegalStateException("提示词文件头 lines 与清单不一致：" + file);
        }
        if (!parsed.sha256().equals(fields.getOrDefault("sha256", ""))) {
            throw new IllegalStateException("提示词文件头 sha256 与正文不一致：" + file);
        }
    }

    private record Parsed(Map<String, String> header, String body, String sha256) { }

    private static Parsed readResource(ClassLoader resources, String file) {
        String text;
        try (InputStream stream = resources.getResourceAsStream("prompts/" + file)) {
            if (stream == null) throw new IllegalStateException("缺少提示词资源：" + file);
            text = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            throw new IllegalStateException("提示词资源无法读取：" + file, unreadable);
        }
        if (!text.startsWith(HEADER_FIRST)) throw new IllegalStateException("提示词资源缺少文件头：" + file);
        int end = text.indexOf(HEADER_END);
        if (end < 0) throw new IllegalStateException("提示词文件头未结束：" + file);
        String headerText = text.substring(0, end);
        String body = text.substring(end + HEADER_END.length());
        Map<String, String> fields = new LinkedHashMap<>();
        for (String line : headerText.split("\n", -1)) {
            int separator = line.indexOf(": ");
            if (separator > 0) fields.put(line.substring(0, separator).trim(), line.substring(separator + 2));
        }
        return new Parsed(Map.copyOf(fields), body, sha256(body));
    }

    private static Set<String> placeholders(String body) {
        Set<String> names = new LinkedHashSet<>();
        Matcher matcher = PLACEHOLDER.matcher(body);
        while (matcher.find()) names.add(matcher.group(1));
        return names;
    }

    private static InputStream openManifest() {
        InputStream stream = PromptCatalog.class.getClassLoader().getResourceAsStream("prompts/manifest.json");
        if (stream == null) throw new IllegalStateException("缺少提示词清单：prompts/manifest.json");
        return stream;
    }

    /** Lower-case SHA-256 hex digest, matching the Python verifier. */
    public static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte value : digest) hex.append(String.format("%02x", value & 0xff));
            return hex.toString();
        } catch (NoSuchAlgorithmException unsupported) {
            throw new IllegalStateException("JVM 不支持 SHA-256", unsupported);
        }
    }

    /** All resource file names referenced by the catalog (messages and protocols). */
    public Collection<String> resourceFiles() {
        Set<String> files = new LinkedHashSet<>();
        for (Stage stage : stages.values()) {
            for (Message message : stage.messages()) files.add(message.file());
            if (stage.protocolFile() != null) files.add(stage.protocolFile());
        }
        return files;
    }
}
