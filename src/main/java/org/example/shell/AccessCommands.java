package org.example.shell;

import org.example.entity.ManifestEntity;
import org.example.entity.PoolEntity;
import org.example.repository.ManifestRepository;
import org.example.service.PoolService;
import org.springframework.shell.standard.ShellComponent;
import org.springframework.shell.standard.ShellMethod;
import org.springframework.shell.standard.ShellOption;

import java.util.List;
import java.util.Set;

/** Public/private sharing of buckets and objects (the same ACLs the S3 API manages). */
@ShellComponent
public class AccessCommands {

    private static final Set<String> ACLS = Set.of("private", "public-read", "public-read-write");

    private final PoolService poolService;
    private final ManifestRepository manifestRepo;

    public AccessCommands(PoolService poolService, ManifestRepository manifestRepo) {
        this.poolService  = poolService;
        this.manifestRepo = manifestRepo;
    }

    @ShellMethod(key = "bucket-acl",
            value = "Show or set bucket access  |  bucket-acl --bucket B [--acl private|public-read|public-read-write]")
    public String bucketAcl(
            @ShellOption(help = "Bucket name") String bucket,
            @ShellOption(defaultValue = ShellOption.NULL, help = "New ACL; omit to show the current one") String acl) {
        PoolEntity pool = poolService.getByName(bucket);
        if (acl == null) {
            return "bucket '" + bucket + "': " + effective(pool.getAcl());
        }
        poolService.setAcl(bucket, requireAcl(acl));
        return "bucket '" + bucket + "' is now " + acl;
    }

    @ShellMethod(key = "object-acl",
            value = "Show or set object access  |  object-acl --bucket B --key K [--acl private|public-read]")
    public String objectAcl(
            @ShellOption(help = "Bucket name") String bucket,
            @ShellOption(help = "Object key") String key,
            @ShellOption(defaultValue = ShellOption.NULL, help = "New ACL; omit to show the current one") String acl) {
        ManifestEntity object = manifestRepo.findByBucketNameAndObjectKeyAndDeletedFalse(bucket, key)
                .orElseThrow(() -> new IllegalArgumentException("Object not found: " + bucket + "/" + key));
        if (acl == null) {
            return bucket + "/" + key + ": " + effective(object.getAcl());
        }
        object.setAcl(requireAcl(acl));
        manifestRepo.save(object);
        return bucket + "/" + key + " is now " + acl;
    }

    @ShellMethod(key = "public-list",
            value = "List everything that is publicly readable, with URLs  |  public-list [--base-url http://host:8080]")
    public String publicList(
            @ShellOption(defaultValue = "http://localhost:8080", help = "Server URL used to print links") String baseUrl) {
        StringBuilder sb = new StringBuilder();
        for (PoolEntity pool : poolService.listAll()) {
            boolean bucketPublic = isPublic(pool.getAcl());
            if (bucketPublic) {
                sb.append(String.format("bucket  %-20s %s  (all objects)%n", pool.getName(), effective(pool.getAcl())));
            }
            List<ManifestEntity> objects = manifestRepo.findByBucketNameAndDeletedFalse(pool.getName());
            for (ManifestEntity o : objects) {
                if (!bucketPublic && isPublic(o.getAcl())) {
                    sb.append(String.format("object  %s/%s%n        %s/%s/%s%n",
                            pool.getName(), o.getObjectKey(), baseUrl, pool.getName(), o.getObjectKey()));
                }
            }
        }
        return sb.isEmpty() ? "Nothing is public." : sb.toString().stripTrailing();
    }

    private static String requireAcl(String acl) {
        if (!ACLS.contains(acl)) {
            throw new IllegalArgumentException("ACL must be one of: private, public-read, public-read-write");
        }
        return acl;
    }

    private static String effective(String acl) { return acl == null ? "private" : acl; }

    private static boolean isPublic(String acl) {
        return "public-read".equals(acl) || "public-read-write".equals(acl);
    }
}
