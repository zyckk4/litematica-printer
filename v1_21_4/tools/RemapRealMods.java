import net.fabricmc.tinyremapper.*;
import net.fabricmc.tinyremapper.extension.mixin.MixinExtension;
import com.google.gson.*;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.jar.*;
import java.util.zip.*;
import java.io.*;
import java.security.MessageDigest;

/** Generated build helper: actual bytecode remap, no dependency version overrides. */
public class RemapRealMods {
    record Job(Path source, Path output, Map<String, Job> nested) {}
    static final List<Job> jobs = new ArrayList<>();
    static Path root;
    public static void main(String[] args) throws Exception {
        if (args.length != 5) throw new IllegalArgumentException(
                "Usage: RemapRealMods <work-dir> <mappings.tiny> <minecraft-intermediary.jar> <litematica.jar> <malilib.jar>");
        verifyInput(Path.of(args[3]), "19fbf46ddc0776c2ff2a1be4cac627b3a236cc4682ce93d01c858e1437e93d4d");
        verifyInput(Path.of(args[4]), "07e2c7569378e37059b35c0cf7a6530554f756737c8e341e1a8c2312dc2d4548");
        root = Path.of(args[0]).toAbsolutePath();
        Files.createDirectories(root.resolve("named"));
        Files.createDirectories(root.resolve("remap-inputs"));
        List<Job> roots = new ArrayList<>();
        for (int i = 3; i < args.length; i++) roots.add(discover(Path.of(args[i]).toAbsolutePath(), "mod" + i));
        Path mappings = Path.of(args[1]);
        Path minecraft = Path.of(args[2]);
        for (Job job : jobs) {
            System.out.println("Remapping " + job.source().getFileName());
            Map<String, JsonObject> refmaps = readRefmaps(job.source());
            // Older Fabric API modules keep named annotation selectors and map
            // them through refmaps. Remap those maps below; treating the named
            // selectors as intermediary annotation strings is incorrect.
            Set<MixinExtension.AnnotationTarget> annotationTargets = refmaps.isEmpty()
                    ? EnumSet.allOf(MixinExtension.AnnotationTarget.class)
                    : EnumSet.of(MixinExtension.AnnotationTarget.HARD);
            TinyRemapper tr = TinyRemapper.newRemapper()
                    .withMappings(TinyUtils.createTinyMappingProvider(mappings, "intermediary", "named"))
                    .extension(new MixinExtension(annotationTargets)).threads(4).build();
            try {
                tr.readClassPath(minecraft);
                for (Job other : jobs) if (other != job) tr.readClassPath(other.source());
                tr.readInputs(job.source());
                try (OutputConsumerPath output = new OutputConsumerPath.Builder(job.output()).build()) {
                    output.addNonClassFiles(job.source(), NonClassCopyMode.FIX_META_INF, tr);
                    tr.apply(output);
                }
                Remapper mapper = tr.getEnvironment().getRemapper();
                try (FileSystem fs = FileSystems.newFileSystem(job.output()); ZipFile zip = new ZipFile(job.source().toFile())) {
                    for (var refmap : refmaps.entrySet()) {
                        JsonObject converted = remapRefmap(refmap.getValue(), zip, mapper);
                        Files.writeString(fs.getPath("/" + refmap.getKey()),
                                new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(converted) + "\n");
                    }
                    for (ZipEntry entry : Collections.list(zip.entries())) {
                        if (!entry.getName().endsWith(".accesswidener")) continue;
                        String input = new String(zip.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8);
                        StringBuilder mapped = new StringBuilder();
                        for (String line : input.split("\\R", -1)) {
                            String clean = line.split("#", 2)[0].trim();
                            if (clean.isEmpty()) { mapped.append(line).append('\n'); continue; }
                            String[] parts = clean.split("\\s+");
                            if (parts[0].equals("accessWidener")) parts[2] = "named";
                            else {
                                String owner = parts[2];
                                if (parts[1].equals("method")) {
                                    parts[3] = mapper.mapMethodName(owner, parts[3], parts[4]);
                                    parts[4] = mapper.mapMethodDesc(parts[4]);
                                } else if (parts[1].equals("field")) {
                                    parts[3] = mapper.mapFieldName(owner, parts[3], parts[4]);
                                    parts[4] = mapper.mapDesc(parts[4]);
                                }
                                parts[2] = mapper.map(owner);
                            }
                            mapped.append(String.join("\t", parts)).append('\n');
                        }
                        Files.writeString(fs.getPath("/" + entry.getName()), mapped.toString());
                    }
                    for (var nested : job.nested().entrySet()) {
                        Files.copy(nested.getValue().output(), fs.getPath("/" + nested.getKey()), StandardCopyOption.REPLACE_EXISTING);
                    }
                    Path manifestPath = fs.getPath("/META-INF/MANIFEST.MF");
                    if (Files.exists(manifestPath)) {
                        Manifest manifest;
                        try (InputStream stream = Files.newInputStream(manifestPath)) { manifest = new Manifest(stream); }
                        manifest.getMainAttributes().putValue("Fabric-Mapping-Namespace", "named");
                        try (OutputStream stream = Files.newOutputStream(manifestPath)) { manifest.write(stream); }
                    }
                }
            } finally { tr.finish(); }
        }
        for (Job job : roots) System.out.println("READY " + job.output());
    }

    static Map<String, JsonObject> readRefmaps(Path source) throws IOException {
        Map<String, JsonObject> result = new LinkedHashMap<>();
        try (ZipFile zip = new ZipFile(source.toFile())) {
            for (ZipEntry entry : Collections.list(zip.entries())) {
                if (!entry.getName().endsWith("refmap.json")) continue;
                try (Reader reader = new InputStreamReader(zip.getInputStream(entry), StandardCharsets.UTF_8)) {
                    JsonObject refmap = JsonParser.parseReader(reader).getAsJsonObject();
                    if (!refmap.has("mappings")) throw new IOException("Missing refmap mappings: " + entry.getName());
                    result.put(entry.getName(), refmap);
                }
            }
        }
        return result;
    }

    static JsonObject remapRefmap(JsonObject source, ZipFile zip, Remapper mapper) throws IOException {
        JsonObject output = source.deepCopy();
        output.add("mappings", remapReferenceMap(source.getAsJsonObject("mappings"), zip, mapper));
        if (source.has("data")) {
            JsonObject data = new JsonObject();
            for (var context : source.getAsJsonObject("data").entrySet()) {
                if (!context.getKey().endsWith(":intermediary")) {
                    throw new IOException("Unsupported refmap namespace: " + context.getKey());
                }
                String name = context.getKey().replace(":intermediary", ":named");
                data.add(name, remapReferenceMap(context.getValue().getAsJsonObject(), zip, mapper));
            }
            output.add("data", data);
        }
        return output;
    }

    static JsonObject remapReferenceMap(JsonObject source, ZipFile zip, Remapper mapper) throws IOException {
        JsonObject output = new JsonObject();
        for (var mixin : source.entrySet()) {
            JsonObject references = mixin.getValue().getAsJsonObject();
            List<String> targets = mixinTargets(zip, mixin.getKey(), references);
            JsonObject mapped = new JsonObject();
            for (var reference : references.entrySet()) {
                // Keys are the unchanged strings in the source annotations.
                mapped.addProperty(reference.getKey(), remapReference(reference.getValue().getAsString(), targets, mapper));
            }
            output.add(mixin.getKey(), mapped);
        }
        return output;
    }

    static List<String> mixinTargets(ZipFile zip, String mixin, JsonObject references) throws IOException {
        ZipEntry entry = zip.getEntry(mixin.replace('.', '/') + ".class");
        if (entry == null) throw new IOException("Missing refmap mixin class: " + mixin);
        ClassNode node = new ClassNode();
        try (InputStream input = zip.getInputStream(entry)) {
            new ClassReader(input).accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        List<String> targets = new ArrayList<>();
        List<AnnotationNode> annotations = new ArrayList<>();
        if (node.visibleAnnotations != null) annotations.addAll(node.visibleAnnotations);
        if (node.invisibleAnnotations != null) annotations.addAll(node.invisibleAnnotations);
        for (AnnotationNode annotation : annotations) {
            if (!annotation.desc.equals("Lorg/spongepowered/asm/mixin/Mixin;")) continue;
            for (int i = 0; i < annotation.values.size(); i += 2) {
                String key = (String) annotation.values.get(i);
                if (!key.equals("value") && !key.equals("targets")) continue;
                for (Object value : (List<?>) annotation.values.get(i + 1)) {
                    String target = value instanceof Type type ? type.getInternalName() : value.toString().replace('.', '/');
                    if (references.has(target)) target = references.get(target).getAsString();
                    targets.add(target);
                }
            }
        }
        if (targets.isEmpty()) throw new IOException("Missing @Mixin targets for " + mixin);
        return targets;
    }

    static String remapReference(String reference, List<String> targets, Remapper mapper) throws IOException {
        if (reference.startsWith("(")) return mapper.mapMethodDesc(reference);
        String owner = null;
        String member = reference;
        if (reference.startsWith("L") && reference.indexOf(';') >= 0) {
            int end = reference.indexOf(';');
            owner = reference.substring(1, end);
            member = reference.substring(end + 1);
            if (member.isEmpty()) return mapper.mapDesc(reference);
        }
        int method = member.indexOf('(');
        int field = member.indexOf(':');
        if (method < 0 && field < 0) return mapper.map(reference);
        int separator = method >= 0 ? method : field;
        String name = member.substring(0, separator);
        String descriptor = member.substring(separator + (method >= 0 ? 0 : 1));
        Set<String> names = new LinkedHashSet<>();
        for (String target : owner != null ? List.of(owner) : targets) {
            names.add(method >= 0 ? mapper.mapMethodName(target, name, descriptor)
                    : mapper.mapFieldName(target, name, descriptor));
        }
        if (names.size() != 1) throw new IOException("Ambiguous refmap member: " + reference + " in " + targets);
        String prefix = owner == null ? "" : "L" + mapper.map(owner) + ";";
        return prefix + names.iterator().next() + (method >= 0 ? mapper.mapMethodDesc(descriptor) : ":" + mapper.mapDesc(descriptor));
    }
    static void verifyInput(Path path, String expected) throws Exception {
        String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
        if (!actual.equals(expected)) throw new IllegalArgumentException("Unrecognized input hash: " + path + " SHA256=" + actual);
    }
    static Job discover(Path source, String prefix) throws Exception {
        Map<String, Job> nested = new LinkedHashMap<>();
        try (ZipFile zip = new ZipFile(source.toFile())) {
            for (ZipEntry entry : Collections.list(zip.entries())) {
                if (!entry.getName().startsWith("META-INF/jars/") || !entry.getName().endsWith(".jar")) continue;
                Path extracted = root.resolve("remap-inputs").resolve(prefix + "-" + Path.of(entry.getName()).getFileName());
                try (InputStream stream = zip.getInputStream(entry)) { Files.copy(stream, extracted, StandardCopyOption.REPLACE_EXISTING); }
                nested.put(entry.getName(), discover(extracted, prefix + "-nested"));
            }
        }
        Path output = root.resolve("named").resolve(source.getFileName().toString().replace(".jar", "-named.jar"));
        Job result = new Job(source, output, nested);
        jobs.add(result);
        return result;
    }
}
