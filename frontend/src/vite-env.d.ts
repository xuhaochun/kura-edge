/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** 可选：HTTP Basic 认证用户名（wires.admin），仅开发联调用，禁止提交真实凭据 */
  readonly VITE_WIRES_USERNAME?: string
  /** 可选：HTTP Basic 认证密码 */
  readonly VITE_WIRES_PASSWORD?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
