import net.fabricmc.tinyremapper.*;
import net.fabricmc.tinyremapper.extension.mixin.MixinExtension;
import org.objectweb.asm.commons.Remapper;
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
        verifyInput(Path.of(args[3]), "69df83a3d229a1fe77ddbeb54071a5ac07c2284ed4d623ea075d04e5f0a6d328");
        verifyInput(Path.of(args[4]), "48461c24a560c68afc4042545b654d8d5ea3796a9339681485aed76a326f6ef3");
        root = Path.of(args[0]).toAbsolutePath();
        Files.createDirectories(root.resolve("named"));
        Files.createDirectories(root.resolve("remap-inputs"));
        List<Job> roots = new ArrayList<>();
        for (int i = 3; i < args.length; i++) roots.add(discover(Path.of(args[i]).toAbsolutePath(), "mod" + i));
        Path mappings = Path.of(args[1]);
        Path minecraft = Path.of(args[2]);
        for (Job job : jobs) {
            System.out.println("Remapping " + job.source().getFileName());
            TinyRemapper tr = TinyRemapper.newRemapper()
                    .withMappings(TinyUtils.createTinyMappingProvider(mappings, "intermediary", "named"))
                    .extension(new MixinExtension()).threads(4).build();
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
