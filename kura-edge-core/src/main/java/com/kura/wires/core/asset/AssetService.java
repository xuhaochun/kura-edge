/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（https://github.com/eclipse-kura/kura，EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.core.asset;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.kura.wires.model.asset.Asset;
import com.kura.wires.model.asset.AssetConfiguration;
import com.kura.wires.model.definition.Ad;
import com.kura.wires.model.definition.Ocd;

/**
 * 资产服务：资产实例（= {@code WireAsset} 组件）的生命周期、通道描述符与运行期访问。
 * <p>
 * 与驱动服务对称：实例由配置驱动（{@code SelfConfiguringComponent} 语义），配置落库，
 * 实例挂在组件注册表里，因此线图引用同一个 {@code pid} 时复用同一实例。
 * </p>
 */
public interface AssetService {

    /**
     * 返回全部资产实例 PID。
     *
     * @return PID 列表
     */
    List<String> getAssetPids();

    /**
     * 是否存在该资产配置。
     *
     * @param pid 资产实例 PID
     * @return 存在时为 true
     */
    boolean exists(String pid);

    /**
     * 返回运行中的资产实例（供 REST 读写通道值）。
     *
     * @param pid 资产实例 PID
     * @return 资产实例
     */
    Optional<Asset> findAsset(String pid);

    /**
     * 返回资产的配置属性（含 {@code driver.pid} 与各通道参数）。
     *
     * @param pid 资产实例 PID
     * @return 属性表
     */
    Optional<Map<String, Object>> getAssetProperties(String pid);

    /**
     * 返回解析后的资产配置（通道表 / 驱动 PID / 描述）。
     *
     * @param pid 资产实例 PID
     * @return 资产配置
     */
    Optional<AssetConfiguration> getAssetConfiguration(String pid);

    /**
     * 创建资产实例。
     *
     * @param pid 资产实例 PID
     * @param properties 配置属性（至少要含 {@code driver.pid}）
     */
    void createAsset(String pid, Map<String, Object> properties);

    /**
     * 更新资产配置并重建实例（对照上游「删掉旧工厂配置再建新的」语义，通道监听因此重新注册）。
     *
     * @param pid 资产实例 PID
     * @param properties 新的完整配置属性
     */
    void updateAsset(String pid, Map<String, Object> properties);

    /**
     * 删除资产配置与实例（调用方负责先做「是否被线图引用」的校验）。
     *
     * @param pid 资产实例 PID
     */
    void deleteAsset(String pid);

    /**
     * 返回资产的合并通道描述符（基座 AD 在前、驱动 AD 在后）。
     *
     * @param pid 资产实例 PID
     * @return 通道 AD 列表（资产不存在或驱动未知时只有基座部分）
     */
    List<Ad> getChannelDescriptor(String pid);

    /**
     * 返回基座通道描述符（与驱动无关的 {@code +} 参数，WireAsset 专用端点）。
     *
     * @return 基座 AD 列表
     */
    List<Ad> getBaseChannelDescriptor();

    /**
     * 返回资产组件的配置定义（{@code WireAsset} 的 OCD，含 {@code emit.*} 选项）。
     *
     * @return 定义（工厂未注册时为空）
     */
    Optional<Ocd> getAssetDefinition();

    /**
     * 按数据库里既有配置重建全部资产实例（启动时调用）。
     */
    void reloadAll();
}
