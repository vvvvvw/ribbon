package com.netflix.loadbalancer;

/*
*
* Copyright 2013 Netflix, Inc.
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
* http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*
*/

import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.netflix.client.IClientConfigAware;
import com.netflix.client.config.CommonClientConfigKey;
import com.netflix.client.config.IClientConfig;
import com.netflix.client.config.IClientConfigKey;
import com.netflix.client.config.Property;
import com.netflix.servo.monitor.Counter;
import com.netflix.servo.monitor.Monitors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * This server list filter deals with filtering out servers based on the Zone affinity. 
 * This filtering will be turned on if either {@link CommonClientConfigKey#EnableZoneAffinity} 
 * or {@link CommonClientConfigKey#EnableZoneExclusivity} is set to true in {@link IClientConfig} object
 * passed into this class during initialization. When turned on, servers outside the same zone (as 
 * indicated by {@link Server#getZone()}) will be filtered out. By default, zone affinity 
 * and exclusivity are turned off and nothing is filtered out.
 * 
 * @author stonse
 *
 */
//"区域感知"，会根据提供服务的实例所处的区域和消费者自身的所处区域进行比较，过滤掉那些不是同时处于一个区域的实例
public class ZoneAffinityServerListFilter<T extends Server> extends
        AbstractServerListFilter<T> implements IClientConfigAware {

    private static IClientConfigKey<String> ZONE = new CommonClientConfigKey<String>("@zone", "") {};
    private static IClientConfigKey<Double> MAX_LOAD_PER_SERVER = new CommonClientConfigKey<Double>("zoneAffinity.maxLoadPerServer", 0.6d) {};
    private static IClientConfigKey<Double> MAX_BLACKOUT_SERVER_PERCENTAGE = new CommonClientConfigKey<Double>("zoneAffinity.maxBlackOutServesrPercentage", 0.8d) {};
    private static IClientConfigKey<Integer> MIN_AVAILABLE_SERVERS = new CommonClientConfigKey<Integer>("zoneAffinity.minAvailableServers", 2) {};
    //如果区域不亲和，则不开启区域感知，否则则有可能区域感知
    private boolean zoneAffinity;
    ////如果区域排除，则一定开启区域感知，zoneExclusive>zoneAffinity
    private boolean zoneExclusive;
    private Property<Double> activeReqeustsPerServerThreshold;
    private Property<Double> blackOutServerPercentageThreshold;
    private Property<Integer> availableServersThreshold;
    private Counter overrideCounter;
    private ZoneAffinityPredicate zoneAffinityPredicate;

    private static Logger logger = LoggerFactory.getLogger(ZoneAffinityServerListFilter.class);
    
    private String zone;

    /**
     * @deprecated Must pass in a config via {@link ZoneAffinityServerListFilter#ZoneAffinityServerListFilter(IClientConfig)}
     */
    @Deprecated
    public ZoneAffinityServerListFilter() {

    }

    public ZoneAffinityServerListFilter(IClientConfig niwsClientConfig) {
        initWithNiwsConfig(niwsClientConfig);
    }

    @Override
    public void initWithNiwsConfig(IClientConfig niwsClientConfig) {
        zoneAffinity = niwsClientConfig.getOrDefault(CommonClientConfigKey.EnableZoneAffinity);
        zoneExclusive = niwsClientConfig.getOrDefault(CommonClientConfigKey.EnableZoneExclusivity);
        zone = niwsClientConfig.getGlobalProperty(ZONE).getOrDefault();
        zoneAffinityPredicate = new ZoneAffinityPredicate(zone);

        activeReqeustsPerServerThreshold = niwsClientConfig.getDynamicProperty(MAX_LOAD_PER_SERVER);
        blackOutServerPercentageThreshold = niwsClientConfig.getDynamicProperty(MAX_BLACKOUT_SERVER_PERCENTAGE);
        availableServersThreshold = niwsClientConfig.getDynamicProperty(MIN_AVAILABLE_SERVERS);

        overrideCounter = Monitors.newCounter("ZoneAffinity_OverrideCounter");

        Monitors.registerObject("NIWSServerListFilter_" + niwsClientConfig.getClientName());
    }

    // //是否开启区域感知
    private boolean shouldEnableZoneAffinity(List<T> filtered) {    
        if (!zoneAffinity && !zoneExclusive) {
            return false;
        }

        //如果区域排除，则开启区域感知
        if (zoneExclusive) {
            return true;
        }
        LoadBalancerStats stats = getLoadBalancerStats();
        if (stats == null) {
            return zoneAffinity;
        } else {
            logger.debug("Determining if zone affinity should be enabled with given server list: {}", filtered);
            //获取过滤后同区域实例的基础指标(包括实例数量、断路器断开数、活动请求数量、实例平均负载等等)，
            //根据一系列的算法求出如下的评价值并与设置的阈值进行比较，若有一个条件符合，则不启用“区域感知”过滤的服务实例清单
            // blackOutServerPercentage:故障百分比(断路器断开数/实例数量) >=0.8
            // activeRequestsPerServer:实例平均负载>=0.6
            //availableServers:可用实例数量(实例数量-断路器断开数) <2
            ZoneSnapshot snapshot = stats.getZoneSnapshot(filtered);
            double loadPerServer = snapshot.getLoadPerServer();
            int instanceCount = snapshot.getInstanceCount();            
            int circuitBreakerTrippedCount = snapshot.getCircuitTrippedCount();
            if (((double) circuitBreakerTrippedCount) / instanceCount >= blackOutServerPercentageThreshold.getOrDefault()
                    || loadPerServer >= activeReqeustsPerServerThreshold.getOrDefault()
                    || (instanceCount - circuitBreakerTrippedCount) < availableServersThreshold.getOrDefault()) {
                logger.debug("zoneAffinity is overriden. blackOutServerPercentage: {}, activeReqeustsPerServer: {}, availableServers: {}", 
                        new Object[] {(double) circuitBreakerTrippedCount / instanceCount,  loadPerServer, instanceCount - circuitBreakerTrippedCount});
                return false;
            } else {
                return true;
            }
            
        }
    }
        
    @Override
    public List<T> getFilteredListOfServers(List<T> servers) {
        if (zone != null && (zoneAffinity || zoneExclusive) && servers !=null && servers.size() > 0){
            List<T> filteredServers = Lists.newArrayList(Iterables.filter(
                    servers, this.zoneAffinityPredicate.getServerOnlyPredicate()));
            if (shouldEnableZoneAffinity(filteredServers)) {
                return filteredServers;
            } else if (zoneAffinity) {
                overrideCounter.increment();
            }
        }
        return servers;
    }

    @Override
    public String toString(){
        StringBuilder sb = new StringBuilder("ZoneAffinityServerListFilter:");
        sb.append(", zone: ").append(zone).append(", zoneAffinity:").append(zoneAffinity);
        sb.append(", zoneExclusivity:").append(zoneExclusive);
        return sb.toString();       
    }

}
