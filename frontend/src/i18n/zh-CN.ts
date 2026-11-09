export default {
  common: {
    yes: '是',
    no: '否',
    cancel: '取消',
    unknown: '未知',
  },
  app: {
    brand: 'Kura Edge',
    menu: {
      wireGraph: 'Wire Graph',
      driversAssets: 'Drivers and Assets',
    },
    title: 'Wires 图编辑器',
  },
  wires: {
    toolbar: {
      save: '保存',
      reload: '重新加载',
      importJson: '导入 JSON',
      reset: '重置',
      download: '导出快照',
      zoomIn: '放大',
      zoomOut: '缩小',
      zoomFit: '适应画布',
      deleteComponent: '删除组件',
      deleteGraph: '删除整图',
    },
    palette: {
      title: '组件面板',
      emitters: '发射器',
      receivers: '接收器',
      both: '发射器 / 接收器',
      hint: '拖拽到画布或点击新建',
      empty: '暂无组件定义',
      assets: '资产',
    },
    config: {
      title: '组件配置',
      none: '选中一个组件以编辑其配置',
      apply: '应用配置',
      invalid: '存在非法字段，无法应用',
      applyBlockedSave: '组件配置存在非法字段，已取消保存',
      applySuccess: '配置已应用',
      noFields: '该组件没有可配置属性',
      assetTab: '资产配置',
      driverTab: 'Driver - {pid}',
      driverSelect: 'Driver',
      driverNone: '未绑定 Driver',
      driverRequired: '请先选择一个 Driver',
      channelsInvalid: '通道表存在非法项，无法应用',
      assetLoadFailed: '资产配置加载失败：{msg}',
      driverLoadFailed: 'Driver 配置加载失败：{msg}',
      driverApplySuccess: 'Driver 配置已应用',
    },
    logTail: {
      title: '日志详情',
      empty: '暂无日志，等待组件收到数据…',
      clear: '清空',
      hint: '仅保留最近 {max} 行',
      state: {
        live: '实时',
        reconnecting: '重连中',
        idle: '未连接',
        stopped: '已停止',
      },
    },
    /**
     * 组件目录文案：后端 GET /metadata 只返回英文 metatype 文案（name / description / options），
     * 中文在本地按「factoryPid 末段 + 属性 id + 选项英文标签」查表（见 composables/useMetadataText.ts）。
     *
     * 属性 id 内含点号（如 simple.interval），故属性表整体取出后按键直取，不经 vue-i18n 的点号路径解析；
     * 未登记的组件/属性/选项一律回落后端原文，异构快照里的未知组件仍可显示与配置。
     */
    metadata: {
      components: {
        Timer: {
          name: '定时器',
          properties: {
            type: {
              name: '间隔类型',
              description: '间隔的类型：简单间隔或 CRON 表达式。',
            },
            'simple.interval': {
              name: '间隔时长',
              description: '定时器两次触发之间的间隔，单位由时间单位属性指定。',
            },
            'simple.time.unit': {
              name: '时间单位',
              description: '选择简单间隔类型时使用的时间单位。',
            },
            'simple.first.tick.policy': {
              name: '首次触发策略',
              description: '定时器启动后首次触发的行为。',
            },
            'simple.custom.first.tick.interval': {
              name: '首次触发延迟',
              description: '首次触发前的自定义时长（仅在自定义策略下生效）。',
            },
            'cron.interval': {
              name: 'CRON 表达式',
              description: 'CRON 表达式形式的间隔（选择 CRON 类型时必须设置）。',
            },
          },
        },
        Logger: {
          name: '日志记录器',
          properties: {
            'log.verbosity': {
              name: '日志详细程度',
              description: '记录收到的 Wire 信封时使用的日志详细程度。',
            },
          },
        },
        RegexFilter: {
          name: '正则过滤器',
          properties: {
            'regex.filter': {
              name: '正则表达式',
              description: '用于匹配的正则表达式（可在 http://regexr.com 上编写并测试）。',
            },
            'filter.type': {
              name: '过滤类型',
              description: '过滤类型：保留匹配的条目，或移除匹配的条目。',
            },
          },
        },
        DbWireRecordStore: {
          name: '数据库记录存储',
          properties: {
            'maximum.table.size': {
              name: '表最大记录数',
              description: '数据表中保留的最大记录数。',
            },
            'cleanup.records.keep': {
              name: '清理保留记录数',
              description: '执行清理操作时保留的记录数（设为 0 则删除全部记录）。',
            },
          },
        },
        Fifo: {
          name: 'FIFO 队列',
          properties: {
            'queue.capacity': {
              name: '队列容量',
              description: '该 FIFO 组件的队列可存储的信封数量上限。',
            },
            'discard.envelopes': {
              name: '队列满时丢弃新信封',
              description:
                '队列已满时的行为：为 true 时丢弃新到达的信封，否则先发出最早的信封腾出空间再入队。',
            },
          },
        },
        CloudPublisher: {
          name: '云发布者',
          properties: {
            'CloudPublisher.target': {
              name: '发布器目标过滤器',
              description:
                '指定用于发布消息的发布器 PID。仅为兼容上游 metatype 保留，本项目通过 Spring 注入绑定。',
            },
            'publish.forward': {
              name: '发布后继续转发',
              description: '为 true 时，发布后仍将收到的信封继续发往下游组件。',
            },
          },
        },
        CloudSubscriber: {
          name: '云订阅者',
          properties: {
            'CloudSubscriber.target': {
              name: '订阅源目标过滤器',
              description:
                '指定用于接收消息的订阅源 PID。仅为兼容上游 metatype 保留，本项目通过 Spring 注入绑定。',
            },
          },
        },
      },
      /** 选项标签：键为后端 metatype 的英文标签（取值仍为原 token，配置兼容性不受影响） */
      options: {
        VERBOSE: '详细',
        QUIET: '简洁',
        RETAIN: '保留匹配项',
        REMOVE: '移除匹配项',
        SIMPLE: '简单间隔',
        CRON: 'CRON 表达式',
        MILLISECONDS: '毫秒',
        SECONDS: '秒',
        MINUTES: '分钟',
        HOURS: '小时',
        DEFAULT: '默认',
        CUSTOM: '自定义',
      },
    },
    dialog: {
      downloadTitle: '导出 Wire 图快照',
      downloadJson: '下载 JSON',
      cancel: '取消',
      newComponentTitle: '新建组件',
      pidPrompt: '请输入组件 PID',
      confirmDeleteGraph: '确认删除整个图？该操作会同步到后端。',
      confirmDeleteComponent: '确认删除选中的组件及其所有连线？',
      confirmReset: '未保存的修改将丢失，确认重新加载？',
      confirmLeave: '当前有未保存的修改，确认离开？',
      confirm: '确认',
    },
    validation: {
      required: '该字段为必填项',
      number: '请输入合法数字',
      integer: '请输入合法整数',
      boolean: '取值必须为 true 或 false',
      char: '只能输入单个字符',
      min: '取值不能小于 {min}',
      max: '取值不能大于 {max}',
      options: '取值必须在可选项中',
      cycle: '不允许创建环路',
      portRange: '端口编号超出组件端口范围',
      duplicate: '该连线已存在',
      componentMissing: '连线两端的组件不存在',
      pidExists: 'PID 已被使用',
      factoryPidMissing: '组件缺少 factoryPid，无法保存（快照可能来自异构环境）',
    },
    messages: {
      assetConfigLoadFailed: '无法读取资产配置，已使用默认值：{msg}',
      nodeAlreadyInGraph: '该组件已在图里：{pid}',
      saveSuccess: '保存成功',
      loadSuccess: '加载成功',
      deleteSuccess: '删除成功',
      importSuccess: '导入成功（尚未保存）',
      importMissingConfig:
        '导入成功（尚未保存），但组件 {pids} 没有可用的组件配置、缺少 factoryPid，保存将失败',
      importFailed: '导入失败：{msg}',
      factoryPidMissingPids:
        '以下组件缺少 factoryPid，无法保存：{pids}（请重新导入整份快照，或在组件面板重建）',
      invalidSnapshot: '快照文件格式非法',
      loadFailed: '加载失败：{msg}',
      saveFailed: '保存失败：{msg}',
      exportFailed: '导出失败：{msg}',
      deleteFailed: '删除失败：{msg}',
    },
  },
  driversAssets: {
    intro: '创建与管理 Driver / Asset 实例，查看并修改 Asset 的通道值。',
    toolbar: {
      newDriver: '新建 Driver',
      newAsset: '新建 Asset',
      delete: '删除',
    },
    list: {
      title: 'Drivers and Assets',
      noDrivers: '没有可用的 Driver！',
    },
    detail: {
      none: '选择左侧的 Driver 或 Asset 以查看配置',
      driverTitle: 'Driver - {pid}',
      noFields: '该 Driver 没有可配置属性',
    },
    tabs: {
      configuration: 'Configuration',
      data: 'Data',
    },
    channels: {
      title: '通道（{count}）· {driver}',
      new: '新建通道',
      newTitle: '添加通道',
      nameLabel: '输入新通道的名字',
      add: '添加',
      delete: '删除通道',
      empty: '尚未定义通道',
      dirtyHint: '有未保存的通道改动',
    },
    data: {
      read: '读取',
      write: '写入',
      empty: '尚无数据，点击「读取」',
      notAvailable: '不可用',
      reason: '原因：',
      failureTitle: '通道失败详情',
      writeConfirm: '确定把修改后的通道值写入 Asset {0}？',
      statusUnknown: '未知',
      statusSuccess: '成功',
      statusFailure: '失败（点击查看详情）',
      statusModified: '已修改',
      statusDisabled: '已禁用',
      columnName: '名称',
      columnType: '类型',
      columnValueType: '取值类型',
      columnStatus: '状态',
      columnValue: '取值',
    },
    newDriver: {
      title: '创建新 Driver',
      intro: '选择一个 Driver 工厂并填写实例名。',
      factory: 'Driver 工厂',
      name: 'Driver 名称',
    },
    newAsset: {
      title: '创建新 Asset',
      intro: '填写实例名并选择该资产所属的 Driver。',
      name: 'Asset 名称',
      driver: 'Driver 名称',
      driverPlaceholder: '请选择 Driver',
      description: '资产描述',
    },
    actions: {
      apply: '应用',
      reset: '重置',
    },
    dialog: {
      confirmTitle: '确认',
      confirmDelete: '确定删除 "{pid}"？',
    },
    message: {
      created: '实例已创建',
      deleted: '实例已删除',
      applied: '配置已应用',
      written: '通道值已写入',
      formInvalid: '表单有错误或必填项为空，请检查。',
      configDirty: '资产配置有未保存的改动，请先应用或丢弃后再查看数据。',
      discardChanges: '有未保存的改动，确定放弃并继续吗？',
    },
    validation: {
      required: '必填项',
      invalidPid: '必须是合法的 service.pid',
      driverRequired: '请先选择该资产所属的 Driver',
      channelName: {
        empty: '通道名不能为空',
        invalidCharacters: '通道名不能包含以下字符： # / + , 与空格',
        duplicate: '该通道名已存在',
      },
    },
  },
}
