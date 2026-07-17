import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/** Writes the deterministic and volatile halves of an assessment evidence manifest. */
public final class EvidenceManifest {
    private static final Pattern KEY = Pattern.compile("[a-z][a-z0-9._-]{0,127}");
    private static final Pattern ARTIFACT_PATH = Pattern.compile("[A-Za-z0-9._/-]{1,512}");
    private static final long MAX_ARTIFACT_BYTES = 128L * 1024L * 1024L;

    private EvidenceManifest() {}

    public static void main(String[] args) {
        try {
            run(Arguments.parse(args));
        } catch (Exception error) {
            System.err.println("evidence-manifest: " + error.getMessage());
            System.exit(2);
        }
    }

    private static void run(Arguments arguments) throws IOException {
        Path root = requireRealDirectory(arguments.root, "evidence root");
        Path output = normalizeNewDirectory(arguments.output, root);

        List<Artifact> artifacts = new ArrayList<>();
        Set<String> artifactLabels = new HashSet<>();
        Set<String> artifactPaths = new HashSet<>();
        for (Map.Entry<String, String> entry : arguments.artifacts.entrySet()) {
            String label = entry.getKey();
            String relativeText = entry.getValue();
            if (!ARTIFACT_PATH.matcher(relativeText).matches()
                    || relativeText.startsWith("/")
                    || relativeText.contains("//")) {
                throw new IllegalArgumentException("invalid artifact path: " + relativeText);
            }
            Path relative = Path.of(relativeText).normalize();
            if (relative.isAbsolute() || relative.getNameCount() == 0
                    || relative.startsWith("..") || !relative.toString().equals(relativeText)) {
                throw new IllegalArgumentException("artifact path must be normalized and relative: " + relativeText);
            }
            Path path = root.resolve(relative).normalize();
            if (!path.startsWith(root) || path.startsWith(output)) {
                throw new IllegalArgumentException("artifact path escapes evidence root: " + relativeText);
            }
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                    || !path.toRealPath().equals(path)) {
                throw new IllegalArgumentException(
                        "artifact must be a regular file without symbolic-link components: " + relativeText);
            }
            long size = Files.size(path);
            if (size <= 0 || size > MAX_ARTIFACT_BYTES) {
                throw new IllegalArgumentException(
                        "artifact must be 1-" + MAX_ARTIFACT_BYTES + " bytes: " + relativeText);
            }
            if (!artifactLabels.add(label) || !artifactPaths.add(relativeText)) {
                throw new IllegalArgumentException("duplicate artifact label or path: " + label);
            }
            artifacts.add(new Artifact(label, relativeText, size, sha256(path)));
        }
        artifacts.sort(Comparator.comparing(Artifact::label));
        requireCompleteInventory(root, output, artifactPaths);

        String core = coreJson(arguments, artifacts);
        String coreHash = sha256(core.getBytes(StandardCharsets.UTF_8));
        String envelope = envelopeJson(arguments, coreHash);

        Path staging = Files.createTempDirectory(output.getParent(), ".streamlens-java-evidence.");
        boolean published = false;
        try {
            Files.writeString(staging.resolve("manifest-core.json"), core, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            Files.writeString(staging.resolve("manifest-envelope.json"), envelope, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            try {
                Files.move(staging, output, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(staging, output);
            }
            published = true;
        } finally {
            if (!published) {
                Files.deleteIfExists(staging.resolve("manifest-core.json"));
                Files.deleteIfExists(staging.resolve("manifest-envelope.json"));
                Files.deleteIfExists(staging);
            }
        }
        System.out.println("Evidence manifests published: " + output);
    }

    private static Path requireRealDirectory(Path input, String label) throws IOException {
        if (input == null || !Files.isDirectory(input, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException(label + " must be a non-symbolic-link directory");
        }
        Path real = input.toRealPath();
        // Canonicalize trusted system aliases such as macOS /var -> /private/var.
        // The final directory itself was checked with NOFOLLOW_LINKS above;
        // artifact paths are independently required to resolve exactly below it.
        return real;
    }

    private static Path normalizeNewDirectory(Path input, Path root) throws IOException {
        if (input == null || input.getFileName() == null) {
            throw new IllegalArgumentException("output directory is required");
        }
        Path parent = requireRealDirectory(input.toAbsolutePath().normalize().getParent(), "output parent");
        Path output = parent.resolve(input.getFileName()).normalize();
        if (!output.startsWith(root) || output.equals(root)) {
            throw new IllegalArgumentException("output directory must be a child of the evidence root");
        }
        if (Files.exists(output, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("output directory must not already exist");
        }
        return output;
    }

    private static void requireCompleteInventory(Path root, Path output, Set<String> expected)
            throws IOException {
        Set<String> discovered = new HashSet<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                if (Files.isSymbolicLink(directory)) {
                    throw new IllegalArgumentException("symbolic link in evidence tree: " + root.relativize(directory));
                }
                return directory.startsWith(output) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                if (Files.isSymbolicLink(file) || !attributes.isRegularFile()) {
                    throw new IllegalArgumentException("unsafe file in evidence tree: " + root.relativize(file));
                }
                discovered.add(root.relativize(file).toString());
                return FileVisitResult.CONTINUE;
            }
        });
        if (!discovered.equals(expected)) {
            Set<String> missing = new HashSet<>(expected);
            missing.removeAll(discovered);
            Set<String> unlisted = new HashSet<>(discovered);
            unlisted.removeAll(expected);
            throw new IllegalArgumentException(
                    "artifact inventory differs; missing=" + missing + ", unlisted=" + unlisted);
        }
    }

    private static String coreJson(Arguments arguments, List<Artifact> artifacts) {
        StringBuilder output = new StringBuilder();
        output.append("{\n")
                .append("  \"schema\": \"streamlens-java-evidence-core-v3\",\n")
                .append("  \"assessment_version\": \"java-v3\",\n")
                .append("  \"revisions\": ");
        appendMap(output, arguments.revisions, "  ");
        output.append(",\n  \"parameters\": ");
        appendMap(output, arguments.parameters, "  ");
        output.append(",\n  \"artifacts\": [");
        for (int index = 0; index < artifacts.size(); index++) {
            Artifact artifact = artifacts.get(index);
            if (index == 0) output.append('\n');
            output.append("    {\"label\": ").append(quote(artifact.label))
                    .append(", \"path\": ").append(quote(artifact.path))
                    .append(", \"size\": ").append(artifact.size)
                    .append(", \"sha256\": ").append(quote(artifact.sha256)).append('}');
            output.append(index + 1 == artifacts.size() ? '\n' : ',').append(index + 1 == artifacts.size() ? "" : "\n");
        }
        output.append("  ]\n}\n");
        return output.toString();
    }

    private static String envelopeJson(Arguments arguments, String coreHash) {
        StringBuilder output = new StringBuilder();
        output.append("{\n")
                .append("  \"schema\": \"streamlens-java-evidence-envelope-v3\",\n")
                .append("  \"assessment_version\": \"java-v3\",\n")
                .append("  \"core_sha256\": ").append(quote(coreHash)).append(",\n")
                .append("  \"generated_at\": ").append(quote(Instant.now().toString())).append(",\n")
                .append("  \"environment\": ");
        appendMap(output, arguments.environment, "  ");
        output.append(",\n  \"runner\": ");
        appendMap(output, arguments.runner, "  ");
        output.append("\n}\n");
        return output.toString();
    }

    private static void appendMap(StringBuilder output, Map<String, String> values, String indent) {
        output.append('{');
        int index = 0;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (index++ == 0) output.append('\n');
            output.append(indent).append("  ").append(quote(entry.getKey())).append(": ")
                    .append(quote(entry.getValue()));
            output.append(index == values.size() ? '\n' : ',').append(index == values.size() ? "" : "\n");
        }
        output.append(indent).append('}');
    }

    private static String quote(String value) {
        StringBuilder output = new StringBuilder(value.length() + 2).append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> output.append("\\\"");
                case '\\' -> output.append("\\\\");
                case '\b' -> output.append("\\b");
                case '\f' -> output.append("\\f");
                case '\n' -> output.append("\\n");
                case '\r' -> output.append("\\r");
                case '\t' -> output.append("\\t");
                default -> {
                    if (character < 0x20) {
                        output.append(String.format(Locale.ROOT, "\\u%04x", (int) character));
                    } else {
                        output.append(character);
                    }
                }
            }
        }
        return output.append('"').toString();
    }

    private static String sha256(Path path) throws IOException {
        MessageDigest digest = digest();
        try (var input = Files.newInputStream(path)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String sha256(byte[] bytes) {
        MessageDigest digest = digest();
        return HexFormat.of().formatHex(digest.digest(bytes));
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private record Artifact(String label, String path, long size, String sha256) {}

    private static final class Arguments {
        Path root;
        Path output;
        final Map<String, String> revisions = new TreeMap<>();
        final Map<String, String> parameters = new TreeMap<>();
        final Map<String, String> environment = new TreeMap<>();
        final Map<String, String> runner = new TreeMap<>();
        final Map<String, String> artifacts = new TreeMap<>();

        static Arguments parse(String[] values) {
            Arguments result = new Arguments();
            if (values.length == 0 || values.length % 2 != 0) {
                throw new IllegalArgumentException("options must be supplied as name/value pairs");
            }
            for (int index = 0; index < values.length; index += 2) {
                String option = values[index];
                String value = values[index + 1];
                switch (option) {
                    case "--root" -> {
                        if (result.root != null) throw new IllegalArgumentException("duplicate --root");
                        result.root = Path.of(value);
                    }
                    case "--output-dir" -> {
                        if (result.output != null) throw new IllegalArgumentException("duplicate --output-dir");
                        result.output = Path.of(value);
                    }
                    case "--revision" -> addEntry(result.revisions, value, option);
                    case "--parameter" -> addEntry(result.parameters, value, option);
                    case "--environment" -> addEntry(result.environment, value, option);
                    case "--runner" -> addEntry(result.runner, value, option);
                    case "--artifact" -> addEntry(result.artifacts, value, option);
                    default -> throw new IllegalArgumentException("unknown option: " + option);
                }
            }
            if (result.root == null || result.output == null || result.revisions.isEmpty()
                    || result.parameters.isEmpty() || result.artifacts.isEmpty()) {
                throw new IllegalArgumentException(
                        "--root, --output-dir, revisions, parameters, and artifacts are required");
            }
            return result;
        }

        private static void addEntry(Map<String, String> destination, String text, String option) {
            int separator = text.indexOf('=');
            if (separator <= 0 || separator == text.length() - 1) {
                throw new IllegalArgumentException(option + " requires key=value");
            }
            String key = text.substring(0, separator);
            String value = text.substring(separator + 1);
            if (!KEY.matcher(key).matches() || value.length() > 4096
                    || value.chars().anyMatch(character -> character == 0 || character == '\n' || character == '\r')) {
                throw new IllegalArgumentException("invalid " + option + " entry");
            }
            if (destination.putIfAbsent(key, value) != null) {
                throw new IllegalArgumentException("duplicate " + option + " key: " + key);
            }
        }
    }
}
