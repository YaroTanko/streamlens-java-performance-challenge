package com.streamlens.analyzer;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Reads, validates, filters, and aggregates StreamLens NDJSON events. */
public final class Analyzer {
    private static final BigInteger BILLION = BigInteger.valueOf(1_000_000_000L);
    private static final long YEAR_ONE_EPOCH_SECOND = -62_135_596_800L;
    private static final long MINIMUM_OUTPUT_EPOCH_SECOND = -62_167_219_200L;
    private static final long MAXIMUM_OUTPUT_EPOCH_SECOND_EXCLUSIVE = 253_402_300_800L;
    private static final int USER_INDEX_THRESHOLD = 32;

    private Analyzer() {}

    /**
     * Analyzes {@code input} in line order.
     *
     * <p>The method never closes the supplied stream. A pending thread interrupt is
     * observed before processing and between completed reads and aggregation steps.
     */
    public static List<Group> analyze(InputStream input, AnalyzerConfig config)
            throws AnalysisException, InterruptedException {
        if (input == null) {
            throw new AnalysisException("input must not be null");
        }
        if (config == null) {
            throw new AnalysisException("config must not be null");
        }
        checkInterrupted();

        NormalizedConfig normalized = normalize(config);
        Map<String, Aggregate> aggregates = new HashMap<>();
        LineReader reader = new LineReader(input);
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        int lineNumber = 0;

        while (true) {
            int encodedLineLength;
            try {
                encodedLineLength = reader.readLine();
            } catch (IOException failure) {
                throw AnalysisException.readFailure(lineNumber + 1, failure);
            }
            if (encodedLineLength < 0) {
                break;
            }

            lineNumber++;
            checkInterrupted();
            String line = decodeLine(reader.lineBytes(), encodedLineLength, lineNumber, decoder);
            String trimmed = trimContractWhitespace(line);
            if (trimmed.isEmpty()) {
                continue;
            }

            Event event;
            try {
                event = parseEvent(trimmed, lineNumber);
            } catch (AnalysisException failure) {
                throw new AnalysisException(
                        "line " + lineNumber + ": " + failure.detail(), failure);
            }

            checkInterrupted();
            if (isAllowed(event, normalized)) {
                checkInterrupted();
                addEvent(aggregates, normalized, event);
            }
        }

        List<Group> result = new ArrayList<>(aggregates.size());
        for (Aggregate aggregate : aggregates.values()) {
            checkInterrupted();
            Map<String, UserTotal> userIndex = aggregate.userIndex;
            int userCount;
            List<TopUser> allUsers;
            if (userIndex == null) {
                userCount = aggregate.users.size();
                allUsers = new ArrayList<>(userCount);
                for (UserTotal user : aggregate.users) {
                    allUsers.add(new TopUser(user.userId, user.value));
                }
            } else {
                userCount = userIndex.size();
                allUsers = new ArrayList<>(userCount);
                for (UserTotal user : userIndex.values()) {
                    allUsers.add(new TopUser(user.userId, user.value));
                }
            }
            allUsers.sort((left, right) -> {
                if (left.value() != right.value()) {
                    return left.value() > right.value() ? -1 : 1;
                }
                return left.userId().compareTo(right.userId());
            });

            int resultUsers = Math.min(normalized.topK, allUsers.size());
            result.add(new Group(
                    aggregate.windowStart,
                    aggregate.tenantId,
                    aggregate.type,
                    aggregate.count,
                    aggregate.sum,
                    userCount,
                    new ArrayList<>(allUsers.subList(0, resultUsers))));
        }

        result.sort(Comparator
                .comparing(Group::windowStart)
                .thenComparing(Group::tenantId)
                .thenComparing(Group::type));
        checkInterrupted();
        return result;
    }

    private static NormalizedConfig normalize(AnalyzerConfig config) throws AnalysisException {
        Duration window = config.window();
        if (window.isNegative()) {
            throw new AnalysisException("window must be positive");
        }
        if (window.isZero()) {
            window = AnalyzerConfig.DEFAULT_WINDOW;
        }
        if (config.topK() < 0) {
            throw new AnalysisException("top-k must be positive");
        }
        int topK = config.topK() == 0 ? AnalyzerConfig.DEFAULT_TOP_K : config.topK();
        return new NormalizedConfig(
                config.from(), config.to(), config.types(), window, topK);
    }

    private static String decodeLine(
            byte[] encoded,
            int encodedLength,
            int lineNumber,
            CharsetDecoder decoder) throws AnalysisException {
        try {
            return decoder.decode(ByteBuffer.wrap(encoded, 0, encodedLength))
                    .toString();
        } catch (CharacterCodingException failure) {
            throw new AnalysisException(
                    "line " + lineNumber + ": input is not valid UTF-8", failure);
        }
    }

    private static Event parseEvent(String line, int lineNumber) throws AnalysisException {
        EventFields fields = new JsonParser(line).parseEventObject();

        String timestampText = requiredString(fields.timestamp, "timestamp");
        Instant timestamp;
        try {
            timestamp = Rfc3339.parse(timestampText);
        } catch (DateTimeParseException failure) {
            throw new AnalysisException(
                    "timestamp must be RFC3339 with an explicit offset", failure);
        }

        String tenantId = requiredString(fields.tenantId, "tenant_id");
        String userId = requiredString(fields.userId, "user_id");
        String type = requiredString(fields.type, "type");

        String rawValue = fields.value;
        if (rawValue == null) {
            throw new AnalysisException("value is required");
        }
        char first = rawValue.charAt(0);
        if (first != '-' && (first < '0' || first > '9')) {
            throw new AnalysisException("value must be a number");
        }

        double value;
        try {
            value = Double.parseDouble(rawValue);
        } catch (NumberFormatException failure) {
            throw new AnalysisException("value must be a number", failure);
        }
        if (!Double.isFinite(value) || value < 0.0d) {
            throw new AnalysisException("value must be finite and greater than or equal to zero");
        }

        return new Event(lineNumber, timestamp, tenantId, userId, type, value);
    }

    private static String requiredString(String raw, String name) throws AnalysisException {
        if (raw == null) {
            throw new AnalysisException(name + " is required");
        }
        if (raw.isEmpty() || raw.charAt(0) != '"') {
            throw new AnalysisException(name + " must be a string");
        }
        String decoded = new JsonParser(raw).parseStandaloneString();
        if (decoded.isEmpty()) {
            throw new AnalysisException(name + " must not be empty");
        }
        return decoded;
    }

    private static boolean isAllowed(Event event, NormalizedConfig config) {
        if (config.from != null && event.timestamp.isBefore(config.from)) {
            return false;
        }
        if (config.to != null && !event.timestamp.isBefore(config.to)) {
            return false;
        }
        if (config.types.isEmpty()) {
            return true;
        }
        for (String allowedType : config.types) {
            if (event.type.equals(allowedType)) {
                return true;
            }
        }
        return false;
    }

    private static void addEvent(
            Map<String, Aggregate> aggregates,
            NormalizedConfig config,
            Event event) throws AnalysisException {
        Instant windowStart;
        try {
            windowStart = alignWindow(event.timestamp, config.window);
            if (windowStart.getEpochSecond() < MINIMUM_OUTPUT_EPOCH_SECOND
                    || windowStart.getEpochSecond() >= MAXIMUM_OUTPUT_EPOCH_SECOND_EXCLUSIVE) {
                throw new DateTimeException("window start is outside the output range");
            }
        } catch (DateTimeException | ArithmeticException failure) {
            throw new AnalysisException(
                    "line " + event.lineNumber + ": window alignment overflow", failure);
        }
        String key = windowStart.getEpochSecond()
                + ":" + windowStart.getNano()
                + ":" + event.tenantId.length() + ":" + event.tenantId
                + ":" + event.type.length() + ":" + event.type;
        Aggregate aggregate = aggregates.get(key);
        if (aggregate == null) {
            aggregate = new Aggregate(windowStart, event.tenantId, event.type);
            aggregates.put(key, aggregate);
        }

        Map<String, UserTotal> userIndex = aggregate.userIndex;
        UserTotal user = null;
        if (userIndex == null) {
            for (int index = 0; index < aggregate.users.size(); index++) {
                UserTotal candidate = aggregate.users.get(index);
                if (candidate.userId.equals(event.userId)) {
                    user = candidate;
                    break;
                }
            }
        } else {
            user = userIndex.get(event.userId);
        }

        double nextUserSum = event.value;
        if (user != null) {
            nextUserSum = user.value + event.value;
            if (Double.isInfinite(nextUserSum)) {
                throw new AnalysisException(
                        "line " + event.lineNumber
                                + ": user sum overflow for user_id \"" + event.userId + "\"");
            }
        }

        double nextGroupSum = aggregate.sum + event.value;
        if (Double.isInfinite(nextGroupSum)) {
            throw new AnalysisException(
                    "line " + event.lineNumber
                            + ": group sum overflow for tenant_id \"" + event.tenantId
                            + "\" and type \"" + event.type + "\"");
        }

        aggregate.count++;
        aggregate.sum = nextGroupSum;
        if (user == null) {
            user = new UserTotal(event.userId, event.value);
            if (userIndex == null) {
                aggregate.users.add(user);
                if (aggregate.users.size() == USER_INDEX_THRESHOLD) {
                    userIndex = new HashMap<>(USER_INDEX_THRESHOLD << 1);
                    for (UserTotal indexedUser : aggregate.users) {
                        userIndex.put(indexedUser.userId, indexedUser);
                    }
                    aggregate.userIndex = userIndex;
                    aggregate.users = null;
                }
            } else {
                userIndex.put(event.userId, user);
            }
        } else {
            user.value = nextUserSum;
        }
    }

    private static Instant alignWindow(Instant timestamp, Duration window) {
        long windowSeconds = window.getSeconds();
        if (window.getNano() == 0 && windowSeconds > 0) {
            try {
                long deltaSeconds = Math.subtractExact(
                        timestamp.getEpochSecond(), YEAR_ONE_EPOCH_SECOND);
                long quotient = Math.floorDiv(deltaSeconds, windowSeconds);
                long alignedSeconds = Math.addExact(
                        YEAR_ONE_EPOCH_SECOND, Math.multiplyExact(quotient, windowSeconds));
                return Instant.ofEpochSecond(alignedSeconds);
            } catch (ArithmeticException ignored) {
                // Use the general path below when a long intermediate overflows.
            }
        }
        BigInteger windowNanos = BigInteger.valueOf(window.getSeconds())
                .multiply(BILLION)
                .add(BigInteger.valueOf(window.getNano()));
        BigInteger deltaNanos = BigInteger.valueOf(timestamp.getEpochSecond())
                .subtract(BigInteger.valueOf(YEAR_ONE_EPOCH_SECOND))
                .multiply(BILLION)
                .add(BigInteger.valueOf(timestamp.getNano()));

        BigInteger[] quotientAndRemainder = deltaNanos.divideAndRemainder(windowNanos);
        BigInteger quotient = quotientAndRemainder[0];
        if (quotientAndRemainder[1].signum() < 0) {
            quotient = quotient.subtract(BigInteger.ONE);
        }

        BigInteger alignedFromAnchor = quotient.multiply(windowNanos);
        BigInteger absoluteNanos = BigInteger.valueOf(YEAR_ONE_EPOCH_SECOND)
                .multiply(BILLION)
                .add(alignedFromAnchor);
        BigInteger[] secondsAndNanos = absoluteNanos.divideAndRemainder(BILLION);
        BigInteger seconds = secondsAndNanos[0];
        BigInteger nanos = secondsAndNanos[1];
        if (nanos.signum() < 0) {
            seconds = seconds.subtract(BigInteger.ONE);
            nanos = nanos.add(BILLION);
        }
        return Instant.ofEpochSecond(seconds.longValueExact(), nanos.longValueExact());
    }

    private static String trimContractWhitespace(String value) {
        int start = 0;
        while (start < value.length()) {
            int codePoint = value.codePointAt(start);
            if (!isContractWhitespace(codePoint)) {
                break;
            }
            start += Character.charCount(codePoint);
        }

        int end = value.length();
        while (end > start) {
            int codePoint = value.codePointBefore(end);
            if (!isContractWhitespace(codePoint)) {
                break;
            }
            end -= Character.charCount(codePoint);
        }
        return value.substring(start, end);
    }

    private static boolean isContractWhitespace(int codePoint) {
        return Character.isWhitespace(codePoint)
                || Character.isSpaceChar(codePoint)
                || codePoint == 0x0085;
    }

    private static void checkInterrupted() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("analysis interrupted");
        }
    }

    private record NormalizedConfig(
            Instant from,
            Instant to,
            List<String> types,
            Duration window,
            int topK) {}

    private record Event(
            int lineNumber,
            Instant timestamp,
            String tenantId,
            String userId,
            String type,
            double value) {}

    private static final class EventFields {
        private String timestamp;
        private String tenantId;
        private String userId;
        private String type;
        private String value;
    }

    private static final class UserTotal {
        private final String userId;
        private double value;

        private UserTotal(String userId, double value) {
            this.userId = userId;
            this.value = value;
        }
    }

    private static final class Aggregate {
        private final Instant windowStart;
        private final String tenantId;
        private final String type;
        private List<UserTotal> users = new ArrayList<>();
        private Map<String, UserTotal> userIndex;
        private long count;
        private double sum;

        private Aggregate(Instant windowStart, String tenantId, String type) {
            this.windowStart = windowStart;
            this.tenantId = tenantId;
            this.type = type;
        }
    }

    private static final class LineReader {
        private final InputStream input;
        private final byte[] inputBuffer = new byte[8 * 1024];
        private byte[] line = new byte[256];
        private int inputOffset;
        private int inputLimit;
        private int lineLength;

        private LineReader(InputStream input) {
            this.input = input;
        }

        private int readLine() throws IOException {
            lineLength = 0;
            while (true) {
                if (inputOffset >= inputLimit) {
                    inputLimit = input.read(inputBuffer);
                    inputOffset = 0;
                    if (inputLimit < 0) {
                        return lineLength == 0 ? -1 : lineLength;
                    }
                    if (inputLimit == 0) {
                        int next = input.read();
                        if (next < 0) {
                            return lineLength == 0 ? -1 : lineLength;
                        }
                        inputBuffer[0] = (byte) next;
                        inputLimit = 1;
                    }
                }

                int segmentStart = inputOffset;
                while (inputOffset < inputLimit && inputBuffer[inputOffset] != '\n') {
                    inputOffset++;
                }
                append(inputBuffer, segmentStart, inputOffset - segmentStart);
                if (inputOffset < inputLimit) {
                    inputOffset++;
                    return lineLength;
                }
            }
        }

        private byte[] lineBytes() {
            return line;
        }

        private void append(byte[] source, int sourceOffset, int length) {
            int requiredLength = lineLength + length;
            if (requiredLength > line.length) {
                int newLength = Math.max(requiredLength, line.length << 1);
                line = Arrays.copyOf(line, newLength);
            }
            for (int index = 0; index < length; index++) {
                line[lineLength + index] = source[sourceOffset + index];
            }
            lineLength = requiredLength;
        }
    }

    private static final class JsonParser {
        private static final int OBJECT_KEY_OR_END = 1;
        private static final int OBJECT_KEY = 2;
        private static final int OBJECT_COLON = 3;
        private static final int OBJECT_VALUE = 4;
        private static final int OBJECT_COMMA_OR_END = 5;
        private static final int ARRAY_VALUE_OR_END = 6;
        private static final int ARRAY_VALUE = 7;
        private static final int ARRAY_COMMA_OR_END = 8;

        private final String source;
        private int index;

        private JsonParser(String source) {
            this.source = source;
        }

        private EventFields parseEventObject() throws AnalysisException {
            skipWhitespace();
            if (!consume('{')) {
                throw invalid("event must be a JSON object");
            }

            EventFields fields = new EventFields();
            skipWhitespace();
            if (consume('}')) {
                requireEnd();
                return fields;
            }

            while (true) {
                skipWhitespace();
                if (!hasNext() || current() != '"') {
                    throw invalid("object field name must be a string");
                }
                String name = scanString(true);
                skipWhitespace();
                if (!consume(':')) {
                    throw invalid("expected ':' after object field name");
                }
                skipWhitespace();
                int valueStart = index;
                skipValue();
                if (name.equals("timestamp")) {
                    fields.timestamp = source.substring(valueStart, index);
                } else if (name.equals("tenant_id")) {
                    fields.tenantId = source.substring(valueStart, index);
                } else if (name.equals("user_id")) {
                    fields.userId = source.substring(valueStart, index);
                } else if (name.equals("type")) {
                    fields.type = source.substring(valueStart, index);
                } else if (name.equals("value")) {
                    fields.value = source.substring(valueStart, index);
                }

                skipWhitespace();
                if (consume('}')) {
                    requireEnd();
                    return fields;
                }
                if (!consume(',')) {
                    throw invalid("expected ',' or '}' after object field value");
                }
            }
        }

        private String parseStandaloneString() throws AnalysisException {
            skipWhitespace();
            if (!hasNext() || current() != '"') {
                throw invalid("value must be a string");
            }
            String result = scanString(true);
            skipWhitespace();
            requireEnd();
            return result;
        }

        private void skipValue() throws AnalysisException {
            skipWhitespace();
            ArrayDeque<Integer> states = new ArrayDeque<>();
            scanValueStart(states);

            while (!states.isEmpty()) {
                int state = states.pop();
                skipWhitespace();
                switch (state) {
                    case OBJECT_KEY_OR_END -> {
                        if (!consume('}')) {
                            scanRequiredKey();
                            states.push(OBJECT_COLON);
                        }
                    }
                    case OBJECT_KEY -> {
                        scanRequiredKey();
                        states.push(OBJECT_COLON);
                    }
                    case OBJECT_COLON -> {
                        if (!consume(':')) {
                            throw invalid("expected ':' after object field name");
                        }
                        states.push(OBJECT_VALUE);
                    }
                    case OBJECT_VALUE -> {
                        states.push(OBJECT_COMMA_OR_END);
                        scanValueStart(states);
                    }
                    case OBJECT_COMMA_OR_END -> {
                        if (consume(',')) {
                            states.push(OBJECT_KEY);
                        } else if (!consume('}')) {
                            throw invalid("expected ',' or '}' in object");
                        }
                    }
                    case ARRAY_VALUE_OR_END -> {
                        if (!consume(']')) {
                            states.push(ARRAY_COMMA_OR_END);
                            scanValueStart(states);
                        }
                    }
                    case ARRAY_VALUE -> {
                        states.push(ARRAY_COMMA_OR_END);
                        scanValueStart(states);
                    }
                    case ARRAY_COMMA_OR_END -> {
                        if (consume(',')) {
                            states.push(ARRAY_VALUE);
                        } else if (!consume(']')) {
                            throw invalid("expected ',' or ']' in array");
                        }
                    }
                    default -> throw new IllegalStateException("unknown JSON parser state");
                }
            }
        }

        private void scanValueStart(ArrayDeque<Integer> states) throws AnalysisException {
            skipWhitespace();
            if (!hasNext()) {
                throw invalid("expected a JSON value");
            }

            char token = current();
            switch (token) {
                case '"' -> scanString(false);
                case '{' -> {
                    index++;
                    states.push(OBJECT_KEY_OR_END);
                }
                case '[' -> {
                    index++;
                    states.push(ARRAY_VALUE_OR_END);
                }
                case 't' -> scanLiteral("true");
                case 'f' -> scanLiteral("false");
                case 'n' -> scanLiteral("null");
                default -> {
                    if (token == '-' || isDigit(token)) {
                        scanNumber();
                    } else {
                        throw invalid("expected a JSON value");
                    }
                }
            }
        }

        private void scanRequiredKey() throws AnalysisException {
            if (!hasNext() || current() != '"') {
                throw invalid("object field name must be a string");
            }
            scanString(false);
        }

        private String scanString(boolean decode) throws AnalysisException {
            if (!consume('"')) {
                throw invalid("expected a JSON string");
            }
            StringBuilder decoded = decode ? new StringBuilder() : null;
            while (hasNext()) {
                char character = source.charAt(index++);
                if (character == '"') {
                    return decoded == null ? "" : decoded.toString();
                }
                if (character < 0x20) {
                    throw invalid("unescaped control character in string");
                }
                if (character != '\\') {
                    if (decoded != null) {
                        decoded.append(character);
                    }
                    continue;
                }

                if (!hasNext()) {
                    throw invalid("unterminated escape sequence");
                }
                char escape = source.charAt(index++);
                switch (escape) {
                    case '"', '\\', '/' -> append(decoded, escape);
                    case 'b' -> append(decoded, '\b');
                    case 'f' -> append(decoded, '\f');
                    case 'n' -> append(decoded, '\n');
                    case 'r' -> append(decoded, '\r');
                    case 't' -> append(decoded, '\t');
                    case 'u' -> scanUnicodeEscape(decoded);
                    default -> throw invalid("invalid string escape");
                }
            }
            throw invalid("unterminated JSON string");
        }

        private void scanUnicodeEscape(StringBuilder decoded) throws AnalysisException {
            int codeUnit = scanHexCodeUnit();
            if (decoded == null) {
                return;
            }
            if (Character.isHighSurrogate((char) codeUnit)) {
                if (index + 6 <= source.length()
                        && source.charAt(index) == '\\'
                        && source.charAt(index + 1) == 'u') {
                    int saved = index;
                    index += 2;
                    int low = scanHexCodeUnit();
                    if (Character.isLowSurrogate((char) low)) {
                        decoded.appendCodePoint(Character.toCodePoint((char) codeUnit, (char) low));
                        return;
                    }
                    index = saved;
                }
                decoded.append('�');
            } else if (Character.isLowSurrogate((char) codeUnit)) {
                decoded.append('�');
            } else {
                decoded.append((char) codeUnit);
            }
        }

        private int scanHexCodeUnit() throws AnalysisException {
            if (index + 4 > source.length()) {
                throw invalid("incomplete Unicode escape");
            }
            int value = 0;
            for (int count = 0; count < 4; count++) {
                int digit = hexDigit(source.charAt(index++));
                if (digit < 0) {
                    throw invalid("invalid Unicode escape");
                }
                value = value * 16 + digit;
            }
            return value;
        }

        private void scanLiteral(String literal) throws AnalysisException {
            if (!source.startsWith(literal, index)) {
                throw invalid("invalid JSON literal");
            }
            index += literal.length();
        }

        private void scanNumber() throws AnalysisException {
            if (consume('-') && !hasNext()) {
                throw invalid("incomplete JSON number");
            }

            if (consume('0')) {
                if (hasNext() && isDigit(current())) {
                    throw invalid("leading zero in JSON number");
                }
            } else {
                if (!hasNext() || current() < '1' || current() > '9') {
                    throw invalid("invalid JSON number");
                }
                while (hasNext() && isDigit(current())) {
                    index++;
                }
            }

            if (consume('.')) {
                if (!hasNext() || !isDigit(current())) {
                    throw invalid("fraction requires a digit");
                }
                while (hasNext() && isDigit(current())) {
                    index++;
                }
            }

            if (hasNext() && (current() == 'e' || current() == 'E')) {
                index++;
                if (hasNext() && (current() == '+' || current() == '-')) {
                    index++;
                }
                if (!hasNext() || !isDigit(current())) {
                    throw invalid("exponent requires a digit");
                }
                while (hasNext() && isDigit(current())) {
                    index++;
                }
            }
        }

        private void skipWhitespace() {
            while (hasNext()) {
                char character = current();
                if (character != ' ' && character != '\t'
                        && character != '\r' && character != '\n') {
                    return;
                }
                index++;
            }
        }

        private void requireEnd() throws AnalysisException {
            if (hasNext()) {
                throw invalid("unexpected content after JSON value");
            }
        }

        private boolean consume(char expected) {
            if (hasNext() && current() == expected) {
                index++;
                return true;
            }
            return false;
        }

        private boolean hasNext() {
            return index < source.length();
        }

        private char current() {
            return source.charAt(index);
        }

        private AnalysisException invalid(String detail) {
            return new AnalysisException(
                    "invalid JSON at character " + (index + 1) + ": " + detail);
        }

        private static boolean isDigit(char character) {
            return character >= '0' && character <= '9';
        }

        private static int hexDigit(char character) {
            if (character >= '0' && character <= '9') {
                return character - '0';
            }
            if (character >= 'a' && character <= 'f') {
                return character - 'a' + 10;
            }
            if (character >= 'A' && character <= 'F') {
                return character - 'A' + 10;
            }
            return -1;
        }

        private static void append(StringBuilder output, char character) {
            if (output != null) {
                output.append(character);
            }
        }
    }
}
