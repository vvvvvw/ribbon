package com.netflix.loadbalancer;

/**
 * strategy for {@link com.netflix.loadbalancer.DynamicServerListLoadBalancer} to use for different ways
 * of doing dynamic server list updates.
 *
 * @author David Liu
 */
//
public interface ServerListUpdater {

    /**
     * an interface for the updateAction that actually executes a server list update
     */
    public interface UpdateAction {
        void doUpdate();
    }


    /**
     * start the serverList updater with the given update action
     * This call should be idempotent.
     *
     * @param updateAction
     */
    //启动服务更新器，传入的UpdateAction对象为更新操作的具体实现
    void start(UpdateAction updateAction);

    /**
     * stop the serverList updater. This call should be idempotent
     */
    //停止服务更新器
    void stop();

    /**
     * @return the last update timestamp as a {@link java.util.Date} string
     */
    //获取最近的更新时间戳
    String getLastUpdate();

    /**
     * @return the number of ms that has elapsed since last update
     */
    //获取上一次更新到薪资的时间间隔，单位为毫秒
    long getDurationSinceLastUpdateMs();

    /**
     * @return the number of update cycles missed, if valid
     */
    //获取错误的更新周期数
    int getNumberMissedCycles();

    /**
     * @return the number of threads used, if vaid
     */
    //获取核心线程数
    int getCoreThreads();
}
