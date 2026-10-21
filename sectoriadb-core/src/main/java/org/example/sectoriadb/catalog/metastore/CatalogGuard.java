package org.example.sectoriadb.catalog.metastore;

import org.example.sectoriadb.catalog.CatalogUnavailableException;
import org.example.sectoriadb.metastore.MetaStoreUnavailableException;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;

/**
 * Translates the metastore module's {@link MetaStoreUnavailableException} into the catalog's own
 * {@link CatalogUnavailableException} at the boundary of a repository, so the layers above the core (the S3 error mapping)
 * need not know the metastore module. One proxy per repository interface, applied by the composition root.
 */
public final class CatalogGuard {

    private CatalogGuard() {
    }

    public static <T> T guard(Class<T> type, T target) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
            try {
                return method.invoke(target, args);
            } catch (InvocationTargetException e) {
                Throwable cause = e.getCause();
                if (cause instanceof MetaStoreUnavailableException unavailable) {
                    throw new CatalogUnavailableException(unavailable.getMessage(), unavailable);
                }
                throw cause;
            }
        }));
    }
}
