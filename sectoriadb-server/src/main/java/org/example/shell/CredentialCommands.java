package org.example.shell;

import org.example.model.CredentialEntity;
import org.example.service.CredentialService;
import org.springframework.shell.standard.ShellComponent;
import org.springframework.shell.standard.ShellMethod;
import org.springframework.shell.standard.ShellOption;

import java.util.ArrayList;
import java.util.List;

@ShellComponent
public class CredentialCommands {

    private final CredentialService credentialService;

    public CredentialCommands(CredentialService credentialService) {
        this.credentialService = credentialService;
    }

    @ShellMethod(key = "mk-key",
            value = "Create one or more S3 access keys  |  mk-key [--count N] [--desc TEXT] [--access-key KEY] [--secret-key SECRET]")
    public String mkKey(
            @ShellOption(defaultValue = "1", help = "Number of keys to create (1-100)") int count,
            @ShellOption(defaultValue = "", help = "Human-readable description") String desc,
            @ShellOption(defaultValue = "", help = "Custom access key (only with count=1)") String accessKey,
            @ShellOption(defaultValue = "", help = "Custom secret key (only with count=1)") String secretKey) {

        if (count < 1 || count > 100) {
            return "Error: count must be between 1 and 100";
        }

        List<CredentialEntity> created = new ArrayList<>();

        if (!accessKey.isBlank() || !secretKey.isBlank()) {
            if (count != 1) {
                return "Error: --access-key and --secret-key can only be used with --count 1";
            }
            if (accessKey.isBlank() || secretKey.isBlank()) {
                return "Error: both --access-key and --secret-key must be provided together";
            }
            try {
                created.add(credentialService.register(accessKey, secretKey, desc));
            } catch (IllegalArgumentException e) {
                return "Error: " + e.getMessage();
            }
        } else {
            created.addAll(credentialService.createMany(count, desc));
        }

        StringBuilder sb = new StringBuilder();
        if (count == 1 && !accessKey.isBlank()) {
            sb.append("Access key registered:\n");
        } else if (count == 1) {
            sb.append("Access key created:\n");
        } else {
            sb.append(count).append(" access keys created:\n");
        }

        sb.append(String.format("\n%-24s  %s%n", "Access Key ID", "Secret Key"));
        sb.append("─".repeat(80)).append('\n');
        for (CredentialEntity cred : created) {
            sb.append(String.format("%-24s  %s%n", cred.getAccessKeyId(), cred.getSecretKey()));
        }
        sb.append("\n⚠  Save the secret keys. They can be shown again with: keys --secret");

        return sb.toString();
    }

    @ShellMethod(key = "keys",
            value = "List all S3 access keys  |  keys [--secret]")
    public String keys(
            @ShellOption(defaultValue = "false", help = "Show secret keys") boolean secret) {
        List<CredentialEntity> list = credentialService.listAll();
        if (list.isEmpty()) {
            return "No access keys. Use 'mk-key' to create one.\n" +
                   "Note: when no keys exist, auth is disabled and all requests are allowed.";
        }
        StringBuilder sb = new StringBuilder();
        if (secret) {
            sb.append(String.format("%-24s  %-8s  %-30s  %-40s  %s%n",
                    "Access Key ID", "Enabled", "Created", "Secret Key", "Description"));
            sb.append("─".repeat(140)).append('\n');
            for (CredentialEntity c : list) {
                sb.append(String.format("%-24s  %-8s  %-30s  %-40s  %s%n",
                        c.getAccessKeyId(),
                        c.isEnabled() ? "yes" : "no",
                        c.getCreatedAt(),
                        c.getSecretKey(),
                        c.getDescription() != null ? c.getDescription() : ""));
            }
        } else {
            sb.append(String.format("%-24s  %-8s  %-30s  %s%n",
                    "Access Key ID", "Enabled", "Created", "Description"));
            sb.append("─".repeat(90)).append('\n');
            for (CredentialEntity c : list) {
                sb.append(String.format("%-24s  %-8s  %-30s  %s%n",
                        c.getAccessKeyId(),
                        c.isEnabled() ? "yes" : "no",
                        c.getCreatedAt(),
                        c.getDescription() != null ? c.getDescription() : ""));
            }
        }
        return sb.toString().stripTrailing();
    }

    @ShellMethod(key = "rm-key",
            value = "Delete one or more S3 access keys  |  rm-key --id ID1,ID2,... [--yes]")
    public String rmKey(
            @ShellOption(help = "Access Key IDs to delete (comma-separated)") String id,
            @ShellOption(defaultValue = "false", help = "Skip confirmation") boolean yes) {

        String[] ids = id.split(",");
        for (int i = 0; i < ids.length; i++) {
            ids[i] = ids[i].trim();
        }

        if (!yes) {
            String idList = String.join(", ", ids);
            return "Add '--yes' to confirm deletion of key(s): " + idList;
        }

        StringBuilder result = new StringBuilder();
        for (String akId : ids) {
            try {
                credentialService.delete(akId);
                result.append("Deleted: ").append(akId).append('\n');
            } catch (IllegalArgumentException e) {
                result.append("Not found: ").append(akId).append('\n');
            }
        }
        return result.toString().stripTrailing();
    }

    @ShellMethod(key = "enable-key",
            value = "Enable an S3 access key  |  enable-key --id ACCESS_KEY_ID")
    public String enableKey(
            @ShellOption(help = "Access Key ID to enable") String id) {
        try {
            credentialService.setEnabled(id, true);
            return "Enabled access key: " + id;
        } catch (IllegalArgumentException e) {
            return "Error: " + e.getMessage();
        }
    }

    @ShellMethod(key = "disable-key",
            value = "Disable an S3 access key  |  disable-key --id ACCESS_KEY_ID")
    public String disableKey(
            @ShellOption(help = "Access Key ID to disable") String id) {
        try {
            credentialService.setEnabled(id, false);
            return "Disabled access key: " + id;
        } catch (IllegalArgumentException e) {
            return "Error: " + e.getMessage();
        }
    }
}
