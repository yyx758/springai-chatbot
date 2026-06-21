package com.example.chatbot.agent.review;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class GitReviewService {

    private final GitReviewProperties properties;

    public String getStatus() {
        ensureEnabled();
        return runGit(List.of("status", "--short"));
    }

    public List<String> getChangedFiles() {
        ensureEnabled();
        Set<String> files = new LinkedHashSet<>();
        addLines(files, runGit(List.of("diff", "--name-only")));
        addLines(files, runGit(List.of("diff", "--cached", "--name-only")));
        return new ArrayList<>(files);
    }

    public String getFileDiff(String relativePath) {
        ensureEnabled();
        String safePath = validateRelativePath(relativePath);
        String unstaged = runGit(List.of("diff", "--", safePath));
        String staged = runGit(List.of("diff", "--cached", "--", safePath));
        String diff = (unstaged == null ? "" : unstaged) + (staged == null || staged.isBlank() ? "" : "\n" + staged);
        int maxChars = Math.max(1000, properties.getMaxDiffChars());
        return diff.length() > maxChars ? diff.substring(0, maxChars) + "\n[diff truncated]" : diff;
    }

    private void ensureEnabled() {
        if (!properties.isEnabled()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "git review is disabled");
        }
    }

    private void addLines(Set<String> target, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        for (String line : text.split("\\R")) {
            String value = line.trim();
            if (!value.isBlank()) {
                target.add(value);
            }
        }
    }

    private String validateRelativePath(String path) {
        if (path == null || path.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "relativePath is required");
        }
        String value = path.trim().replace('\\', '/');
        if (value.startsWith("/") || value.startsWith("~") || value.matches("^[A-Za-z]:.*")
                || value.contains("../") || value.equals("..") || value.contains("/..")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid git path");
        }
        return value;
    }

    private String runGit(List<String> args) {
        try {
            List<String> command = new ArrayList<>();
            command.add("git");
            command.addAll(args);
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.directory(new File(properties.getRepositoryPath()));
            builder.redirectErrorStream(true);
            Process process = builder.start();
            boolean completed = process.waitFor(Math.max(1000, properties.getCommandTimeoutMs()), TimeUnit.MILLISECONDS);
            if (!completed) {
                process.destroyForcibly();
                throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "git command timed out");
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (process.exitValue() != 0) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "git command failed: " + output.strip());
            }
            return output;
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "git command failed: " + e.getMessage(), e);
        }
    }
}
