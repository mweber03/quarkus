package io.quarkus.cache.runtime;

import java.time.Duration;
import java.util.function.Function;

import javax.annotation.Priority;
import javax.interceptor.AroundInvoke;
import javax.interceptor.Interceptor;
import javax.interceptor.InvocationContext;

import org.jboss.logging.Logger;

import io.quarkus.cache.CacheException;
import io.quarkus.cache.CacheResult;
import io.smallrye.mutiny.TimeoutException;
import io.smallrye.mutiny.Uni;

@CacheResult(cacheName = "") // The `cacheName` attribute is @Nonbinding.
@Interceptor
@Priority(CacheInterceptor.BASE_PRIORITY + 2)
public class CacheResultInterceptor extends CacheInterceptor {

    private static final Logger LOGGER = Logger.getLogger(CacheResultInterceptor.class);
    private static final String INTERCEPTOR_BINDING_ERROR_MSG = "The Quarkus cache extension is not working properly (CacheResult interceptor binding retrieval failed), please create a GitHub issue in the Quarkus repository to help the maintainers fix this bug";

    @AroundInvoke
    public Object intercept(InvocationContext invocationContext) throws Throwable {
        CacheInterceptionContext<CacheResult> interceptionContext = getInterceptionContext(invocationContext,
                CacheResult.class, true);

        if (interceptionContext.getInterceptorBindings().isEmpty()) {
            // This should never happen.
            LOGGER.warn(INTERCEPTOR_BINDING_ERROR_MSG);
            return invocationContext.proceed();
        }

        CacheResult binding = interceptionContext.getInterceptorBindings().get(0);
        AbstractCache cache = (AbstractCache) cacheManager.getCache(binding.cacheName()).get();
        Object key = getCacheKey(cache, interceptionContext.getCacheKeyParameterPositions(), invocationContext.getParameters());
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debugf("Loading entry with key [%s] from cache [%s]", key, binding.cacheName());
        }

        try {
            final boolean isUni = Uni.class.isAssignableFrom(invocationContext.getMethod().getReturnType());
            if (isUni) {
                Uni<Object> ret = cache.get(key, new Function<Object, Object>() {
                    @Override
                    public Object apply(Object k) {
                        LOGGER.debugf("Adding %s entry with key [%s] into cache [%s]",
                                UnresolvedUniValue.class.getSimpleName(), key, binding.cacheName());
                        return UnresolvedUniValue.INSTANCE;
                    }
                }).onItem().transformToUni(o -> {
                    if (o == UnresolvedUniValue.INSTANCE) {
                        try {
                            return ((Uni<Object>) invocationContext.proceed())
                                    .onItem().call(emittedValue -> cache.replaceUniValue(key, emittedValue));
                        } catch (CacheException e) {
                            throw e;
                        } catch (Exception e) {
                            throw new CacheException(e);
                        }
                    } else {
                        return Uni.createFrom().item(o);
                    }
                });
                if (binding.lockTimeout() <= 0) {
                    return ret;
                }
                return ret.ifNoItem().after(Duration.ofMillis(binding.lockTimeout())).recoverWithUni(() -> {
                    try {
                        return (Uni<?>) invocationContext.proceed();
                    } catch (CacheException e) {
                        throw e;
                    } catch (Exception e) {
                        throw new CacheException(e);
                    }
                });

            } else {
                Uni<Object> cacheValue = cache.get(key, new Function<Object, Object>() {
                    @Override
                    public Object apply(Object k) {
                        try {
                            return invocationContext.proceed();
                        } catch (CacheException e) {
                            throw e;
                        } catch (Throwable e) {
                            throw new CacheException(e);
                        }
                    }
                });
                Object value;
                if (binding.lockTimeout() <= 0) {
                    value = cacheValue.await().indefinitely();
                } else {
                    try {
                        /*
                         * If the current thread started the cache value computation, then the computation is already finished
                         * since
                         * it was done synchronously and the following call will never time out.
                         */
                        value = cacheValue.await().atMost(Duration.ofMillis(binding.lockTimeout()));
                    } catch (TimeoutException e) {
                        // TODO: Add statistics here to monitor the timeout.
                        return invocationContext.proceed();
                    }
                }
                return value;
            }

        } catch (CacheException e) {
            if (e.getCause() != null) {
                throw e.getCause();
            } else {
                throw e;
            }
        }
    }
}
