<script setup lang="ts">
/**
 * 应用外壳（对照上游 EntryClassUi 的左侧 System 导航）：左侧竖排菜单 + 顶部标题 + 内容区。
 *
 * 菜单只有本项目已有的两个功能页，名字沿用上游导航条目标签：
 * - Wire Graph（上游 `msgs.wires`，图标 SITEMAP）
 * - Drivers and Assets（上游 `msgs.driversAndAssetsServices`，图标 CUBE）
 *
 * 切菜单前若页面有未保存改动，由页面自己通过 `beforeRouteLeave` 拦截（与上游 confirmIfUiDirty 等价），
 * 外壳只负责布局与当前项高亮。
 */
import { computed, ref } from 'vue'
import { useRoute } from 'vue-router'
import { useI18n } from 'vue-i18n'
import UpgradeDialog from '@/components/system/UpgradeDialog.vue'

const route = useRoute()
const { t } = useI18n()

/** 升级弹窗开关（标题栏右侧「升级」按钮，RFC 006/007 的后端 jar 热更新入口） */
const upgradeOpen = ref(false)

/** 当前高亮的菜单路径（子路由归到所属一级菜单） */
const activePath = computed(() =>
  route.path.startsWith('/drivers-assets') ? '/drivers-assets' : '/wires',
)

/** 顶部标题：当前一级菜单的名字（对照上游 setHeader(当前项标签)） */
const activeTitle = computed(() =>
  activePath.value === '/drivers-assets' ? t('app.menu.driversAssets') : t('app.menu.wireGraph'),
)
</script>

<template>
  <div class="app-shell">
    <aside class="app-shell__sidenav">
      <div class="app-shell__brand">
        <span class="app-shell__brand-text">{{ $t('app.brand') }}</span>
        <el-button
          class="app-shell__upgrade"
          text
          type="primary"
          size="small"
          :title="$t('app.upgrade.buttonHint')"
          @click="upgradeOpen = true"
        >
          {{ $t('app.upgrade.button') }}
        </el-button>
      </div>
      <el-menu :default-active="activePath" router class="app-shell__menu">
        <el-menu-item index="/wires">
          <span class="app-shell__icon" aria-hidden="true">
            <svg viewBox="0 0 16 16" width="14" height="14">
              <rect x="6" y="1" width="4" height="4" rx="1" fill="currentColor" />
              <rect x="1" y="11" width="4" height="4" rx="1" fill="currentColor" />
              <rect x="11" y="11" width="4" height="4" rx="1" fill="currentColor" />
              <path d="M8 5v3M3 11V9h10v2" stroke="currentColor" stroke-width="1.2" fill="none" />
            </svg>
          </span>
          <span>{{ $t('app.menu.wireGraph') }}</span>
        </el-menu-item>
        <el-menu-item index="/drivers-assets">
          <span class="app-shell__icon" aria-hidden="true">
            <svg viewBox="0 0 16 16" width="14" height="14">
              <path
                d="M8 1.5 14 4.5v7L8 14.5 2 11.5v-7z"
                stroke="currentColor"
                stroke-width="1.2"
                fill="none"
              />
              <path
                d="M2 4.5 8 7.5l6-3M8 7.5v7"
                stroke="currentColor"
                stroke-width="1.1"
                fill="none"
              />
            </svg>
          </span>
          <span>{{ $t('app.menu.driversAssets') }}</span>
        </el-menu-item>
      </el-menu>
    </aside>
    <div class="app-shell__main">
      <header class="app-shell__header">{{ activeTitle }}</header>
      <section class="app-shell__content">
        <router-view />
      </section>
    </div>
    <UpgradeDialog v-model="upgradeOpen" />
  </div>
</template>

<style scoped>
.app-shell {
  display: flex;
  height: 100vh;
  overflow: hidden;
  background: #f5f7fa;
}

.app-shell__sidenav {
  width: 200px;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  background: #fff;
  border-right: 1px solid #dcdfe6;
}

.app-shell__brand {
  height: 48px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 0 8px 0 16px;
  font-size: 15px;
  font-weight: 600;
  color: #303133;
  border-bottom: 1px solid #ebeef5;
}

.app-shell__brand-text {
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}

/* 升级按钮贴在品牌名右侧：设备运维最主要的动作，放顶部比塞进菜单更好找 */
.app-shell__upgrade {
  flex-shrink: 0;
  font-weight: 500;
  padding: 4px 6px;
}

.app-shell__menu {
  border-right: none;
  padding-top: 8px;
}

.app-shell__icon {
  display: inline-flex;
  align-items: center;
  margin-right: 8px;
  color: #606266;
}

.app-shell__main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.app-shell__header {
  height: 40px;
  flex-shrink: 0;
  display: flex;
  align-items: center;
  padding: 0 16px;
  font-size: 14px;
  font-weight: 600;
  color: #303133;
  background: #fff;
  border-bottom: 1px solid #dcdfe6;
}

.app-shell__content {
  flex: 1;
  min-height: 0;
  overflow: hidden;
}
</style>
