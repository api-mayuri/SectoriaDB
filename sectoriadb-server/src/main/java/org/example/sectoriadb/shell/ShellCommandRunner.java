package org.example.sectoriadb.shell;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.shell.core.InputReader;
import org.springframework.shell.core.ShellRunner;
import org.springframework.shell.core.command.CommandContext;
import org.springframework.shell.core.command.CommandExecutionException;
import org.springframework.shell.core.command.CommandExecutor;
import org.springframework.shell.core.command.CommandNotFoundException;
import org.springframework.shell.core.command.CommandParser;
import org.springframework.shell.core.command.CommandRegistry;
import org.springframework.shell.core.command.ExitStatus;
import org.springframework.shell.core.command.ParsedInput;
import org.springframework.stereotype.Component;

import java.io.PrintWriter;

/**
 * Runs the admin commands. Replaces the runner of Spring Shell (bean name {@code springShellApplicationRunner}):
 * <ul>
 *   <li>a command given on the command line ({@code java -jar sectoriadb.jar mk-key --desc "two words"}) runs once; its
 *       arguments are quoted again before parsing, so a value with spaces stays one value (Spring Shell joins them with
 *       spaces and splits them again); a failure prints {@code Error: <reason>} and makes the exit code 1;</li>
 *   <li>without a command the interactive shell runs when {@code spring.shell.interactive.enabled=true} (the server's
 *       console, {@code docker attach}); otherwise nothing runs (a server without a console, the tests).</li>
 * </ul>
 */
@Component("springShellApplicationRunner")
public class ShellCommandRunner implements ApplicationRunner, ExitCodeGenerator {

    private final CommandParser parser;
    private final CommandRegistry registry;
    private final ObjectProvider<ShellRunner> interactive;
    private volatile int exitCode;

    public ShellCommandRunner(CommandParser parser, CommandRegistry registry, ObjectProvider<ShellRunner> interactive) {
        this.parser = parser;
        this.registry = registry;
        this.interactive = interactive;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        String[] raw = args.getSourceArgs();
        String command = commandLine(raw);
        if (command.isEmpty()) {
            ShellRunner shell = interactive.getIfAvailable();
            if (shell != null && !(shell instanceof org.springframework.shell.core.NonInteractiveShellRunner)) shell.run(raw);
            return;
        }
        PrintWriter out = new PrintWriter(System.out, true);   // UTF-8: Application.main sets it
        exitCode = execute(command, out);
    }

    /** Runs one command line and returns its exit code: 0, or 1 after printing {@code Error: ...}. */
    int execute(String line, PrintWriter out) {
        try {
            ParsedInput input = parser.parse(line);
            ExitStatus status = new CommandExecutor(registry).execute(new CommandContext(input, registry, out, NO_INPUT));
            out.flush();
            if (status.code() == ExitStatus.OK.code()) return 0;
            System.err.println("Error: " + status.description());
            return 1;
        } catch (CommandNotFoundException e) {
            System.err.println("Error: unknown command '" + e.getCommandName() + "'");
            return 1;
        } catch (CommandExecutionException e) {
            Throwable cause = e;
            while (cause.getCause() != null) cause = cause.getCause();
            System.err.println("Error: " + (cause.getMessage() != null ? cause.getMessage() : cause.toString()));
            return 1;
        } catch (RuntimeException e) {   // parse errors (an option without its value)
            System.err.println("Error: " + e.getMessage());
            return 1;
        }
    }

    /** The command words of the arguments (Spring Boot options {@code --name=value} before the command are skipped). */
    static String commandLine(String[] args) {
        StringBuilder sb = new StringBuilder();
        boolean started = false;
        for (String a : args) {
            if (!started && a.startsWith("--") && a.contains("=") && a.indexOf('.') > 0 && a.indexOf('.') < a.indexOf('=')) {
                continue;   // --sectoriadb.meta-dir=... and other properties
            }
            if (!started && a.startsWith("-")) continue;
            started = true;
            if (sb.length() > 0) sb.append(' ');
            sb.append(quote(a));
        }
        return sb.toString();
    }

    /** One argument as one word for the parser: quoted when it has whitespace or quotes ({@code --x=a b} keeps its name). */
    static String quote(String arg) {
        if (arg.isEmpty()) return "\"\"";
        if (!arg.matches("(?s).*[\\s\"\\\\].*")) return arg;
        String escaped = arg.replace("\\", "\\\\").replace("\"", "\\\"");
        int eq = arg.startsWith("--") ? arg.indexOf('=') : -1;
        if (eq > 0 && !arg.substring(0, eq).matches(".*[\\s\"].*")) {
            return arg.substring(0, eq + 1) + "\"" + escaped.substring(escaped.indexOf('=') + 1) + "\"";
        }
        return "\"" + escaped + "\"";
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }

    private static final InputReader NO_INPUT = new InputReader() { };
}
