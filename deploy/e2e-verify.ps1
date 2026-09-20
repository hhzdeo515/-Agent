# ============================================================================
# 主链路端到端验证脚本
#
# 验证 AGENTS.md 第 14 条要求的主链路：
#   建 Case → 上传 → 解析 → 确认要求 → AI 初审 → Risk Case
#   → 法务确认 → 转整改 → V2 → 解析(触发重新提交) → AI 复审 → 签名 → 关闭 → 批准
#
# 用法（应用已在 8080 端口运行）：
#   pwsh -File deploy/e2e-verify.ps1
# ============================================================================

param(
  [string]$Base = "http://localhost:8080",
  [long]$UserId = 1
)

$ErrorActionPreference = "Stop"
$script:pass = 0
$script:fail = 0

# 本地联调用的法务主体：权限点通过请求头传入。
# 生产环境这些必须来自服务端的角色加载，绝不可由客户端声明（04 文档 §1.8）。
$legalPerms = @(
  'case.view','case.list','case.create','case.edit','case.upload','case.parse_retry',
  'case.requirement_confirm','case.start_initial_review','case.reject',
  'case.resume_remediation','case.back_to_feedback','case.archive',
  'risk.view','risk.confirm','risk.false_positive','risk.request_evidence',
  'risk.to_remediation','risk.rereview_trigger','risk.legal_final_review',
  'risk.sign','risk.close',
  'version.view','version.upload','version.diff_view',
  'material.approve','material.revoke_approval',
  'report.view','report.export'
) -join ','

# 品牌方主体：刻意不含确认风险与批准权限，用于验证越权会被拒绝
$brandPerms = 'case.view,case.list,case.create,case.upload,case.parse_retry,risk.view,version.view,version.upload'

function Headers([string]$perms) {
  return @{
    'Content-Type'          = 'application/json; charset=utf-8'
    'X-Actor-User-Id'       = "$UserId"
    'X-Actor-Permissions'   = $perms
  }
}

function Call([string]$method, [string]$path, $body, [string]$perms) {
  $uri = "$Base$path"
  $h = Headers $perms
  try {
    if ($null -eq $body) {
      return Invoke-RestMethod -Method $method -Uri $uri -Headers $h
    }
    $json = [System.Text.Encoding]::UTF8.GetBytes(($body | ConvertTo-Json -Depth 8 -Compress))
    return Invoke-RestMethod -Method $method -Uri $uri -Headers $h -Body $json
  } catch {
    $resp = $_.Exception.Response
    if ($resp -and $resp.StatusCode) {
      $code = [int]$resp.StatusCode
      $reader = New-Object System.IO.StreamReader($resp.GetResponseStream())
      $text = $reader.ReadToEnd()
      return [pscustomobject]@{ __httpStatus = $code; __error = $text }
    }
    throw
  }
}

function Check([string]$name, [bool]$cond, [string]$detail = "") {
  if ($cond) { $script:pass++; Write-Host ("  [PASS] " + $name) -ForegroundColor Green }
  else { $script:fail++; Write-Host ("  [FAIL] " + $name + " " + $detail) -ForegroundColor Red }
}

Write-Host "`n=== 0. 健康检查 ===" -ForegroundColor Cyan
try {
  $health = Invoke-RestMethod -Uri "$Base/actuator/health" -TimeoutSec 10
  Check "应用健康 ($($health.status))" ($health.status -eq 'UP')
} catch {
  Write-Host "  应用未就绪：$_" -ForegroundColor Red
  Write-Host "  请先启动：cd deploy; docker compose up -d" -ForegroundColor Yellow
  exit 1
}

Write-Host "`n=== 1. 创建审核任务 ===" -ForegroundColor Cyan
$case = Call POST '/api/intake/cases' @{
  projectId = 1
  name      = 'E2E 验证任务 ' + (Get-Date -Format 'HHmmss')
} $legalPerms
Check "创建 Case" ($case.code -eq 0) ($case | ConvertTo-Json -Compress)
$caseId = $case.data.id
$caseNo = $case.data.caseNo
Write-Host "  caseId=$caseId caseNo=$caseNo 初始状态=$($case.data.status)"

Write-Host "`n=== 2. 上传物料（纯文本，Mock 解析会产出可审核内容）===" -ForegroundColor Cyan
$sha = [System.BitConverter]::ToString(
  [System.Security.Cryptography.SHA256]::Create().ComputeHash(
    [System.Text.Encoding]::UTF8.GetBytes("e2e-$caseNo"))).Replace('-','').ToLower()
$up = Call POST '/api/intake/materials' @{
  caseId       = $caseId
  fileName     = '秋季新品宣传语.txt'
  materialType = 'TEXT'
  objectKey    = "e2e/$caseNo/v1.txt"
  sha256       = $sha
  fileSize     = 128
  mimeType     = 'text/plain'
  uploadReason = '首次上传'
} $legalPerms
Check "上传物料" ($up.code -eq 0) ($up | ConvertTo-Json -Compress)
$materialId = $up.data.materialId
$versionId  = $up.data.versionId

Write-Host "`n=== 3. 触发解析（生成证据锚点）===" -ForegroundColor Cyan
$parse = Call POST "/api/intake/materials/$materialId/parse" $null $legalPerms
Check "解析产出锚点" ($parse.code -eq 0 -and $parse.data.anchorCount -gt 0) ($parse | ConvertTo-Json -Compress)
Write-Host "  锚点数=$($parse.data.anchorCount) 解析状态=$($parse.data.parseStatus)"

Write-Host "`n=== 4. 确认审核要求（模板须人工确认才生效）===" -ForegroundColor Cyan
$req = Call POST '/api/intake/requirements' @{
  caseId     = $caseId
  templateCode = 'DEFAULT'
  requirement = @{ focusPoints = @('绝对化宣传','安全承诺','无依据数据') }
} $legalPerms
Check "确认审核要求" ($req.code -eq 0) ($req | ConvertTo-Json -Compress)

Write-Host "`n=== 5. 启动 AI 初审 ===" -ForegroundColor Cyan
$review = Call POST "/api/intake/cases/$caseId/initial-review" $null $legalPerms
Check "AI 初审产出风险" ($review.code -eq 0 -and $review.data.riskCreated -gt 0) ($review | ConvertTo-Json -Compress)
Write-Host "  新建风险=$($review.data.riskCreated) 任务状态=$($review.data.caseStatus)"

Write-Host "`n=== 6. 反馈区：三分类与风险列表 ===" -ForegroundColor Cyan
$summary = Call GET "/api/feedback/cases/$caseId/summary" $null $legalPerms
Check "取初审汇总" ($summary.code -eq 0) ($summary | ConvertTo-Json -Compress)
$risks = Call GET "/api/feedback/risks?caseId=$caseId" $null $legalPerms
Check "风险列表非空" ($risks.code -eq 0 -and $risks.data.Count -gt 0)
$riskId = $risks.data[0].id
Write-Host "  风险数=$($risks.data.Count) 首条=$($risks.data[0].riskNo) 等级=$($risks.data[0].riskLevel) 状态=$($risks.data[0].status)"

Write-Host "`n=== 7. 越权验证：品牌方不能确认风险 ===" -ForegroundColor Cyan
$deny = Call POST "/api/feedback/risks/$riskId/confirm" @{ opinion = '越权尝试' } $brandPerms
Check "品牌方确认风险被拒" ($deny.__httpStatus -eq 403 -or $deny.code -eq 40301) ($deny | ConvertTo-Json -Compress)

Write-Host "`n=== 8. 法务确认风险 → 转整改 ===" -ForegroundColor Cyan
# 说明：Mock 初审判定会为锚点文本中的每个违规点各建一条 Risk Case（本例 5 条）。
# 这恰好验证了"一张海报多个独立风险 → 多条可分别处理的 Risk Case"这一要求，
# 但也意味着后续动作必须对所有风险逐条执行，否则物料无法通过最终批准。
$allRisks = @($risks.data)
$confirm = Call POST "/api/feedback/risks/$riskId/confirm" @{ opinion = 'E2E：确认存在绝对化宣传风险' } $legalPerms
Check "确认风险" ($confirm.code -eq 0 -and $confirm.data.status -eq 'CONFIRMED') ($confirm | ConvertTo-Json -Compress)

$remOk = 0
foreach ($r in $allRisks) {
  if ($r.id -ne $riskId) {
    Call POST "/api/feedback/risks/$($r.id)/confirm" @{ opinion = 'E2E：批量确认' } $legalPerms | Out-Null
  }
  $x = Call POST "/api/feedback/risks/$($r.id)/to-remediation" @{
    assigneeId = $UserId; note = 'E2E：转整改'
  } $legalPerms
  if ($x.code -eq 0 -and $x.data.status -eq 'AWAITING_REVISION') { $remOk++ }
}
Check "全部风险转入整改（$remOk/$($allRisks.Count)）" ($remOk -eq $allRisks.Count) "成功 $remOk 条"

Write-Host "`n=== 9. 上传 V2 并解析（触发"已重新提交"）===" -ForegroundColor Cyan
$sha2 = [System.BitConverter]::ToString(
  [System.Security.Cryptography.SHA256]::Create().ComputeHash(
    [System.Text.Encoding]::UTF8.GetBytes("e2e-$caseNo-v2"))).Replace('-','').ToLower()
$up2 = Call POST '/api/intake/materials' @{
  caseId       = $caseId
  materialId   = $materialId
  fileName     = '秋季新品宣传语-V2.txt'
  materialType = 'TEXT'
  objectKey    = "e2e/$caseNo/v2.txt"
  sha256       = $sha2
  fileSize     = 120
  mimeType     = 'text/plain'
  uploadReason = '已删除绝对化用语'
} $legalPerms
Check "上传 V2" ($up2.code -eq 0 -and $up2.data.versionLabel -eq 'V2') ($up2 | ConvertTo-Json -Compress)
$v2Id = $up2.data.versionId
$parse2 = Call POST "/api/intake/materials/$materialId/parse" $null $legalPerms
Check "V2 解析" ($parse2.code -eq 0) ($parse2 | ConvertTo-Json -Compress)

$afterV2 = Call GET "/api/final-review/risks?caseId=$caseId" $null $legalPerms
$cur = $afterV2.data | Where-Object { $_.id -eq $riskId }
Check "风险已进入终审区且状态为已重新提交" ($cur.status -eq 'RESUBMITTED') ("实际=" + $cur.status)

Write-Host "`n=== 10. 阻断性风险未关闭时，批准必须被拒 ===" -ForegroundColor Cyan
$ctx0 = Call GET "/api/final-review/materials/$materialId/approval-context?versionId=$v2Id" $null $legalPerms
Write-Host "  未关闭阻断性风险=$($ctx0.data.openBlockingRiskCount) 可批准=$(-not $ctx0.data.blockingRiskOpen)"
$early = Call POST "/api/final-review/materials/$materialId/approvals" @{ versionId = $v2Id } $legalPerms
Check "有阻断性风险时批准被拒（AGENTS.md 第 7 条）" ($early.__httpStatus -eq 422 -and $early.__error -match '43201') ($early | ConvertTo-Json -Compress)

Write-Host "`n=== 11. AI 复审（必须携带范围且拒绝 FULL）===" -ForegroundColor Cyan
$badScope = Call POST "/api/final-review/risks/$riskId/rereview" @{ reviewScope = 'FULL' } $legalPerms
Check "复审范围 FULL 被拒" ($badScope.__httpStatus -eq 422 -or $badScope.__httpStatus -eq 400 -or $badScope.code -ne 0) ($badScope | ConvertTo-Json -Compress)

$re = Call POST "/api/final-review/risks/$riskId/rereview" @{ reviewScope = 'CHANGED_REGION_WITH_CONTEXT' } $legalPerms
Check "启动复审" ($re.code -eq 0 -and $re.data.status -eq 'AI_REREVIEW') ($re | ConvertTo-Json -Compress)

$rer = Call POST "/api/final-review/risks/$riskId/rereview/result" @{
  originalResolved = $true
  remainingRisk    = $false
  summary          = 'E2E：原风险已解决，未发现新增风险'
} $legalPerms
Check "复审通过 → 法务终审" ($rer.code -eq 0 -and $rer.data.status -eq 'LEGAL_FINAL_REVIEW') ($rer | ConvertTo-Json -Compress)

Write-Host "`n=== 12. 无签名直接关闭应被拒（数据库触发器兜底）===" -ForegroundColor Cyan
$closeNoSign = Call POST "/api/final-review/risks/$riskId/close" @{ closeReason = '未签名尝试关闭' } $legalPerms
Check "无签名关闭被拒" ($closeNoSign.code -ne 0) ($closeNoSign | ConvertTo-Json -Compress)

Write-Host "`n=== 13. 逐条复审 → 签名 → 关闭，直至全部风险关闭 ===" -ForegroundColor Cyan
$closedCount = 0
foreach ($r in $allRisks) {
  $id = $r.id
  if ($id -ne $riskId) {
    Call POST "/api/final-review/risks/$id/rereview" @{ reviewScope = 'CHANGED_REGION_WITH_CONTEXT' } $legalPerms | Out-Null
    Call POST "/api/final-review/risks/$id/rereview/result" @{
      originalResolved = $true; remainingRisk = $false; summary = 'E2E：已解决'
    } $legalPerms | Out-Null
  }
  $sg = Call POST "/api/final-review/risks/$id/signatures" @{ comment = 'E2E：确认风险已消除' } $legalPerms
  if ($id -eq $riskId) { Check "法务签名" ($sg.code -eq 0 -and $sg.data.signType -eq 'RISK_CLOSE') ($sg | ConvertTo-Json -Compress) }
  $cl = Call POST "/api/final-review/risks/$id/close" @{ closeReason = 'E2E：整改完成并已签名' } $legalPerms
  if ($id -eq $riskId) { Check "关闭风险" ($cl.code -eq 0 -and $cl.data.status -eq 'CLOSED') ($cl | ConvertTo-Json -Compress) }
  if ($cl.code -eq 0 -and $cl.data.status -eq 'CLOSED') { $closedCount++ }
}
Check "全部风险已关闭（$closedCount/$($allRisks.Count)）" ($closedCount -eq $allRisks.Count) "关闭 $closedCount 条"

Write-Host "`n=== 14. 标记最终批准版本 ===" -ForegroundColor Cyan
$ctx = Call GET "/api/final-review/materials/$materialId/approval-context?versionId=$v2Id" $null $legalPerms
Check "取批准影响范围" ($ctx.code -eq 0) ($ctx | ConvertTo-Json -Compress)
Write-Host "  未关闭阻断性风险=$($ctx.data.openBlockingRiskCount) 可批准=$(-not $ctx.data.blockingRiskOpen)"

$appr = Call POST "/api/final-review/materials/$materialId/approvals" @{
  versionId = $v2Id
  comment   = 'E2E：批准上线'
} $legalPerms
Check "批准物料版本" ($appr.code -eq 0 -and $appr.data.status -eq 'APPROVED') ($appr | ConvertTo-Json -Compress)
Check "批准绑定精确版本与哈希快照" ($appr.data.versionId -eq $v2Id -and $appr.data.fileSha256.Length -eq 64) ("sha=" + $appr.data.fileSha256)

Write-Host "`n=== 15. 反向追溯 ===" -ForegroundColor Cyan
$records = Call GET "/api/feedback/risks/$riskId/records" $null $legalPerms
Check "风险处理轨迹完整" ($records.code -eq 0 -and $records.data.Count -ge 5)
Write-Host "  审核记录数=$($records.data.Count)"
$records.data | ForEach-Object {
  Write-Host ("    - " + $_.action + " : " + $_.fromStatus + " -> " + $_.toStatus + " [" + $_.actorType + "]")
}

Write-Host "`n============================================" -ForegroundColor Cyan
Write-Host ("  通过 " + $script:pass + " 项，失败 " + $script:fail + " 项") -ForegroundColor $(if ($script:fail -eq 0) { 'Green' } else { 'Yellow' })
Write-Host "============================================`n" -ForegroundColor Cyan

if ($script:fail -gt 0) { exit 1 }
