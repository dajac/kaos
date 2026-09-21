/**
 * The network proxy that sits between the simulated consumers and the cluster, and the
 * per-client network faults it applies.
 *
 * <p>Nothing here is static: a {@link io.github.dajac.kaos.internal.proxy.NetworkProxy} and its
 * {@link io.github.dajac.kaos.internal.proxy.NetworkFaults} are plain instances, so several
 * proxies coexist in one JVM.
 */
package io.github.dajac.kaos.internal.proxy;
