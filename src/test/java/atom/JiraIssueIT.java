package atom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jenkinschina.atom.sdk.Json;
import com.jenkinschina.atom.sdk.testing.Atom;
import com.jenkinschina.atom.sdk.testing.AtomResult;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The jira-issue atom, started the way the runner starts it, against the fake JIRA in {@code tools/}.
 *
 * <p>These are the checks the old {@code tools/smoke.sh} made — the create payload, both auth styles (bearer token and
 * Basic), the transition lookup by name and by id, the list of available transitions on an unknown one, and the bad
 * inputs — written as JUnit.
 */
class JiraIssueIT {

    private static Fake fake;

    private Atom atom;
    private long logBefore;

    @BeforeAll
    static void startFakeJira() throws Exception {
        fake = Fake.start();
    }

    @AfterAll
    static void stopFakeJira() {
        fake.close();
    }

    @BeforeEach
    void setUp() throws Exception {
        atom = Atom.of();
        logBefore = Files.size(fake.log);
    }

    @Test
    @DisplayName("create posts the fields with the bearer token and reports the issue key and url")
    void createsAnIssue() throws Exception {
        AtomResult run = atom.file(".secrets.json", "{\"token\": \"pat-123\"}")
                .input("baseUrl", fake.url)
                .input("token", "ignored-when-secret-exists")
                .input("action", "create")
                .input("projectKey", "ACME")
                .input("summary", "build 42 failed")
                .input("description", "see console")
                .run();

        run.assertOk();
        assertEquals("ACME-7", run.field("issueKey"), "issue key:\n" + run);
        assertEquals("http://jira.example.com/rest/api/2/issue/10001", run.field("issueUrl"));

        Map<String, Object> create = last("POST", "/rest/api/2/issue");
        assertEquals("Bearer pat-123", create.get("authorization"), "the secret should win over the input\n" + create);
        Map<String, Object> fields = map(body(create), "fields");
        assertEquals("ACME", map(fields, "project").get("key"));
        assertEquals("Task", map(fields, "issuetype").get("name"));
        assertEquals("build 42 failed", fields.get("summary"));
    }

    @Test
    @DisplayName("comment travels as Basic auth, with the username as an input and the token as the password")
    void commentsWithBasicAuth() throws Exception {
        AtomResult run = atom.file(".secrets.json", "{\"token\": \"pw\"}")
                .input("baseUrl", fake.url)
                .input("action", "comment")
                .input("issueKey", "ACME-7")
                .input("comment", "fix is in")
                .input("username", "ci")
                .run();

        run.assertOk();
        assertEquals("20002", run.field("commentId"), "comment id:\n" + run);

        Map<String, Object> comment = last("POST", "/comment");
        String expected = "Basic " + Base64.getEncoder().encodeToString("ci:pw".getBytes(StandardCharsets.UTF_8));
        assertEquals(expected, comment.get("authorization"), "comment auth:\n" + comment);
        assertEquals("fix is in", Json.parseObject(String.valueOf(comment.get("body"))).get("body"));
    }

    @Test
    @DisplayName("a transition name is resolved to its id")
    void transitionsByName() throws Exception {
        AtomResult run = atom.file(".secrets.json", "{\"token\": \"pat-123\"}")
                .input("baseUrl", fake.url)
                .input("action", "transition")
                .input("issueKey", "ACME-7")
                .input("transition", "In Progress")
                .run();

        run.assertOk();
        assertEquals("11", run.field("transitionId"), "transition id:\n" + run);
        Map<String, Object> posted = last("POST", "/transitions");
        assertEquals("11", map(Json.parseObject(String.valueOf(posted.get("body"))), "transition").get("id"),
                "transition payload:\n" + posted);
    }

    @Test
    @DisplayName("an unknown transition lists the ones that exist")
    void refusesAnUnknownTransition() throws Exception {
        AtomResult run = atom.file(".secrets.json", "{\"token\": \"pat-123\"}")
                .input("baseUrl", fake.url)
                .input("action", "transition")
                .input("issueKey", "ACME-7")
                .input("transition", "Reviewed")
                .run();

        run.assertRefused();
        assertTrue(run.field("message").contains("In Progress, Done"),
                "available transitions:\n" + run);
    }

    @Test
    @DisplayName("a numeric id skips the lookup, even on a JIRA that lists no transitions")
    void transitionsByIdWithoutALookup() throws Exception {
        try (Fake withoutTransitions = Fake.start("--no-transitions")) {
            AtomResult run = atom.file(".secrets.json", "{\"token\": \"pat-123\"}")
                    .input("baseUrl", withoutTransitions.url)
                    .input("action", "transition")
                    .input("issueKey", "ACME-7")
                    .input("transition", "31")
                    .run();

            run.assertOk();
            assertEquals("31", run.field("transitionId"), "an explicit id needs no list:\n" + run);
        }
    }

    @Test
    @DisplayName("an unknown action is refused by name")
    void refusesAnUnknownAction() throws Exception {
        AtomResult run = atom.file(".secrets.json", "{\"token\": \"pat-123\"}")
                .input("baseUrl", fake.url)
                .input("action", "explode")
                .run();

        run.assertRefused();
        assertTrue(run.field("message").contains("known: create"), "message:\n" + run);
    }

    @Test
    @DisplayName("create without a summary is refused")
    void refusesACreateWithoutSummary() throws Exception {
        AtomResult run = atom.file(".secrets.json", "{\"token\": \"pat-123\"}")
                .input("baseUrl", fake.url)
                .input("action", "create")
                .run();

        run.assertRefused();
    }

    /** The last request the fake logged for {@code method} and a path ending in {@code suffix}. */
    private Map<String, Object> last(String method, String suffix) throws IOException {
        byte[] all = Files.readAllBytes(fake.log);
        String text = all.length <= logBefore ? ""
                : new String(all, (int) logBefore, all.length - (int) logBefore, StandardCharsets.UTF_8);
        List<Map<String, Object>> entries = new ArrayList<>();
        for (String line : text.split("\n")) {
            if (!line.isBlank()) {
                entries.add(Json.parseObject(line));
            }
        }
        for (int i = entries.size() - 1; i >= 0; i--) {
            Map<String, Object> entry = entries.get(i);
            if (method.equals(entry.get("method")) && String.valueOf(entry.get("path")).endsWith(suffix)) {
                return entry;
            }
        }
        throw new AssertionError("no " + method + " " + suffix + " was logged; requests: " + entries);
    }

    private static Map<String, Object> body(Map<String, Object> entry) {
        String body = String.valueOf(entry.get("body"));
        return body.isBlank() ? Map.of() : Json.parseObject(body);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Map<String, Object> entry, String key) {
        Object value = entry.get(key);
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private static Path module() {
        return Path.of(System.getProperty("atom.module", System.getProperty("user.dir")));
    }

    /** One fake JIRA on its own port; {@code --no-transitions} is the only flag the script knows. */
    private record Fake(Process process, String url, Path log) implements AutoCloseable {

        static Fake start(String... flags) throws Exception {
            int port;
            try (ServerSocket socket = new ServerSocket(0)) {
                port = socket.getLocalPort();
            }
            Path log = Files.createTempFile("jira-requests-", ".log");
            List<String> command = new ArrayList<>(List.of("python3",
                    module().resolve("tools/fake_jira.py").toString(), Integer.toString(port), log.toString()));
            command.addAll(List.of(flags));
            Path output = Files.createTempFile("jira-fake-", ".log");
            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .redirectOutput(output.toFile())
                    .start();
            waitForPort(port, output);
            return new Fake(process, "http://127.0.0.1:" + port, log);
        }

        @Override
        public void close() {
            process.destroy();
            try {
                process.waitFor();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        private static void waitForPort(int port, Path output) throws Exception {
            long deadline = System.currentTimeMillis() + 10_000;
            while (System.currentTimeMillis() < deadline) {
                try (Socket socket = new Socket()) {
                    socket.connect(new InetSocketAddress("127.0.0.1", port), 200);
                    return;
                } catch (IOException notYet) {
                    Thread.sleep(100);
                }
            }
            throw new IllegalStateException("the fake JIRA did not start: " + Files.readString(output));
        }
    }
}
