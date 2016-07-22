/*
 * Copyright 2015 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.vertx.core.dns.impl.fix;

import io.netty.channel.ChannelFactory;
import io.netty.channel.EventLoop;
import io.netty.channel.ReflectiveChannelFactory;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.InternetProtocolFamily;
import io.netty.resolver.HostsFileEntriesResolver;
import io.netty.resolver.dns.*;
import io.netty.util.internal.InternalThreadLocalMap;
import io.netty.util.internal.PlatformDependent;
import io.netty.util.internal.UnstableApi;
import io.vertx.core.impl.launcher.commands.ExecUtils;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.netty.util.internal.ObjectUtil.checkNotNull;
import static io.netty.util.internal.ObjectUtil.intValue;

/**
 * A {@link DnsNameResolver} builder.
 */
@UnstableApi
public final class DnsNameResolverBuilder {

  private static final Pattern NDOTS_OPTIONS_PATTERN = Pattern.compile("^[ \\t\\f]*options[ \\t\\f]+ndots:[ \\t\\f]*(\\d)+(?=$|\\s)", Pattern.MULTILINE);
  private static final List<String> DEFAULT_SEACH_DOMAINS;
  private static final int DEFAULT_NDOTS;

  public static int parseNdotsFromResolvConf(String s) {
    int ndots = -1;
    Matcher matcher = NDOTS_OPTIONS_PATTERN.matcher(s);
    while (matcher.find()) {
      ndots = Integer.parseInt(matcher.group(1));
    }
    return ndots;
  }

  static {
    ArrayList<String> searchDomains = new ArrayList<>();
    try {
      Class<?> configClass = Class.forName("sun.net.dns.ResolverConfiguration");
      Method open = configClass.getMethod("open");
      Method nameservers = configClass.getMethod("searchlist");
      Object instance = open.invoke(null);

      @SuppressWarnings("unchecked")
      List<String> list = (List<String>) nameservers.invoke(instance);
      searchDomains.addAll(list);
    } catch (Exception ignore) {
      // Failed to get the system name search domain list.
    }
    DEFAULT_SEACH_DOMAINS = Collections.unmodifiableList(searchDomains);
  }

  static {
    int ndots = 1;
    if (ExecUtils.isLinux()) {
      File f = new File("/etc/resolv.conf");
      if (f.exists() && f.isFile()) {
        try {
          String conf = new String(Files.readAllBytes(f.toPath()));
          ndots = parseNdotsFromResolvConf(conf);
        } catch (IOException ignore) {
        }
      }
    }
    DEFAULT_NDOTS = ndots;
  }

  private final EventLoop eventLoop;
  private ChannelFactory<? extends DatagramChannel> channelFactory;
  private DnsServerAddresses nameServerAddresses = DnsServerAddresses.defaultAddresses();
  private DnsCache resolveCache;
  private Integer minTtl;
  private Integer maxTtl;
  private Integer negativeTtl;
  private long queryTimeoutMillis = 5000;
  private InternetProtocolFamily[] resolvedAddressTypes = DnsNameResolver.DEFAULT_RESOLVE_ADDRESS_TYPES;
  private boolean recursionDesired = true;
  private int maxQueriesPerResolve = 16;
  private boolean traceEnabled;
  private int maxPayloadSize = 4096;
  private boolean optResourceEnabled = true;
  private HostsFileEntriesResolver hostsFileEntriesResolver = HostsFileEntriesResolver.DEFAULT;
  private List<String> searchDomains = DEFAULT_SEACH_DOMAINS;
  private int ndots = DEFAULT_NDOTS;

  /**
   * Creates a new builder.
   *
   * @param eventLoop the {@link EventLoop} the {@link EventLoop} which will perform the communication with the DNS
   * servers.
   */
  public DnsNameResolverBuilder(EventLoop eventLoop) {
    this.eventLoop = eventLoop;
  }

  /**
   * Convenience method added for Vert.x
   */
  public DnsNameResolverBuilder() {
    this.eventLoop = null;
  }

  /**
   * Sets the {@link ChannelFactory} that will create a {@link DatagramChannel}.
   *
   * @param channelFactory the {@link ChannelFactory}
   * @return {@code this}
   */
  public DnsNameResolverBuilder channelFactory(ChannelFactory<? extends DatagramChannel> channelFactory) {
    this.channelFactory = channelFactory;
    return this;
  }

  /**
   * Sets the {@link ChannelFactory} as a {@link ReflectiveChannelFactory} of this type.
   * Use as an alternative to {@link #channelFactory(ChannelFactory)}.
   *
   * @param channelType the type
   * @return {@code this}
   */
  public DnsNameResolverBuilder channelType(Class<? extends DatagramChannel> channelType) {
    return channelFactory(new ReflectiveChannelFactory<DatagramChannel>(channelType));
  }

  /**
   * Sets the addresses of the DNS server.
   *
   * @param nameServerAddresses the DNS server addresses
   * @return {@code this}
   */
  public DnsNameResolverBuilder nameServerAddresses(DnsServerAddresses nameServerAddresses) {
    this.nameServerAddresses = nameServerAddresses;
    return this;
  }

  /**
   * Sets the cache for resolution results.
   *
   * @param resolveCache the DNS resolution results cache
   * @return {@code this}
   */
  public DnsNameResolverBuilder resolveCache(DnsCache resolveCache) {
    this.resolveCache  = resolveCache;
    return this;
  }

  /**
   * Sets the minimum and maximum TTL of the cached DNS resource records (in seconds). If the TTL of the DNS
   * resource record returned by the DNS server is less than the minimum TTL or greater than the maximum TTL,
   * this resolver will ignore the TTL from the DNS server and use the minimum TTL or the maximum TTL instead
   * respectively.
   * The default value is {@code 0} and {@link Integer#MAX_VALUE}, which practically tells this resolver to
   * respect the TTL from the DNS server.
   *
   * @param minTtl the minimum TTL
   * @param maxTtl the maximum TTL
   * @return {@code this}
   */
  public DnsNameResolverBuilder ttl(int minTtl, int maxTtl) {
    this.maxTtl = maxTtl;
    this.minTtl = minTtl;
    return this;
  }

  /**
   * Sets the TTL of the cache for the failed DNS queries (in seconds).
   *
   * @param negativeTtl the TTL for failed cached queries
   * @return {@code this}
   */
  public DnsNameResolverBuilder negativeTtl(int negativeTtl) {
    this.negativeTtl = negativeTtl;
    return this;
  }

  /**
   * Sets the timeout of each DNS query performed by this resolver (in milliseconds).
   *
   * @param queryTimeoutMillis the query timeout
   * @return {@code this}
   */
  public DnsNameResolverBuilder queryTimeoutMillis(long queryTimeoutMillis) {
    this.queryTimeoutMillis = queryTimeoutMillis;
    return this;
  }

  /**
   * Sets the list of the protocol families of the address resolved.
   * Usually, both {@link InternetProtocolFamily#IPv4} and {@link InternetProtocolFamily#IPv6} are specified in
   * the order of preference.  To enforce the resolve to retrieve the address of a specific protocol family,
   * specify only a single {@link InternetProtocolFamily}.
   *
   * @param resolvedAddressTypes the address types
   * @return {@code this}
   */
  public DnsNameResolverBuilder resolvedAddressTypes(InternetProtocolFamily... resolvedAddressTypes) {
    checkNotNull(resolvedAddressTypes, "resolvedAddressTypes");

    final List<InternetProtocolFamily> list =
        InternalThreadLocalMap.get().arrayList(InternetProtocolFamily.values().length);

    for (InternetProtocolFamily f : resolvedAddressTypes) {
      if (f == null) {
        break;
      }

      // Avoid duplicate entries.
      if (list.contains(f)) {
        continue;
      }

      list.add(f);
    }

    if (list.isEmpty()) {
      throw new IllegalArgumentException("no protocol family specified");
    }

    this.resolvedAddressTypes = list.toArray(new InternetProtocolFamily[list.size()]);

    return this;
  }

  /**
   * Sets the list of the protocol families of the address resolved.
   * Usually, both {@link InternetProtocolFamily#IPv4} and {@link InternetProtocolFamily#IPv6} are specified in
   * the order of preference.  To enforce the resolve to retrieve the address of a specific protocol family,
   * specify only a single {@link InternetProtocolFamily}.
   *
   * @param resolvedAddressTypes the address types
   * @return {@code this}
   */
  public DnsNameResolverBuilder resolvedAddressTypes(Iterable<InternetProtocolFamily> resolvedAddressTypes) {
    checkNotNull(resolvedAddressTypes, "resolveAddressTypes");

    final List<InternetProtocolFamily> list =
        InternalThreadLocalMap.get().arrayList(InternetProtocolFamily.values().length);

    for (InternetProtocolFamily f : resolvedAddressTypes) {
      if (f == null) {
        break;
      }

      // Avoid duplicate entries.
      if (list.contains(f)) {
        continue;
      }

      list.add(f);
    }

    if (list.isEmpty()) {
      throw new IllegalArgumentException("no protocol family specified");
    }

    this.resolvedAddressTypes = list.toArray(new InternetProtocolFamily[list.size()]);

    return this;
  }

  /**
   * Sets if this resolver has to send a DNS query with the RD (recursion desired) flag set.
   *
   * @param recursionDesired true if recursion is desired
   * @return {@code this}
   */
  public DnsNameResolverBuilder recursionDesired(boolean recursionDesired) {
    this.recursionDesired = recursionDesired;
    return this;
  }

  /**
   * Sets the maximum allowed number of DNS queries to send when resolving a host name.
   *
   * @param maxQueriesPerResolve the max number of queries
   * @return {@code this}
   */
  public DnsNameResolverBuilder maxQueriesPerResolve(int maxQueriesPerResolve) {
    this.maxQueriesPerResolve = maxQueriesPerResolve;
    return this;
  }

  /**
   * Sets if this resolver should generate the detailed trace information in an exception message so that
   * it is easier to understand the cause of resolution failure.
   *
   * @param traceEnabled true if trace is enabled
   * @return {@code this}
   */
  public DnsNameResolverBuilder traceEnabled(boolean traceEnabled) {
    this.traceEnabled = traceEnabled;
    return this;
  }

  /**
   * Sets the capacity of the datagram packet buffer (in bytes).  The default value is {@code 4096} bytes.
   *
   * @param maxPayloadSize the capacity of the datagram packet buffer
   * @return {@code this}
   */
  public DnsNameResolverBuilder maxPayloadSize(int maxPayloadSize) {
    this.maxPayloadSize = maxPayloadSize;
    return this;
  }

  /**
   * Enable the automatic inclusion of a optional records that tries to give the remote DNS server a hint about
   * how much data the resolver can read per response. Some DNSServer may not support this and so fail to answer
   * queries. If you find problems you may want to disable this.
   *
   * @param optResourceEnabled if optional records inclusion is enabled
   * @return {@code this}
   */
  public DnsNameResolverBuilder optResourceEnabled(boolean optResourceEnabled) {
    this.optResourceEnabled = optResourceEnabled;
    return this;
  }

  /**
   * @param hostsFileEntriesResolver the {@link HostsFileEntriesResolver} used to first check
   *                                 if the hostname is locally aliased.
   * @return {@code this}
   */
  public DnsNameResolverBuilder hostsFileEntriesResolver(HostsFileEntriesResolver hostsFileEntriesResolver) {
    this.hostsFileEntriesResolver = hostsFileEntriesResolver;
    return this;
  }

  public List<String> searchDomains() {
    return searchDomains;
  }

  public DnsNameResolverBuilder searchDomains(List<String> searchDomains) {
    this.searchDomains = searchDomains;
    return this;
  }

  public int ndots() {
    return ndots;
  }

  public DnsNameResolverBuilder ndots(int ndots) {
    this.ndots = ndots;
    return this;
  }

  /**
   * Returns a new {@link DnsNameResolver} instance.
   *
   * @return a {@link DnsNameResolver}
   */
  public DnsNameResolver build() {
    return build(eventLoop);
  }

  /**
   * Convenience method added for Vert.x
   *
   * @return a {@link DnsNameResolver}
   */
  public DnsNameResolver build(EventLoop eventLoop) {

    if (resolveCache != null && (minTtl != null || maxTtl != null || negativeTtl != null)) {
      throw new IllegalStateException("resolveCache and TTLs are mutually exclusive");
    }

    DnsCache cache = resolveCache != null ? resolveCache :
        new DefaultDnsCache(intValue(minTtl, 0), intValue(maxTtl, Integer.MAX_VALUE), intValue(negativeTtl, 0));

    return new DnsNameResolver(
        eventLoop,
        channelFactory,
        nameServerAddresses,
        cache,
        queryTimeoutMillis,
        resolvedAddressTypes,
        recursionDesired,
        maxQueriesPerResolve,
        traceEnabled,
        maxPayloadSize,
        optResourceEnabled,
        hostsFileEntriesResolver,
        searchDomains,
        ndots);
  }
}
