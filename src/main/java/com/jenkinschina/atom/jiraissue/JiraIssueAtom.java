package com.jenkinschina.atom.jiraissue;

import com.jenkinschina.atom.sdk.AtomContext;
import com.jenkinschina.atom.sdk.AtomResult;
import com.jenkinschina.atom.sdk.AtomTask;
import com.jenkinschina.atom.sdk.ErrorType;
import com.jenkinschina.atom.sdk.Json;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Create an issue, comment on one, or move it through a workflow — the step half of the JIRA plugins.
 *
 * <p>The JIRA plugin family is mostly triggers (master side, not portable). What is portable is the writing side, and
 * it is three calls: {@code POST /issue}, {@code POST /issue/{key}/comment}, {@code POST /issue/{key}/transitions}.
 * A transition is named by a human ("In Progress") but posted by id, so the atom looks the id up and fails with the
 * available names when it cannot — otherwise the build reports "transition failed" with no way to act on it.
 */
public class JiraIssueAtom implements AtomTask {

    @Override
    public AtomResult run(AtomContext context) throws Exception {
        String baseUrl = context.getParam("baseUrl", "").trim();
        String username = context.getParam("username", "").trim();
        String token = context.getSecret("token");
        if (token == null) {
            token = context.getParam("token", "");
        }
        String action = context.getParam("action", "create").trim().toLowerCase(Locale.ROOT);
        String projectKey = context.getParam("projectKey", "").trim();
        String issueType = context.getParam("issueType", "Task").trim();
        String summary = context.getParam("summary", "");
        String description = context.getParam("description", "");
        String issueKey = context.getParam("issueKey", "").trim();
        String comment = context.getParam("comment", "");
        String transition = context.getParam("transition", "").trim();
        String extraFields = context.getParam("fields", "").trim();
        int timeoutSeconds = Math.max(5, Math.min(context.getIntParam("timeoutSeconds", 30), 300));

        if (baseUrl.isEmpty()) {
            return AtomResult.error(ErrorType.USER, 40001, "baseUrl is required (e.g. https://jira.example.com)");
        }
        if (token == null || token.isBlank()) {
            return AtomResult.error(ErrorType.USER, 40002, "token is required");
        }

        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(timeoutSeconds)).build();
        String root = baseUrl.replaceAll("/+$", "") + "/rest/api/2";

        try {
            switch (action) {
                case "create" -> {
                    if (projectKey.isEmpty() || summary.isBlank()) {
                        return AtomResult.error(ErrorType.USER, 40003, "create needs projectKey and summary");
                    }
                    Map<String, Object> fields = new LinkedHashMap<>();
                    Map<String, Object> project = new LinkedHashMap<>();
                    project.put("key", projectKey);
                    fields.put("project", project);
                    fields.put("summary", summary);
                    fields.put("issuetype", Map.of("name", issueType));
                    if (!description.isBlank()) {
                        fields.put("description", description);
                    }
                    mergeExtra(fields, extraFields);
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("fields", fields);

                    Response created = post(client, root + "/issue", Json.write(payload), username, token, timeoutSeconds);
                    if (!created.ok()) {
                        return AtomResult.error(created.type, 40004, "creating the issue failed: " + created.message());
                    }
                    String key = text(asMap(created.body), "key");
                    String self = text(asMap(created.body), "self");
                    String url = self.isEmpty() ? baseUrl.replaceAll("/+$", "") + "/browse/" + key : self;
                    context.log("[jira] created " + key);
                    return AtomResult.success()
                            .data("action", "create")
                            .data("issueKey", key)
                            .data("issueUrl", url);
                }
                case "comment" -> {
                    requireIssueKey(issueKey);
                    if (comment.isBlank()) {
                        return AtomResult.error(ErrorType.USER, 40005, "comment needs comment text");
                    }
                    Response posted = post(client, root + "/issue/" + encodePath(issueKey) + "/comment",
                            Json.write(Map.of("body", comment)), username, token, timeoutSeconds);
                    if (!posted.ok()) {
                        return AtomResult.error(posted.type, 40006, "commenting failed: " + posted.message());
                    }
                    String id = text(asMap(posted.body), "id");
                    context.log("[jira] commented on " + issueKey + " (comment " + id + ")");
                    return AtomResult.success()
                            .data("action", "comment")
                            .data("issueKey", issueKey)
                            .data("commentId", id);
                }
                case "transition" -> {
                    requireIssueKey(issueKey);
                    if (transition.isEmpty()) {
                        return AtomResult.error(ErrorType.USER, 40007, "transition needs a name or an id");
                    }
                    Response available = get(client, root + "/issue/" + encodePath(issueKey) + "/transitions",
                            username, token, timeoutSeconds);
                    if (!available.ok()) {
                        return AtomResult.error(available.type, 40008,
                                "could not list the transitions of " + issueKey + ": " + available.message());
                    }
                    List<String> names = new ArrayList<>();
                    String id = transition.matches("\\d+") ? transition : "";
                    Object transitions = asMap(available.body).get("transitions");
                    if (transitions instanceof List<?> list) {
                        for (Object item : list) {
                            if (!(item instanceof Map<?, ?> transitionMap)) {
                                continue;
                            }
                            String name = String.valueOf(transitionMap.get("name"));
                            names.add(name);
                            if (id.isEmpty() && name.equalsIgnoreCase(transition)) {
                                id = String.valueOf(transitionMap.get("id"));
                            }
                        }
                    }
                    if (id.isEmpty()) {
                        return AtomResult.error(ErrorType.USER, 40009,
                                "'" + transition + "' is not available on " + issueKey + "; available: "
                                        + (names.isEmpty() ? "(none)" : String.join(", ", names)));
                    }
                    Response moved = post(client, root + "/issue/" + encodePath(issueKey) + "/transitions",
                            Json.write(Map.of("transition", Map.of("id", id))), username, token, timeoutSeconds);
                    if (!moved.ok()) {
                        return AtomResult.error(moved.type, 40010, "the transition failed: " + moved.message());
                    }
                    context.log("[jira] " + issueKey + " -> " + transition + " (id " + id + ")");
                    return AtomResult.success()
                            .data("action", "transition")
                            .data("issueKey", issueKey)
                            .data("transition", transition)
                            .data("transitionId", id);
                }
                default -> {
                    return AtomResult.error(ErrorType.USER, 40011,
                            "unknown action '" + action + "'; known: create, comment, transition");
                }
            }
        } catch (java.net.http.HttpConnectTimeoutException e) {
            return AtomResult.error(ErrorType.ENVIRONMENT, 40012,
                    "could not reach JIRA at " + baseUrl + " within " + timeoutSeconds + "s");
        } catch (java.io.IOException e) {
            return AtomResult.error(ErrorType.THIRD_PART, 40013, "calling JIRA failed: " + e.getMessage());
        }
    }

    private static void requireIssueKey(String issueKey) {
        if (issueKey.isEmpty()) {
            throw new IllegalArgumentException("issueKey is required for this action");
        }
    }

    /** {@code fields} is raw JSON, merged over the generated ones so unusual field ids stay possible. */
    private static void mergeExtra(Map<String, Object> fields, String extra) {
        if (extra == null || extra.isBlank()) {
            return;
        }
        if (Json.parse(extra) instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                fields.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
    }

    private static Response post(HttpClient client, String url, String body, String username, String token,
                                 int timeoutSeconds) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("User-Agent", "jenkins-atom-jira-issue")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        authorize(builder, username, token);
        return Response.of(client.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)));
    }

    private static Response get(HttpClient client, String url, String username, String token, int timeoutSeconds)
            throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Accept", "application/json")
                .header("User-Agent", "jenkins-atom-jira-issue")
                .GET();
        authorize(builder, username, token);
        return Response.of(client.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)));
    }

    /** A personal access token goes as a bearer; username + password as basic. */
    private static void authorize(HttpRequest.Builder builder, String username, String token) {
        if (username == null || username.isBlank()) {
            builder.header("Authorization", "Bearer " + token);
            return;
        }
        String pair = username + ":" + token;
        builder.header("Authorization", "Basic " + Base64.getEncoder().encodeToString(pair.getBytes(StandardCharsets.UTF_8)));
    }

    private static Map<?, ?> asMap(String body) {
        try {
            Object parsed = Json.parse(body == null ? "{}" : body);
            return parsed instanceof Map<?, ?> map ? map : Map.of();
        } catch (RuntimeException e) {
            return Map.of();
        }
    }

    /** Wildcard maps do not take a typed default, so read through one helper. */
    private static String text(Map<?, ?> map, String key) {
        Object value = map.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private static String encodePath(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private record Response(int status, String body, ErrorType type) {
        static Response of(HttpResponse<String> response) {
            int status = response.statusCode();
            ErrorType type = status == 400 || status == 401 || status == 403 || status == 404
                    ? ErrorType.USER : ErrorType.THIRD_PART;
            return new Response(status, response.body(), type);
        }

        boolean ok() {
            return status >= 200 && status < 300;
        }

        String message() {
            String text = body == null ? "" : body.replaceAll("\\s+", " ").trim();
            return "HTTP " + status + (text.isEmpty() ? "" : " " + (text.length() > 200 ? text.substring(0, 200) + "…" : text));
        }
    }
}
