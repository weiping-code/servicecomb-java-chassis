/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.servicecomb.loadbalance.filter;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.apache.servicecomb.core.Invocation;
import org.apache.servicecomb.core.Transport;
import org.apache.servicecomb.loadbalance.Configuration;
import org.apache.servicecomb.loadbalance.ServiceCombLoadBalancerStats;
import org.apache.servicecomb.loadbalance.ServiceCombServer;
import org.apache.servicecomb.loadbalance.ServiceCombServerStats;
import org.apache.servicecomb.loadbalance.TestServiceCombServerStats;
import org.apache.servicecomb.registry.api.registry.MicroserviceInstance;
import org.apache.servicecomb.registry.cache.CacheEndpoint;
import org.apache.servicecomb.registry.discovery.DiscoveryContext;
import org.apache.servicecomb.registry.discovery.DiscoveryTreeNode;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.mockito.Mockito;

import mockit.Deencapsulation;
import mockit.Mocked;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class IsolationDiscoveryFilterTest {

  private DiscoveryContext discoveryContext;

  private DiscoveryTreeNode discoveryTreeNode;

  private Map<String, MicroserviceInstance> data;

  private IsolationDiscoveryFilter filter;

  @Mocked
  private Transport transport = Mockito.mock(Transport.class);

  private Invocation invocation = new Invocation() {
    @Override
    public String getMicroserviceName() {
      return "testMicroserviceName";
    }
  };

  @BeforeEach
  public void before() {
    discoveryContext = new DiscoveryContext();
    discoveryContext.setInputParameters(invocation);
    discoveryTreeNode = new DiscoveryTreeNode();
    Mockito.doAnswer(a -> a.getArguments()[0]).when(transport).parseAddress(Mockito.anyString());
    data = new HashMap<>();
    for (int i = 0; i < 3; ++i) {
      MicroserviceInstance instance = new MicroserviceInstance();
      instance.setInstanceId("i" + i);
      String endpoint = "rest://127.0.0.1:" + i;
      instance.setEndpoints(Collections.singletonList(endpoint));
      data.put(instance.getInstanceId(), instance);
      ServiceCombServer serviceCombServer = new ServiceCombServer(invocation.getMicroserviceName(), transport,
          new CacheEndpoint(endpoint, instance));
      ServiceCombLoadBalancerStats.INSTANCE.getServiceCombServerStats(serviceCombServer);
    }
    discoveryTreeNode.data(data);

    filter = new IsolationDiscoveryFilter();
    TestServiceCombServerStats.releaseTryingChance();
  }

  @AfterEach
  public void after() {
    Deencapsulation.invoke(ServiceCombLoadBalancerStats.INSTANCE, "init");
    TestServiceCombServerStats.releaseTryingChance();
  }

  @Test
  public void discoveryNoInstanceReachErrorThreshold() {
    DiscoveryTreeNode childNode = filter.discovery(discoveryContext, discoveryTreeNode);

    Map<String, MicroserviceInstance> childNodeData = childNode.data();
    MatcherAssert.assertThat(childNodeData.keySet(), Matchers.containsInAnyOrder("i0", "i1", "i2"));
    Assertions.assertEquals(data.get("i0"), childNodeData.get("i0"));
    Assertions.assertEquals(data.get("i1"), childNodeData.get("i1"));
    Assertions.assertEquals(data.get("i2"), childNodeData.get("i2"));
  }

  @Test
  public void discoveryIsolateErrorInstance() {
    ServiceCombServer server0 = ServiceCombLoadBalancerStats.INSTANCE.getServiceCombServer(data.get("i0"));
    for (int i = 0; i < 4; ++i) {
      ServiceCombLoadBalancerStats.INSTANCE.markFailure(server0);
    }
    DiscoveryTreeNode childNode = filter.discovery(discoveryContext, discoveryTreeNode);
    Map<String, MicroserviceInstance> childNodeData = childNode.data();
    MatcherAssert.assertThat(childNodeData.keySet(), Matchers.containsInAnyOrder("i0", "i1", "i2"));
    Assertions.assertEquals(data.get("i0"), childNodeData.get("i0"));
    Assertions.assertEquals(data.get("i1"), childNodeData.get("i1"));
    Assertions.assertEquals(data.get("i2"), childNodeData.get("i2"));

    // by default 5 times continuous failure will cause isolation
    ServiceCombLoadBalancerStats.INSTANCE.markFailure(server0);
    Assertions.assertFalse(ServiceCombLoadBalancerStats.INSTANCE.getServiceCombServerStats(server0).isIsolated());

    childNode = filter.discovery(discoveryContext, discoveryTreeNode);
    childNodeData = childNode.data();
    MatcherAssert.assertThat(childNodeData.keySet(), Matchers.containsInAnyOrder("i1", "i2"));
    Assertions.assertEquals(data.get("i1"), childNodeData.get("i1"));
    Assertions.assertEquals(data.get("i2"), childNodeData.get("i2"));
    Assertions.assertTrue(ServiceCombLoadBalancerStats.INSTANCE.getServiceCombServerStats(server0).isIsolated());
  }

  @Test
  public void discoveryTryIsolatedInstanceAfterSingleTestTime() {
    ServiceCombServer server0 = ServiceCombLoadBalancerStats.INSTANCE.getServiceCombServer(data.get("i0"));
    ServiceCombServerStats serviceCombServerStats = ServiceCombLoadBalancerStats.INSTANCE
        .getServiceCombServerStats(server0);
    for (int i = 0; i < 5; ++i) {
      serviceCombServerStats.markFailure();
    }
    letIsolatedInstancePassSingleTestTime(serviceCombServerStats);
    ServiceCombLoadBalancerStats.INSTANCE.markIsolated(server0, true);

    Assertions.assertTrue(ServiceCombServerStats.isolatedServerCanTry());
    Assertions.assertNull(TestServiceCombServerStats.getTryingIsolatedServerInvocation());
    DiscoveryTreeNode childNode = filter.discovery(discoveryContext, discoveryTreeNode);
    Map<String, MicroserviceInstance> childNodeData = childNode.data();
    MatcherAssert.assertThat(childNodeData.keySet(), Matchers.containsInAnyOrder("i0", "i1", "i2"));
    Assertions.assertEquals(data.get("i0"), childNodeData.get("i0"));
    Assertions.assertEquals(data.get("i1"), childNodeData.get("i1"));
    Assertions.assertEquals(data.get("i2"), childNodeData.get("i2"));
    Assertions.assertTrue(serviceCombServerStats.isIsolated());
    Assertions.assertFalse(ServiceCombServerStats.isolatedServerCanTry());
    Assertions.assertSame(invocation, TestServiceCombServerStats.getTryingIsolatedServerInvocation());
  }

  @Test
  public void discoveryNotTryIsolatedInstanceConcurrently() {
    ServiceCombServer server0 = ServiceCombLoadBalancerStats.INSTANCE.getServiceCombServer(data.get("i0"));
    ServiceCombServerStats serviceCombServerStats = ServiceCombLoadBalancerStats.INSTANCE
        .getServiceCombServerStats(server0);
    for (int i = 0; i < 5; ++i) {
      serviceCombServerStats.markFailure();
    }
    ServiceCombLoadBalancerStats.INSTANCE.markIsolated(server0, true);
    letIsolatedInstancePassSingleTestTime(serviceCombServerStats);

    Assertions.assertTrue(ServiceCombServerStats.isolatedServerCanTry());

    // The first invocation can occupy the trying chance
    DiscoveryTreeNode childNode = filter.discovery(discoveryContext, discoveryTreeNode);
    Map<String, MicroserviceInstance> childNodeData = childNode.data();
    MatcherAssert.assertThat(childNodeData.keySet(), Matchers.containsInAnyOrder("i0", "i1", "i2"));
    Assertions.assertEquals(data.get("i0"), childNodeData.get("i0"));
    Assertions.assertEquals(data.get("i1"), childNodeData.get("i1"));
    Assertions.assertEquals(data.get("i2"), childNodeData.get("i2"));
    Assertions.assertFalse(ServiceCombServerStats.isolatedServerCanTry());

    // Other invocation cannot get trying chance concurrently
    childNode = filter.discovery(discoveryContext, discoveryTreeNode);
    childNodeData = childNode.data();
    MatcherAssert.assertThat(childNodeData.keySet(), Matchers.containsInAnyOrder("i1", "i2"));
    Assertions.assertEquals(data.get("i1"), childNodeData.get("i1"));
    Assertions.assertEquals(data.get("i2"), childNodeData.get("i2"));

    ServiceCombServerStats
        .checkAndReleaseTryingChance(invocation); // after the first invocation releases the trying chance

    // Other invocation can get the trying chance
    childNode = filter.discovery(discoveryContext, discoveryTreeNode);
    childNodeData = childNode.data();
    MatcherAssert.assertThat(childNodeData.keySet(), Matchers.containsInAnyOrder("i0", "i1", "i2"));
    Assertions.assertEquals(data.get("i0"), childNodeData.get("i0"));
    Assertions.assertEquals(data.get("i1"), childNodeData.get("i1"));
    Assertions.assertEquals(data.get("i2"), childNodeData.get("i2"));
    Assertions.assertFalse(ServiceCombServerStats.isolatedServerCanTry());
  }

  private ServiceCombServerStats letIsolatedInstancePassSingleTestTime(ServiceCombServerStats serviceCombServerStats) {
    Deencapsulation.setField(serviceCombServerStats, "lastActiveTime",
        System.currentTimeMillis() - 1 - Configuration.INSTANCE.getSingleTestTime(invocation.getMicroserviceName()));
    Deencapsulation.setField(serviceCombServerStats, "lastVisitTime",
        System.currentTimeMillis() - 1 - Configuration.INSTANCE.getSingleTestTime(invocation.getMicroserviceName()));
    return serviceCombServerStats;
  }

  @Test
  public void discoveryKeepMinIsolationTime() {
    ServiceCombServer server0 = ServiceCombLoadBalancerStats.INSTANCE.getServiceCombServer(data.get("i0"));
    ServiceCombLoadBalancerStats.INSTANCE.markIsolated(server0, true);
    ServiceCombLoadBalancerStats.INSTANCE.markSuccess(server0);

    DiscoveryTreeNode childNode = filter.discovery(discoveryContext, discoveryTreeNode);
    Map<String, MicroserviceInstance> childNodeData = childNode.data();
    MatcherAssert.assertThat(childNodeData.keySet(), Matchers.containsInAnyOrder("i1", "i2"));
    Assertions.assertEquals(data.get("i1"), childNodeData.get("i1"));
    Assertions.assertEquals(data.get("i2"), childNodeData.get("i2"));

    ServiceCombServerStats serviceCombServerStats = ServiceCombLoadBalancerStats.INSTANCE
        .getServiceCombServerStats(server0);
    Deencapsulation.setField(serviceCombServerStats, "isolatedTime",
        System.currentTimeMillis() - Configuration.INSTANCE.getMinIsolationTime(invocation.getMicroserviceName()) - 1);
    childNode = filter.discovery(discoveryContext, discoveryTreeNode);
    childNodeData = childNode.data();
    MatcherAssert.assertThat(childNodeData.keySet(), Matchers.containsInAnyOrder("i0", "i1", "i2"));
    Assertions.assertEquals(data.get("i0"), childNodeData.get("i0"));
    Assertions.assertEquals(data.get("i1"), childNodeData.get("i1"));
    Assertions.assertEquals(data.get("i2"), childNodeData.get("i2"));
  }

  @Test
  public void discoveryRecoverInstance() {
    ServiceCombServer server0 = ServiceCombLoadBalancerStats.INSTANCE.getServiceCombServer(data.get("i0"));
    ServiceCombLoadBalancerStats.INSTANCE.markSuccess(server0);
    ServiceCombServerStats serviceCombServerStats = ServiceCombLoadBalancerStats.INSTANCE
        .getServiceCombServerStats(server0);

    ServiceCombLoadBalancerStats.INSTANCE.markIsolated(server0, true);
    Deencapsulation.setField(serviceCombServerStats, "isolatedTime",
        System.currentTimeMillis() - Configuration.INSTANCE.getMinIsolationTime(invocation.getMicroserviceName()) - 1);

    DiscoveryTreeNode childNode = filter.discovery(discoveryContext, discoveryTreeNode);
    Map<String, MicroserviceInstance> childNodeData = childNode.data();
    MatcherAssert.assertThat(childNodeData.keySet(), Matchers.containsInAnyOrder("i0", "i1", "i2"));
    Assertions.assertEquals(data.get("i0"), childNodeData.get("i0"));
    Assertions.assertEquals(data.get("i1"), childNodeData.get("i1"));
    Assertions.assertEquals(data.get("i2"), childNodeData.get("i2"));
    Assertions.assertFalse(ServiceCombLoadBalancerStats.INSTANCE.getServiceCombServerStats(server0).isIsolated());
  }
}
