/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（https://github.com/eclipse-kura/kura，EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.core.driver;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.kura.wires.model.definition.Ad;
import com.kura.wires.model.definition.Ocd;
import com.kura.wires.model.driver.Driver;

/**
 * 驱动服务：驱动工厂注册表 + 驱动实例生命周期 + 描述符查询。
 * 对照上游「Driver 服务注册 + {@code DriverComponentTrackerCustomizer}」的职责，
 * 但驱动实例由本项目自己创建与持有（不再依赖 OSGi 服务注册）。
 */
public interface DriverService {

    /**
     * 返回全部驱动工厂 PID。
     *
     * @return 工厂 PID 集合
     */
    Collection<String> getDriverFactoryPids();

    /**
     * 是否存在该驱动工厂。
     *
     * @param factoryPid 工厂 PID
     * @return 存在时为 true
     */
    boolean isDriverFactory(String factoryPid);

    /**
     * 返回驱动工厂的配置定义（管理界面表单数据源）。
     *
     * @param factoryPid 工厂 PID
     * @return 定义（工厂未注册时为空）
     */
    Optional<Ocd> getDefinition(String factoryPid);

    /**
     * 返回驱动工厂的通道参数定义。
     *
     * @param factoryPid 工厂 PID
     * @return 通道 AD 列表（工厂未注册时为空列表）
     */
    List<Ad> getChannelDescriptor(String factoryPid);

    /**
     * 返回运行中的驱动实例。
     *
     * @param pid 驱动实例 PID
     * @return 驱动实例
     */
    Optional<Driver> findDriver(String pid);

    /**
     * 返回驱动实例的工厂 PID。
     *
     * @param pid 驱动实例 PID
     * @return 工厂 PID（未创建时为空）
     */
    Optional<String> getFactoryPid(String pid);

    /**
     * 是否存在该驱动实例配置。
     *
     * @param pid 驱动实例 PID
     * @return 存在时为 true
     */
    boolean exists(String pid);

    /**
     * 返回全部驱动实例 PID（配置存在即算，包含尚未创建实例的）。
     *
     * @return 实例 PID 列表
     */
    List<String> getDriverPids();

    /**
     * 创建驱动实例。
     *
     * @param factoryPid 工厂 PID
     * @param pid 实例 PID
     * @param properties 配置属性（可为 null，缺省必填项会用默认值补齐）
     */
    void createDriver(String factoryPid, String pid, Map<String, Object> properties);

    /**
     * 更新驱动配置并重建实例。
     *
     * @param pid 实例 PID
     * @param properties 新的完整配置属性
     */
    void updateDriver(String pid, Map<String, Object> properties);

    /**
     * 删除驱动配置与实例。
     *
     * @param pid 实例 PID
     */
    void deleteDriver(String pid);

    /**
     * 按数据库里既有的配置重建全部驱动实例（启动时调用）。
     */
    void reloadAll();
}
