import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.BlockTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.ImportTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberReferenceTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.ModifiersTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/** Trusted, dependency-free audit for the exact candidate Analyzer.java blob. */
public final class SourceAudit {
    private static final int MAX_SOURCE_BYTES = 256 * 1024;
    private static final String REQUIRED_PACKAGE = "com.streamlens.analyzer";

    private static final Set<String> ALLOWED_TYPES = Set.of(
            "java.io.BufferedReader",
            "java.io.BufferedInputStream",
            "java.io.ByteArrayInputStream",
            "java.io.ByteArrayOutputStream",
            "java.io.CharConversionException",
            "java.io.EOFException",
            "java.io.IOException",
            "java.io.InputStream",
            "java.io.InputStreamReader",
            "java.io.Reader",
            "java.io.StringReader",
            "java.lang.ArithmeticException",
            "java.lang.Boolean",
            "java.lang.CharSequence",
            "java.lang.Character",
            "java.lang.Comparable",
            "java.lang.Double",
            "java.lang.Enum",
            "java.lang.Exception",
            "java.lang.IllegalArgumentException",
            "java.lang.IllegalStateException",
            "java.lang.Integer",
            "java.lang.InterruptedException",
            "java.lang.Iterable",
            "java.lang.Long",
            "java.lang.Math",
            "java.lang.Number",
            "java.lang.NumberFormatException",
            "java.lang.Object",
            "java.lang.Override",
            "java.lang.Record",
            "java.lang.RuntimeException",
            "java.lang.String",
            "java.lang.StringBuilder",
            "java.lang.SuppressWarnings",
            "java.lang.Thread",
            "java.math.BigInteger",
            "java.nio.ByteBuffer",
            "java.nio.CharBuffer",
            "java.nio.charset.CharacterCodingException",
            "java.nio.charset.Charset",
            "java.nio.charset.CharsetDecoder",
            "java.nio.charset.CodingErrorAction",
            "java.nio.charset.StandardCharsets",
            "java.time.DateTimeException",
            "java.time.Duration",
            "java.time.Instant",
            "java.time.OffsetDateTime",
            "java.time.ZoneOffset",
            "java.time.format.DateTimeParseException",
            "java.util.ArrayDeque",
            "java.util.ArrayList",
            "java.util.Arrays",
            "java.util.Collection",
            "java.util.Collections",
            "java.util.Comparator",
            "java.util.Deque",
            "java.util.HashMap",
            "java.util.HashSet",
            "java.util.Iterator",
            "java.util.LinkedHashMap",
            "java.util.LinkedHashSet",
            "java.util.List",
            "java.util.Map",
            "java.util.Map.Entry",
            "java.util.NavigableMap",
            "java.util.NavigableSet",
            "java.util.Objects",
            "java.util.Optional",
            "java.util.PriorityQueue",
            "java.util.Queue",
            "java.util.Set",
            "java.util.SortedMap",
            "java.util.SortedSet",
            "java.util.Spliterator",
            "java.util.TreeMap",
            "java.util.TreeSet",
            "java.util.function.BiConsumer",
            "java.util.function.BiFunction",
            "java.util.function.Consumer",
            "java.util.function.Function",
            "java.util.function.Predicate",
            "java.util.function.Supplier",
            "java.util.function.ToDoubleFunction",
            "java.util.function.ToIntFunction",
            "java.util.function.ToLongFunction",
            "java.util.regex.Matcher",
            "java.util.regex.Pattern",
            "java.util.stream.Collector",
            "java.util.stream.Collectors",
            "java.util.stream.Stream"
    );

    private static final Set<String> THREAD_METHODS = Set.of(
            "currentThread", "isInterrupted"
    );

    private static final Set<String> ALLOWED_STATIC_FIELD_TYPES = Set.of(
            "java.lang.String", "java.math.BigInteger", "java.util.regex.Pattern"
    );

    private static final List<String> BENCHMARK_MARKERS = List.of(
            "Benchmark", "Balanced", "HighCardinality", "MostlyFiltered",
            "jmh", "benchfixture", "assessment workload"
    );

    private SourceAudit() {}

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            fail("usage: SourceAudit <candidate-Analyzer.java> <companion-source.java>...");
        }
        Path candidate = Path.of(args[0]).toAbsolutePath().normalize();
        byte[] sourceBytes = Files.readAllBytes(candidate);
        validateRawSource(sourceBytes);

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            fail("a full JDK 21 is required");
        }
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        Path classes = Files.createTempDirectory("streamlens-source-audit-classes-");
        try (StandardJavaFileManager manager = compiler.getStandardFileManager(
                diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
            List<Path> paths = new ArrayList<>();
            paths.add(candidate);
            for (int i = 1; i < args.length; i++) {
                paths.add(Path.of(args[i]).toAbsolutePath().normalize());
            }
            Iterable<? extends JavaFileObject> files = manager.getJavaFileObjectsFromPaths(paths);
            List<String> options = List.of(
                    "--release", "21", "-proc:none", "-Xlint:none",
                    "-d", classes.toString()
            );
            JavacTask task = (JavacTask) compiler.getTask(
                    null, manager, diagnostics, options, null, files);
            List<CompilationUnitTree> units = new ArrayList<>();
            task.parse().forEach(units::add);
            task.analyze();
            rejectCompilationErrors(diagnostics);

            CompilationUnitTree candidateUnit = findCandidateUnit(units, candidate);
            new AuditScanner(Trees.instance(task), candidateUnit).scan(candidateUnit, null);
        } finally {
            deleteTree(classes);
        }
        System.out.println("Candidate source audit passed.");
    }

    private static void validateRawSource(byte[] source) {
        if (source.length == 0 || source.length > MAX_SOURCE_BYTES) {
            fail("Analyzer.java must be 1-" + MAX_SOURCE_BYTES + " bytes");
        }
        String text = new String(source, StandardCharsets.UTF_8);
        if (!Arrays.equals(source, text.getBytes(StandardCharsets.UTF_8))) {
            fail("Analyzer.java must be valid UTF-8");
        }
        if (text.indexOf('\0') >= 0) {
            fail("NUL bytes are prohibited");
        }
        // Java expands Unicode escapes before tokenization, including in comments.
        // Refusing them keeps the exact committed text and parsed tokens identical.
        if (text.contains("\\u") || text.contains("\\U")) {
            fail("Unicode escape sequences are prohibited");
        }
    }

    private static void rejectCompilationErrors(DiagnosticCollector<JavaFileObject> diagnostics) {
        for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics.getDiagnostics()) {
            if (diagnostic.getKind() == Diagnostic.Kind.ERROR) {
                String source = diagnostic.getSource() == null
                        ? "Analyzer.java" : Path.of(diagnostic.getSource().toUri()).getFileName().toString();
                fail(source + ":" + diagnostic.getLineNumber() + ":" + diagnostic.getColumnNumber()
                        + ": " + diagnostic.getMessage(Locale.ROOT));
            }
        }
    }

    private static CompilationUnitTree findCandidateUnit(
            List<CompilationUnitTree> units, Path candidate) {
        for (CompilationUnitTree unit : units) {
            Path path = Path.of(unit.getSourceFile().toUri()).toAbsolutePath().normalize();
            if (path.equals(candidate)) {
                return unit;
            }
        }
        throw new AuditFailure("candidate compilation unit was not parsed");
    }

    private static final class AuditScanner extends TreePathScanner<Void, Void> {
        private final Trees trees;
        private final CompilationUnitTree unit;
        private boolean sawAnalyzer;

        AuditScanner(Trees trees, CompilationUnitTree unit) {
            this.trees = trees;
            this.unit = unit;
        }

        @Override
        public Void visitCompilationUnit(CompilationUnitTree tree, Void unused) {
            String packageName = tree.getPackageName() == null ? "" : tree.getPackageName().toString();
            if (!REQUIRED_PACKAGE.equals(packageName)) {
                reject(tree, "package must remain " + REQUIRED_PACKAGE);
            }
            Void result = super.visitCompilationUnit(tree, unused);
            if (!sawAnalyzer) {
                reject(tree, "top-level Analyzer class is required");
            }
            return result;
        }

        @Override
        public Void visitImport(ImportTree tree, Void unused) {
            if (tree.isStatic()) {
                reject(tree, "static imports are prohibited");
            }
            String imported = tree.getQualifiedIdentifier().toString();
            if (imported.endsWith(".*")) {
                reject(tree, "wildcard imports are prohibited");
            }
            if (!ALLOWED_TYPES.contains(imported)) {
                reject(tree, "import is outside the safe-JDK subset: " + imported);
            }
            return super.visitImport(tree, unused);
        }

        @Override
        public Void visitClass(ClassTree tree, Void unused) {
            TreePath parent = getCurrentPath().getParentPath();
            if (parent != null && parent.getLeaf().getKind() == Tree.Kind.COMPILATION_UNIT) {
                if (!"Analyzer".contentEquals(tree.getSimpleName())) {
                    reject(tree, "additional top-level types are prohibited");
                }
                sawAnalyzer = true;
                if (!tree.getModifiers().getFlags().contains(Modifier.PUBLIC)) {
                    reject(tree, "Analyzer must remain public");
                }
            }
            if (tree.getKind() == Tree.Kind.ENUM) {
                reject(tree, "candidate enum declarations are prohibited");
            }
            inspectModifiers(tree.getModifiers());
            return super.visitClass(tree, unused);
        }

        @Override
        public Void visitBlock(BlockTree tree, Void unused) {
            if (tree.isStatic()) {
                reject(tree, "static initializer blocks are prohibited");
            }
            return super.visitBlock(tree, unused);
        }

        @Override
        public Void visitVariable(VariableTree tree, Void unused) {
            Element element = trees.getElement(getCurrentPath());
            if (element instanceof VariableElement variable
                    && variable.getModifiers().contains(Modifier.STATIC)) {
                Set<Modifier> flags = variable.getModifiers();
                if (!flags.contains(Modifier.PRIVATE) || !flags.contains(Modifier.FINAL)) {
                    reject(tree, "static fields must be private final deep-immutable constants");
                }
                TypeMirror type = variable.asType();
                boolean primitive = type.getKind().isPrimitive();
                String typeName = type.toString();
                if (!primitive && !ALLOWED_STATIC_FIELD_TYPES.contains(typeName)) {
                    reject(tree, "static field type is not an allowed deep-immutable constant: " + typeName);
                }
                if (typeName.equals("java.math.BigInteger") && !isBaselineBigIntegerConstant(tree)) {
                    reject(tree, "static BigInteger is allowed only for the baseline BILLION literal constant");
                }
            }
            return super.visitVariable(tree, unused);
        }

        @Override
        public Void visitMethod(MethodTree tree, Void unused) {
            inspectModifiers(tree.getModifiers());
            if (tree.getName().contentEquals("finalize") && tree.getParameters().isEmpty()) {
                reject(tree, "finalizer declarations are prohibited");
            }
            return super.visitMethod(tree, unused);
        }

        @Override
        public Void visitAnnotation(AnnotationTree tree, Void unused) {
            Element element = trees.getElement(new TreePath(getCurrentPath(), tree.getAnnotationType()));
            if (element instanceof TypeElement type) {
                String name = type.getQualifiedName().toString();
                if (!name.equals("java.lang.Override") && !name.equals("java.lang.SuppressWarnings")) {
                    reject(tree, "annotation is prohibited: " + name);
                }
            }
            return super.visitAnnotation(tree, unused);
        }

        @Override
        public Void visitIdentifier(IdentifierTree tree, Void unused) {
            inspectElement(tree, trees.getElement(getCurrentPath()));
            return super.visitIdentifier(tree, unused);
        }

        @Override
        public Void visitMemberSelect(MemberSelectTree tree, Void unused) {
            inspectElement(tree, trees.getElement(getCurrentPath()));
            return super.visitMemberSelect(tree, unused);
        }

        @Override
        public Void visitMethodInvocation(MethodInvocationTree tree, Void unused) {
            Element element = trees.getElement(getCurrentPath());
            if (element instanceof ExecutableElement executable) {
                inspectExecutable(tree, executable);
            }
            return super.visitMethodInvocation(tree, unused);
        }

        @Override
        public Void visitNewClass(NewClassTree tree, Void unused) {
            Element element = trees.getElement(getCurrentPath());
            if (element instanceof ExecutableElement constructor) {
                inspectExecutable(tree, constructor);
                inspectConstructor(tree, constructor);
            }
            return super.visitNewClass(tree, unused);
        }

        @Override
        public Void visitMemberReference(MemberReferenceTree tree, Void unused) {
            Element element = trees.getElement(getCurrentPath());
            if (element instanceof ExecutableElement executable) {
                inspectExecutable(tree, executable);
            }
            return super.visitMemberReference(tree, unused);
        }

        @Override
        public Void visitLiteral(LiteralTree tree, Void unused) {
            if (tree.getValue() instanceof String value) {
                String lower = value.toLowerCase(Locale.ROOT);
                for (String marker : BENCHMARK_MARKERS) {
                    if (lower.contains(marker.toLowerCase(Locale.ROOT))) {
                        reject(tree, "benchmark/workload marker is prohibited: " + marker);
                    }
                }
            }
            return super.visitLiteral(tree, unused);
        }

        private void inspectModifiers(ModifiersTree modifiers) {
            if (modifiers.getFlags().contains(Modifier.NATIVE)) {
                reject(modifiers, "native code is prohibited");
            }
        }

        private void inspectElement(Tree tree, Element element) {
            if (element instanceof TypeElement type) {
                requireAllowedType(tree, type);
            }
        }

        private void inspectExecutable(Tree tree, ExecutableElement executable) {
            Element owner = executable.getEnclosingElement();
            if (owner instanceof TypeElement type) {
                requireAllowedType(tree, type);
                String ownerName = type.getQualifiedName().toString();
                String methodName = executable.getSimpleName().toString();
                if (ownerName.equals("java.lang.Thread")
                        && !THREAD_METHODS.contains(methodName)) {
                    reject(tree, "Thread method is prohibited: " + methodName);
                }
                if (ownerName.equals("java.lang.Object")
                        && Set.of("wait", "notify", "notifyAll", "hashCode", "toString",
                                "getClass", "clone", "finalize").contains(methodName)) {
                    reject(tree, "object monitor/identity/reflection method is prohibited: " + methodName);
                }
                if (ownerName.equals("java.lang.String") && methodName.equals("intern")) {
                    reject(tree, "String.intern mutates global VM state");
                }
                if ((ownerName.startsWith("java.util.") || ownerName.startsWith("java.util.stream."))
                        && methodName.startsWith("parallel")) {
                    reject(tree, "parallel/common-pool operations are prohibited: " + methodName);
                }
                if (ownerName.equals("java.lang.String")
                        && Set.of("toLowerCase", "toUpperCase", "getBytes").contains(methodName)
                        && executable.getParameters().isEmpty()) {
                    reject(tree, "default locale/charset overload is prohibited: String." + methodName);
                }
                if (ownerName.equals("java.lang.String") && methodName.equals("getBytes")
                        && executable.getParameters().stream()
                                .map(VariableElement::asType)
                                .map(TypeMirror::toString)
                                .anyMatch(name -> name.equals("java.lang.String"))) {
                    reject(tree, "dynamic charset-name overload is prohibited: String.getBytes");
                }
                if (ownerName.equals("java.nio.charset.Charset")
                        && Set.of("defaultCharset", "forName", "availableCharsets", "isSupported")
                                .contains(methodName)) {
                    reject(tree, "default/dynamic charset lookup is prohibited: Charset." + methodName);
                }
                if (ownerName.equals("java.lang.Math") && methodName.equals("random")) {
                    reject(tree, "nondeterministic random source is prohibited: Math.random");
                }
                if ((ownerName.equals("java.time.Instant")
                        || ownerName.equals("java.time.OffsetDateTime"))
                        && methodName.equals("now")) {
                    reject(tree, "ambient wall-clock access is prohibited: " + ownerName + ".now");
                }
                if (ownerName.equals("java.util.Collections") && methodName.equals("shuffle")) {
                    reject(tree, "nondeterministic collection shuffling is prohibited");
                }
                if (ownerName.equals("java.lang.String")
                        && (methodName.equals("format") || methodName.equals("formatted"))) {
                    reject(tree, "default-locale formatting is prohibited: String." + methodName);
                }
                if ((ownerName.equals("java.lang.Boolean") && methodName.equals("getBoolean"))
                        || (ownerName.equals("java.lang.Integer") && methodName.equals("getInteger"))
                        || (ownerName.equals("java.lang.Long") && methodName.equals("getLong"))) {
                    reject(tree, "system-property access is prohibited: " + ownerName + "." + methodName);
                }
            }
        }

        private void inspectConstructor(Tree tree, ExecutableElement constructor) {
            Element owner = constructor.getEnclosingElement();
            if (!(owner instanceof TypeElement type)) {
                return;
            }
            String ownerName = type.getQualifiedName().toString();
            List<? extends VariableElement> parameters = constructor.getParameters();
            if (ownerName.equals("java.io.InputStreamReader")) {
                boolean explicitCharsetObject = parameters.stream()
                        .map(VariableElement::asType)
                        .map(TypeMirror::toString)
                        .anyMatch(name -> name.equals("java.nio.charset.Charset")
                                || name.equals("java.nio.charset.CharsetDecoder"));
                if (!explicitCharsetObject) {
                    reject(tree, "default/dynamic-charset InputStreamReader constructor is prohibited");
                }
            }
            if (ownerName.equals("java.lang.String") && !parameters.isEmpty()
                    && isByteArray(parameters.getFirst().asType())) {
                boolean explicitCharset = parameters.stream()
                        .map(VariableElement::asType)
                        .map(TypeMirror::toString)
                        .anyMatch(name -> name.equals("java.nio.charset.Charset"));
                if (!explicitCharset) {
                    reject(tree, "default-charset String byte constructor is prohibited");
                }
            }
        }

        private boolean isByteArray(TypeMirror type) {
            return type.getKind() == TypeKind.ARRAY
                    && ((ArrayType) type).getComponentType().getKind() == TypeKind.BYTE;
        }

        private boolean isBaselineBigIntegerConstant(VariableTree tree) {
            if (!tree.getName().contentEquals("BILLION")
                    || !(tree.getInitializer() instanceof MethodInvocationTree invocation)
                    || invocation.getArguments().size() != 1
                    || !(invocation.getArguments().getFirst() instanceof LiteralTree literal)
                    || !(literal.getValue() instanceof Long value)
                    || value != 1_000_000_000L) {
                return false;
            }
            Element method = trees.getElement(new TreePath(getCurrentPath(), invocation));
            if (!(method instanceof ExecutableElement executable)
                    || !executable.getSimpleName().contentEquals("valueOf")) {
                return false;
            }
            Element owner = executable.getEnclosingElement();
            return owner instanceof TypeElement type
                    && type.getQualifiedName().contentEquals("java.math.BigInteger");
        }

        private void requireAllowedType(Tree tree, TypeElement type) {
            String name = type.getQualifiedName().toString();
            if (name.startsWith(REQUIRED_PACKAGE + ".") || ALLOWED_TYPES.contains(name)) {
                return;
            }
            // Local and anonymous types have no qualified name; their enclosing
            // source declaration is already audited recursively.
            if (name.isEmpty() || isNestedInAnalyzer(type)) {
                return;
            }
            reject(tree, "type is outside the safe-JDK subset: " + name);
        }

        private boolean isNestedInAnalyzer(TypeElement type) {
            Element current = type;
            while (current != null && !(current instanceof PackageElement)) {
                if (current instanceof TypeElement enclosing
                        && enclosing.getQualifiedName().toString()
                                .equals(REQUIRED_PACKAGE + ".Analyzer")) {
                    return true;
                }
                current = current.getEnclosingElement();
            }
            return false;
        }

        private void reject(Tree tree, String reason) {
            long position = trees.getSourcePositions().getStartPosition(unit, tree);
            long line = position < 0 ? 0 : unit.getLineMap().getLineNumber(position);
            long column = position < 0 ? 0 : unit.getLineMap().getColumnNumber(position);
            throw new AuditFailure("Analyzer.java:" + line + ":" + column + ": " + reason);
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            paths.sorted((left, right) -> right.compareTo(left)).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException error) {
                    throw new DeleteFailure(error);
                }
            });
        } catch (DeleteFailure failure) {
            throw failure.error;
        }
    }

    private static void fail(String message) {
        throw new AuditFailure(message);
    }

    private static final class AuditFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;

        AuditFailure(String message) {
            super(message);
        }
    }

    private static final class DeleteFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;

        final IOException error;

        DeleteFailure(IOException error) {
            this.error = error;
        }
    }
}
