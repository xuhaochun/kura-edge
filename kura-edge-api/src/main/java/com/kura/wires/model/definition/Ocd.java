/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（https://github.com/eclipse-kura/kura，EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.model.definition;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 组件配置描述对象（Object Coordinate Definition），对照上游 metatype
 * {@code org.eclipse.kura.configuration.metatype.OCD}。
 * <p>
 * REST {@code GET /metadata} 返回的组件定义即本结构（对照上游
 * {@code WireComponentDefinitionDTO}）。
 * </p>
 */
public class Ocd {

    private String id;
    private String name;
    private String description;
    private List<Ad> ads = new ArrayList<>();

    /** 创建空描述对象（供反序列化使用）。 */
    public Ocd() {
        // Jackson 反序列化用
    }

    /**
     * 创建描述对象。
     *
     * @param id 标识（通常为 factoryPid）
     * @param name 显示名
     * @param description 描述
     * @param ads 属性定义列表
     */
    public Ocd(final String id, final String name, final String description, final List<Ad> ads) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.ads = ads == null ? new ArrayList<>() : new ArrayList<>(ads);
    }

    public String getId() {
        return this.id;
    }

    public void setId(final String id) {
        this.id = id;
    }

    public String getName() {
        return this.name;
    }

    public void setName(final String name) {
        this.name = name;
    }

    public String getDescription() {
        return this.description;
    }

    public void setDescription(final String description) {
        this.description = description;
    }

    public List<Ad> getAds() {
        return Collections.unmodifiableList(this.ads);
    }

    public void setAds(final List<Ad> ads) {
        this.ads = ads == null ? new ArrayList<>() : new ArrayList<>(ads);
    }
}
