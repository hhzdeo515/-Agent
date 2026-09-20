/**
 * 统一 API 客户端。
 *
 * 三条约定：
 *  1. 所有请求带 traceId，便于与后端 audit_log / review_record 对账；
 *  2. 操作主体通过请求头传递（当前为本地联调方式，接入 JWT 后改为从 token 解析）；
 *  3. 错误统一抛 ApiError，调用方只需处理一种异常类型。
 */

export interface ApiResult<T> {
  code: number
  message: string
  data: T
  traceId?: string
}

export class ApiError extends Error {
  constructor(
    public readonly code: number,
    message: string,
    public readonly status: number,
  ) {
    super(message)
    this.name = 'ApiError'
  }
}

/**
 * 本地联调用的操作主体。
 * 接入登录后应改为由后端下发，且权限点绝不从客户端声明（docs/04 §1.8）。
 */
const ACTOR = {
  userId: '1',
  permissions: [
    'case.view', 'case.list', 'case.create', 'case.edit', 'case.upload', 'case.parse_retry',
    'case.requirement_confirm', 'case.start_initial_review', 'case.reject',
    'case.resume_remediation', 'case.back_to_feedback', 'case.archive',
    'risk.view', 'risk.confirm', 'risk.false_positive', 'risk.request_evidence',
    'risk.to_remediation', 'risk.rereview_trigger', 'risk.legal_final_review',
    'risk.sign', 'risk.close',
    'version.view', 'version.upload', 'version.diff_view',
    'material.approve', 'material.revoke_approval',
    'report.view', 'report.export',
    'assistant.use', 'assistant.file_upload', 'assistant.to_formal_case',
    // AI 调用配置（模型型号、超时、API Key）会改变所有审核结论的可比性，
    // 后端按运维级权限点校验，联调主体需一并带上，否则设置面板读不到也存不下
    'admin.config',
  ].join(','),
}

function newTraceId(): string {
  return Math.random().toString(16).slice(2, 10) + Date.now().toString(16).slice(-8)
}

async function handle<T>(resp: Response): Promise<T> {
  const text = await resp.text()
  let body: ApiResult<T> | null = null
  try {
    body = text ? (JSON.parse(text) as ApiResult<T>) : null
  } catch {
    /* 非 JSON 响应（如 502 网关页）走下面的兜底 */
  }

  if (!resp.ok) {
    throw new ApiError(
      body?.code ?? resp.status,
      body?.message ?? `请求失败（HTTP ${resp.status}）`,
      resp.status,
    )
  }
  if (body && body.code !== 0) {
    throw new ApiError(body.code, body.message, resp.status)
  }
  return (body?.data ?? (null as unknown)) as T
}

export async function apiGet<T>(path: string): Promise<T> {
  const resp = await fetch(path, {
    headers: { 'X-Actor-User-Id': ACTOR.userId, 'X-Actor-Permissions': ACTOR.permissions },
  })
  return handle<T>(resp)
}

export async function apiPost<T>(path: string, body?: unknown): Promise<T> {
  const traceId = newTraceId()
  const resp = await fetch(path, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json; charset=utf-8',
      'X-Trace-Id': traceId,
      'X-Actor-User-Id': ACTOR.userId,
      'X-Actor-Permissions': ACTOR.permissions,
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  return handle<T>(resp)
}

/**
 * 整体替换式更新（设置类接口）。
 *
 * 与 apiPost 的区别只在方法：设置面板保存的是"这一组配置的新状态"，
 * 反复 POST 会让后端难以区分"创建"与"覆盖"，也会让审计日志里出现语义错误的动作名。
 */
export async function apiPut<T>(path: string, body?: unknown): Promise<T> {
  const traceId = newTraceId()
  const resp = await fetch(path, {
    method: 'PUT',
    headers: {
      'Content-Type': 'application/json; charset=utf-8',
      'X-Trace-Id': traceId,
      'X-Actor-User-Id': ACTOR.userId,
      'X-Actor-Permissions': ACTOR.permissions,
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  return handle<T>(resp)
}

/** 文件上传：不走 JSON，用 multipart */
export async function apiUpload<T>(path: string, form: FormData): Promise<T> {
  const resp = await fetch(path, {
    method: 'POST',
    headers: {
      'X-Trace-Id': newTraceId(),
      'X-Actor-User-Id': ACTOR.userId,
      'X-Actor-Permissions': ACTOR.permissions,
    },
    body: form,
  })
  return handle<T>(resp)
}
