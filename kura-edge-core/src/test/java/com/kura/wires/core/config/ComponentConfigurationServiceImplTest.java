/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.core.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.kura.wires.exception.ErrorCode;
import com.kura.wires.exception.WiresException;
import com.kura.wires.model.ComponentConfiguration;

/**
 * {@link ComponentConfigurationServiceImpl} 的行为测试：以 Mockito 模拟
 * {@link ComponentConfigurationCrudRepository}，覆盖工厂配置增删改查的正反路径。
 */
public class ComponentConfigurationServiceImplTest {

    private static final String PID = "timer_1";
    private static final String FACTORY_PID = "org.eclipse.kura.wire.Timer";

    private ComponentConfigurationCrudRepository repository;
    private ComponentConfigurationServiceImpl service;

    @BeforeEach
    public void setUp() {
        this.repository = mock(ComponentConfigurationCrudRepository.class);
        this.service = new ComponentConfigurationServiceImpl(this.repository);
    }

    @Test
    public void successfulCreateFactoryConfiguration() {
        when(this.repository.existsById(PID)).thenReturn(false);

        this.service.createFactoryConfiguration(FACTORY_PID, PID, Map.of("period", 10));

        final ArgumentCaptor<ComponentConfigurationEntity> captor = ArgumentCaptor
                .forClass(ComponentConfigurationEntity.class);
        verify(this.repository).save(captor.capture());
        assertThat(captor.getValue().getPid()).isEqualTo(PID);
        assertThat(captor.getValue().getFactoryPid()).isEqualTo(FACTORY_PID);
        assertThat(captor.getValue().getPropertiesJson()).contains("\"period\"").contains("10");
    }

    @Test
    public void rejectCreateWhenConfigurationAlreadyExists() {
        when(this.repository.existsById(PID)).thenReturn(true);

        assertThatThrownBy(() -> this.service.createFactoryConfiguration(FACTORY_PID, PID, Map.of()))
                .isInstanceOf(WiresException.class)
                .extracting(e -> ((WiresException) e).getErrorCode()).isEqualTo(ErrorCode.CONFIG_INVALID);
        verify(this.repository, never()).save(any());
    }

    @Test
    public void rejectCreateWhenPropertiesAreNotSerializable() {
        when(this.repository.existsById(PID)).thenReturn(false);

        assertThatThrownBy(() -> this.service.createFactoryConfiguration(FACTORY_PID, PID, Map.of("bad", new Object())))
                .isInstanceOf(WiresException.class)
                .extracting(e -> ((WiresException) e).getErrorCode()).isEqualTo(ErrorCode.PERSISTENCE_ERROR);
    }

    @Test
    public void successfulUpdateConfiguration() {
        final ComponentConfigurationEntity entity = new ComponentConfigurationEntity(PID, FACTORY_PID, "{}");
        when(this.repository.findById(PID)).thenReturn(Optional.of(entity));

        this.service.updateConfiguration(PID, Map.of("period", 20));

        verify(this.repository).save(entity);
        assertThat(entity.getPropertiesJson()).contains("20");
    }

    @Test
    public void rejectUpdateWhenConfigurationIsMissing() {
        when(this.repository.findById(PID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> this.service.updateConfiguration(PID, Map.of())).isInstanceOf(WiresException.class)
                .extracting(e -> ((WiresException) e).getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    public void rejectUpdateWhenPropertiesAreNotSerializable() {
        final ComponentConfigurationEntity entity = new ComponentConfigurationEntity(PID, FACTORY_PID, "{}");
        when(this.repository.findById(PID)).thenReturn(Optional.of(entity));

        assertThatThrownBy(() -> this.service.updateConfiguration(PID, Map.of("bad", new Object())))
                .isInstanceOf(WiresException.class)
                .extracting(e -> ((WiresException) e).getErrorCode()).isEqualTo(ErrorCode.PERSISTENCE_ERROR);
    }

    @Test
    public void successfulDeleteFactoryConfiguration() {
        when(this.repository.existsById(PID)).thenReturn(true);

        this.service.deleteFactoryConfiguration(PID);

        verify(this.repository).deleteById(PID);
    }

    @Test
    public void rejectDeleteWhenConfigurationIsMissing() {
        when(this.repository.existsById(PID)).thenReturn(false);

        assertThatThrownBy(() -> this.service.deleteFactoryConfiguration(PID)).isInstanceOf(WiresException.class)
                .extracting(e -> ((WiresException) e).getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    public void successfulExistsDelegatesToRepository() {
        when(this.repository.existsById(PID)).thenReturn(true);

        assertThat(this.service.exists(PID)).isTrue();
    }

    @Test
    public void successfulGetConfigurationReturnsDomainObject() {
        when(this.repository.findById(PID)).thenReturn(Optional.of(new ComponentConfigurationEntity(PID, FACTORY_PID,
                "{\"period\":10}")));

        final Optional<ComponentConfiguration> configuration = this.service.getConfiguration(PID);

        assertThat(configuration).isPresent();
        assertThat(configuration.orElseThrow().getPid()).isEqualTo(PID);
        assertThat(configuration.orElseThrow().getDefinition()).isNull();
        assertThat(configuration.orElseThrow().getProperties()).containsEntry("period", 10);
    }

    @Test
    public void successfulGetConfigurationReturnsEmptyWhenMissing() {
        when(this.repository.findById(PID)).thenReturn(Optional.empty());

        assertThat(this.service.getConfiguration(PID)).isEmpty();
    }

    @Test
    public void successfulGetConfigurationsReturnsAll() {
        when(this.repository.findAll()).thenReturn(List.of(new ComponentConfigurationEntity(PID, FACTORY_PID, "{}"),
                new ComponentConfigurationEntity("logger_1", "logger", "{}")));

        assertThat(this.service.getConfigurations()).hasSize(2).extracting(ComponentConfiguration::getPid)
                .containsExactly(PID, "logger_1");
    }
}
