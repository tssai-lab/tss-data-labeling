package com.tss.platform.training;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
public class ShellCommandRunner {

    private static final Logger LOG = LoggerFactory.getLogger(ShellCommandRunner.class);

    public CommandResult run(List<String> command, Path workingDirectory, int timeoutSeconds) {
        List<String> safeCommand = List.copyOf(command);
        LOG.info("执行命令: {} (cwd={}, timeout={}s)", safeCommand, workingDirectory, timeoutSeconds);
        ProcessBuilder builder = new ProcessBuilder(safeCommand);
        if (workingDirectory != null) {
            builder.directory(workingDirectory.toFile());
        }
        builder.redirectErrorStream(true);
        try {
            Process process = builder.start();
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append('\n');
                }
            }
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return CommandResult.failed(-1, output.toString(), "命令超时: " + safeCommand);
            }
            int exitCode = process.exitValue();
            if (exitCode == 0) {
                return CommandResult.success(output.toString());
            }
            return CommandResult.failed(exitCode, output.toString(), "命令失败 exit=" + exitCode);
        } catch (Exception e) {
            return CommandResult.failed(-1, "", e.getMessage());
        }
    }

    public CommandResult runWithInput(
            List<String> command,
            Path workingDirectory,
            String stdinContent,
            int timeoutSeconds
    ) {
        List<String> safeCommand = List.copyOf(command);
        ProcessBuilder builder = new ProcessBuilder(safeCommand);
        if (workingDirectory != null) {
            builder.directory(workingDirectory.toFile());
        }
        builder.redirectErrorStream(true);
        Process process = null;
        try {
            process = builder.start();
            try (var outputStream = process.getOutputStream()) {
                outputStream.write((stdinContent == null ? "" : stdinContent)
                        .getBytes(StandardCharsets.UTF_8));
            }

            StringBuffer output = new StringBuffer();
            Process started = process;
            Thread reader = new Thread(() -> {
                try (var input = started.inputReader(StandardCharsets.UTF_8)) {
                    String line;
                    while ((line = input.readLine()) != null) {
                        output.append(line).append('\n');
                    }
                } catch (Exception exception) {
                    LOG.debug("Failed to drain command output: {}", exception.getMessage());
                }
            }, "shell-command-output-reader");
            reader.setDaemon(true);
            reader.start();

            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
                reader.join(1_000);
                return CommandResult.failed(-1, output.toString(), "command timed out: " + safeCommand);
            }
            reader.join(5_000);
            int exitCode = process.exitValue();
            return exitCode == 0
                    ? CommandResult.success(output.toString())
                    : CommandResult.failed(exitCode, output.toString(), "command failed exit=" + exitCode);
        } catch (Exception exception) {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
            return CommandResult.failed(-1, "", exception.getMessage());
        }
    }

    public CommandResult runScript(Path scriptPath, Path workingDirectory, int timeoutSeconds, String... envPairs) {
        if (!Files.isRegularFile(scriptPath)) {
            return CommandResult.failed(-1, "", "脚本不存在: " + scriptPath);
        }
        List<String> command = new ArrayList<>();
        command.add("/bin/bash");
        command.add(scriptPath.toAbsolutePath().toString());
        ProcessBuilder builder = new ProcessBuilder(command);
        if (workingDirectory != null) {
            builder.directory(workingDirectory.toFile());
        }
        if (envPairs != null) {
            for (int i = 0; i + 1 < envPairs.length; i += 2) {
                builder.environment().put(envPairs[i], envPairs[i + 1]);
            }
        }
        builder.redirectErrorStream(true);
        LOG.info("执行脚本: {} (cwd={})", scriptPath, workingDirectory);
        try {
            Process process = builder.start();
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append('\n');
                }
            }
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return CommandResult.failed(-1, output.toString(), "脚本超时: " + scriptPath);
            }
            int exitCode = process.exitValue();
            if (exitCode == 0) {
                return CommandResult.success(output.toString());
            }
            return CommandResult.failed(exitCode, output.toString(), "脚本失败 exit=" + exitCode);
        } catch (Exception e) {
            return CommandResult.failed(-1, "", e.getMessage());
        }
    }

    public record CommandResult(boolean success, int exitCode, String output, String errorMessage) {
        public static CommandResult success(String output) {
            return new CommandResult(true, 0, output, null);
        }

        public static CommandResult failed(int exitCode, String output, String errorMessage) {
            return new CommandResult(false, exitCode, output, errorMessage);
        }
    }
}
