<script setup lang="ts">
/**
 * 配置区（对照上游 ConfigurationAreaUi + ValidationData/ValidationInputCell）：
 * 选中组件 → 表单状态交给 componentConfig store（字段值 / 校验结果 / 应用回写），
 * 字段控件交给 ValidationInput 按 metatype 渲染（必填、数值范围、选项、PASSWORD 脱敏）；
 * 校验失败标红并阻止应用。
 */
import { computed, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import LoggerLogPanel from './LoggerLogPanel.vue'
import ValidationInput from './ValidationInput.vue'
import WireAssetConfiguration from './WireAssetConfiguration.vue'
import { useComponentConfigStore } from '@/stores/componentConfig'
import { useWiresGraphStore } from '@/stores/wiresGraph'
import { useMetadataText } from '@/composables/useMetadataText'
import { WIRE_ASSET_FACTORY_PID } from '@/types/driversAssets'
import type { ConfigParameter } from '@/types/wires'

/** Logger 组件工厂 PID（与后端 LoggerFactory.FACTORY_PID 一致）：仅它显示运行日志详情（RFC 007） */
const LOGGER_FACTORY_PID = 'org.eclipse.kura.wire.Logger'

const graph = useWiresGraphStore()
const config = useComponentConfigStore()
const { t } = useI18n()
const { propertyLabel, propertyDescription } = useMetadataText()

/** 字段所属组件的 factoryPid：字段文案按组件查 i18n 表 */
const factoryPid = computed(() => config.component?.factoryPid ?? '')

/** 资产使用专用配置区（通道表 + Driver 页签），普通组件仍走扁平 OCD 表单 */
const isAsset = computed(() => factoryPid.value === WIRE_ASSET_FACTORY_PID)

/** 日志记录器：表单下方追加「日志详情」实时面板（RFC 007） */
const isLogger = computed(() => factoryPid.value === LOGGER_FACTORY_PID)

/** 字段标签：中文名，未登记翻译时回落后端 metatype 名 */
function paramLabel(param: ConfigParameter): string {
  return propertyLabel(param, factoryPid.value)
}

/** 字段说明（label 的 tooltip）：中文说明，未登记时回落后端英文说明 */
function paramDescription(param: ConfigParameter): string {
  return propertyDescription(param, factoryPid.value)
}

// 选中项变化时重填表单（对照上游 ConfigurationAreaUi）
watch(
  () => graph.selectedPid,
  (pid) => config.begin(pid),
  { immediate: true },
)

/** 字段错误文案（校验通过返回空串，交给 el-form-item 的 error 插槽） */
function fieldError(paramId: string): string {
  const result = config.results[paramId]
  if (!result || result.valid) {
    return ''
  }
  return t(result.messageKey ?? '', result.params ?? {})
}

function apply(): void {
  if (config.apply()) {
    ElMessage.success(t('wires.config.applySuccess'))
    return
  }
  ElMessage.warning(t('wires.config.invalid'))
}
</script>

<template>
  <div class="config-area">
    <div class="config-area__title">{{ $t('wires.config.title') }}</div>
    <el-empty v-if="!config.component" :description="$t('wires.config.none')" :image-size="60" />
    <template v-else>
      <WireAssetConfiguration v-if="isAsset" :pid="config.component.pid" />
      <template v-else>
        <div class="config-area__pid">{{ config.component.pid }}</div>
        <p v-if="config.parameters.length === 0" class="config-area__no-fields">
          {{ $t('wires.config.noFields') }}
        </p>
        <el-form v-else label-position="top" size="small" @submit.prevent>
          <el-form-item
            v-for="param in config.parameters"
            :key="param.id"
            :required="param.required"
            :error="fieldError(param.id)"
          >
            <template #label>
              <span class="config-area__param" :title="paramDescription(param)">
                <span class="config-area__param-name">{{ paramLabel(param) }}</span>
                <!-- 中文名与原 metatype 属性 id 不一时并列展示 id：配置项与上游文档仍需能对上 -->
                <span v-if="paramLabel(param) !== param.id" class="config-area__param-id">
                  {{ param.id }}
                </span>
              </span>
            </template>
            <ValidationInput
              :param="param"
              :model-value="config.values[param.id] ?? ''"
              :invalid="config.results[param.id]?.valid === false"
              @update:model-value="config.setValue(param.id, $event)"
            />
          </el-form-item>
          <el-button type="primary" size="small" @click="apply">
            {{ $t('wires.config.apply') }}
          </el-button>
        </el-form>
      </template>
      <!-- 日志记录器专属：配置下方实时 tail -f 该组件收到的记录（RFC 007） -->
      <LoggerLogPanel v-if="isLogger" :pid="config.component.pid" />
    </template>
  </div>
</template>

<style scoped>
.config-area {
  height: 100%;
  overflow-y: auto;
  padding: 8px 12px;
}

.config-area__title {
  font-size: 14px;
  font-weight: 600;
  color: #303133;
}

.config-area__pid {
  font-size: 12px;
  color: #909399;
  margin: 4px 0 8px;
  word-break: break-all;
}

.config-area__no-fields {
  font-size: 12px;
  color: #909399;
}

.config-area__param {
  display: inline-flex;
  align-items: baseline;
  gap: 6px;
}

.config-area__param-id {
  font-size: 11px;
  font-weight: 400;
  color: #909399;
  word-break: break-all;
}
</style>
