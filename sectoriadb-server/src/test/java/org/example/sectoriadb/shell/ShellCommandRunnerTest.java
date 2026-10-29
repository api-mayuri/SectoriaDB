package org.example.sectoriadb.shell;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The command line of the admin CLI as one input for the Spring Shell parser. */
class ShellCommandRunnerTest {

    @Test
    void aValueWithSpacesStaysOneQuotedWord() {
        assertEquals("mk-key --desc \"две слова\" --admin",
                ShellCommandRunner.commandLine(new String[]{"mk-key", "--desc", "две слова", "--admin"}));
        assertEquals("mk-key --desc=\"a b\"", ShellCommandRunner.commandLine(new String[]{"mk-key", "--desc=a b"}));
    }

    @Test
    void quotesAndBackslashesAreEscaped() {
        assertEquals("\"say \\\"hi\\\"\"", ShellCommandRunner.quote("say \"hi\""));
        assertEquals("\"a\\\\b c\"", ShellCommandRunner.quote("a\\b c"));
        assertEquals("\"\"", ShellCommandRunner.quote(""));
        assertEquals("plain", ShellCommandRunner.quote("plain"));
    }

    @Test
    void springPropertiesBeforeTheCommandAreNotPartOfIt() {
        assertEquals("keys --secret",
                ShellCommandRunner.commandLine(new String[]{"--sectoriadb.meta-dir=/data/meta", "keys", "--secret"}));
        assertEquals("", ShellCommandRunner.commandLine(new String[]{"--server.port=8081"}), "no command: the server runs");
        assertEquals("", ShellCommandRunner.commandLine(new String[]{}));
    }
}
