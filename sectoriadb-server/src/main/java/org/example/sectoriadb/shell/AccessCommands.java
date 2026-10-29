package org.example.sectoriadb.shell;

import org.example.sectoriadb.catalog.ManifestEntity;
import org.example.sectoriadb.catalog.ManifestRepository;
import org.example.sectoriadb.catalog.PoolEntity;
import org.example.sectoriadb.storage.PoolService;
import org.springframework.shell.core.command.annotation.Command;
import org.springframework.shell.core.command.annotation.Option;
import org.springframework.stereotype.Component;

import java.util.Set;

/** Public/private sharing of buckets and objects (the same ACLs the S3 API manages). */
@Component
public class AccessCommands {

    private static final Set<String> ACLS = Set.of("private", "public-read", "public-read-write");

    private final PoolService poolService;
    private final ManifestRepository manifestRepo;

    public AccessCommands(PoolService poolService, ManifestRepository manifestRepo) {
        this.poolService  = poolService;
        this.manifestRepo = manifestRepo;
    }

    @Command(name = "bucket-acl", description = "Show or set bucket access  |  bucket-acl --bucket B [--acl private|public-read|public-read-write]")
    public String bucketAcl(
            @Option(longName = "bucket", required = true, description = "Bucket name") String bucket,
            @Option(longName = "acl", description = "New ACL; omit to show the current one") String acl) {
        PoolEntity pool = poolService.getByName(bucket);
        if (acl == null) {
            return "bucket '" + bucket + "': " + effective(pool.getAcl());
        }
        poolService.setAcl(bucket, requireAcl(acl));
        return "bucket '" + bucket + "' is now " + acl;
    }

    @Command(name = "object-acl", description = "Show or set object access  |  object-acl --bucket B --key K [--acl private|public-read]")
    public String objectAcl(
            @Option(longName = "bucket", required = true, description = "Bucket name") String bucket,
            @Option(longName = "key", required = true, description = "Object key") String key,
            @Option(longName = "acl", description = "New ACL; omit to show the current one") String acl) {
        if (acl == null) {
            ManifestEntity object = manifestRepo.findCurrent(bucket, key)
                    .orElseThrow(() -> new IllegalArgumentException("Object not found: " + bucket + "/" + key));
            return bucket + "/" + key + ": " + effective(object.getAcl());
        }
        String newAcl = requireAcl(acl);
        manifestRepo.updateCurrent(bucket, key, o -> o.setAcl(newAcl))
                .orElseThrow(() -> new IllegalArgumentException("Object not found: " + bucket + "/" + key));
        return bucket + "/" + key + " is now " + acl;
    }

    @Command(name = "public-list", description = "List everything that is publicly readable, with URLs  |  public-list [--base-url http://host:8080]")
    public String publicList(
            @Option(longName = "base-url", defaultValue = "http://localhost:8080", description = "Server URL used to print links") String baseUrl) {
        StringBuilder sb = new StringBuilder();
        for (PoolEntity pool : poolService.listAll()) {
            boolean bucketPublic = isPublic(pool.getAcl());
            if (bucketPublic) {
                sb.append(String.format("bucket  %-20s %s  (all objects)%n", pool.getName(), effective(pool.getAcl())));
                continue;
            }
            // administrative scan of the bucket's key range, 1000 keys per page
            String after = null;
            while (true) {
                ManifestRepository.ObjectListing page =
                        manifestRepo.listObjects(pool.getName(), "", null, after, 1000);
                for (ManifestRepository.ObjectSummary o : page.objects()) {
                    ManifestEntity m = manifestRepo.findById(o.manifestId()).orElse(null);
                    if (m != null && isPublic(m.getAcl())) {
                        sb.append(String.format("object  %s/%s%n        %s/%s/%s%n",
                                pool.getName(), o.key(), baseUrl, pool.getName(), o.key()));
                    }
                }
                if (!page.truncated()) break;
                after = page.nextMarker();
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
