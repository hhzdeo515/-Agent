<#
  演示数据准备：造一个"看起来像真的"审核任务。

  为什么需要它：Mock 解析器现在会读取<b>文件真实内容</b>（见 MockParseAdapter），
  所以演示不能再用"只登记元数据"的接口——必须真的把文件传进对象存储，
  否则解析阶段会如实报错。这本身就是对的行为。

  产出：
    * 一个审核任务（含真实上传的宣传文案 .txt）
    * 已解析的证据锚点（每行一个）
    * 已确认的审核要求
    * 第一次 AI 初审产出的 Risk Case（保持 OPEN，供反馈区演示）
    * 一版 AI 初审报告

  用法：
    pwsh -File deploy/demo-seed.ps1
#>

param(
  [string]$Base = "http://localhost:8080",
  [long]$UserId = 1,
  [switch]$SkipV2,
  # 加这个开关会额外把风险确认并转入整改，再上传 V2 —— 用于演示终审区
  # （不加则风险停在"新建"，适合演示反馈区的判断工作流）
  [switch]$Remediate
)

$ErrorActionPreference = "Stop"

$legalPerms = @(
  'case.view','case.list','case.create','case.edit','case.upload','case.parse_retry',
  'case.requirement_confirm','case.start_initial_review','case.reject',
  'case.resume_remediation','case.back_to_feedback','case.archive',
  'risk.view','risk.confirm','risk.false_positive','risk.request_evidence',
  'risk.to_remediation','risk.rereview_trigger','risk.legal_final_review',
  'risk.sign','risk.close',
  'version.view','version.upload','version.diff_view',
  'material.approve','material.revoke_approval',
  'report.view','report.export','assistant.use','assistant.file_upload'
) -join ','

function Json([string]$method, [string]$path, $body) {
  $h = @{
    'Content-Type'        = 'application/json; charset=utf-8'
    'X-Actor-User-Id'     = "$UserId"
    'X-Actor-Permissions' = $legalPerms
  }
  if ($null -eq $body) { return Invoke-RestMethod -Method $method -Uri "$Base$path" -Headers $h }
  $bytes = [System.Text.Encoding]::UTF8.GetBytes(($body | ConvertTo-Json -Depth 8 -Compress))
  return Invoke-RestMethod -Method $method -Uri "$Base$path" -Headers $h -Body $bytes
}

<#
  真实上传（multipart）。
  用 curl.exe 而不是 Invoke-RestMethod：Windows PowerShell 5.1 的
  Invoke-RestMethod 没有 -Form 参数，无法构造 multipart 请求。
#>
function Upload([long]$caseId, [long]$materialId, [string]$file, [string]$reason) {
  $argv = @(
    '-s', '-X', 'POST', "$Base/api/intake/materials/upload",
    '-H', "X-Actor-User-Id: $UserId",
    '-H', "X-Actor-Permissions: $legalPerms",
    '-F', "caseId=$caseId",
    '-F', "file=@$file"
  )
  if ($materialId -gt 0) { $argv += @('-F', "materialId=$materialId") }
  if ($reason) { $argv += @('-F', "uploadReason=$reason") }
  $raw = & curl.exe @argv
  return $raw | ConvertFrom-Json
}

# ── 素材：一份典型的新品上市主视觉文案 ──────────────────────────────────────
$v1Lines = @(
  '2026 秋季新品上市主视觉文案',
  '',
  '行业第一的新能源智造品牌',
  '100% 保障安全，全家出行无忧',
  'CLTC 综合续航可达 1000km',
  '国家级权威认证，品质更有保障',
  '限时立减 5000 元，仅限本月',
  '同级最优，全面超越同级竞品'
)

# V2：去掉绝对化用语与无依据数据，保留可验证表述 —— 用于验证"原风险已解决"
$v2Lines = @(
  '2026 秋季新品上市主视觉文案',
  '',
  '专注新能源智造十余年',
  '通过国家强制性产品认证（CCC）',
  'CLTC 综合续航 705km（工况法，实际受路况与驾驶习惯影响）',
  '限时优惠 5000 元，活动细则以门店公示为准'
)

$tmp = Join-Path $env:TEMP "gw-demo-$(Get-Date -Format 'HHmmss')"
New-Item -ItemType Directory -Path $tmp -Force | Out-Null
$v1File = Join-Path $tmp '秋季新品上市主视觉文案.txt'
$v2File = Join-Path $tmp '秋季新品上市主视觉文案-V2.txt'
# UTF-8 无 BOM：与设计/市场同事从 Windows 记事本另存的文件一致
[System.IO.File]::WriteAllLines($v1File, $v1Lines, (New-Object System.Text.UTF8Encoding($false)))
[System.IO.File]::WriteAllLines($v2File, $v2Lines, (New-Object System.Text.UTF8Encoding($false)))

Write-Host "`n=== 1. 创建审核任务 ===" -ForegroundColor Cyan
$case = Json POST '/api/intake/cases' @{
  projectId = 1
  name      = '2026 秋季新品上市宣传物料审核'
  deadline  = (Get-Date).AddDays(3).ToString('yyyy-MM-ddTHH:mm:ss')
}
$caseId = $case.data.id
Write-Host "  caseId=$caseId  caseNo=$($case.data.caseNo)"

Write-Host "`n=== 2. 上传物料原件（真实 multipart，写入对象存储）===" -ForegroundColor Cyan
$up = Upload $caseId 0 $v1File '首次上传'
if ($up.code -ne 0) { throw "上传失败：$($up | ConvertTo-Json -Compress)" }
$materialId = $up.data.materialId
$v1Id = $up.data.versionId
Write-Host "  materialId=$materialId  version=$($up.data.versionLabel)  sha256=$($up.data.fileSha256.Substring(0,16))…"

Write-Host "`n=== 3. 解析（Mock 读取文件真实内容，逐行生成锚点）===" -ForegroundColor Cyan
$parse = Json POST "/api/intake/materials/$materialId/parse" $null
Write-Host "  锚点数=$($parse.data.anchorCount)  解析状态=$($parse.data.parseStatus)"
if ($parse.data.anchorCount -lt 3) { throw "锚点过少，解析未生效：$($parse.data.parseErrorMessage)" }

Write-Host "`n=== 4. 确认审核要求 ===" -ForegroundColor Cyan
Json POST '/api/intake/requirements' @{
  caseId       = $caseId
  templateCode = 'DEFAULT'
  requirement  = @{ focusPoints = @(
    '绝对化宣传','安全承诺','无依据数据','竞品相关表达','价格宣传','免责声明缺失'
  ) }
} | Out-Null

Write-Host "`n=== 5. 启动 AI 初审 ===" -ForegroundColor Cyan
$review = Json POST "/api/intake/cases/$caseId/initial-review" $null
Write-Host "  新建风险=$($review.data.riskCreated)  任务状态=$($review.data.caseStatus)"

Write-Host "`n=== 6. 查看初审结果与审核依据 ===" -ForegroundColor Cyan
$risks = Json GET "/api/feedback/risks?caseId=$caseId" $null
foreach ($r in $risks.data) {
  $basis = if ($r.basisText) { ($r.basisText -split "`n")[0] } else { '（无，已转人工判断）' }
  Write-Host ("  {0}  {1,-6} {2,-14} 原文「{3}」" -f $r.riskNo, $r.riskLevel, $r.riskTypeLabel, $r.riskText)
  Write-Host ("        依据：{0}" -f $basis) -ForegroundColor DarkGray
}

Write-Host "`n=== 7. 生成 AI 初审报告 ===" -ForegroundColor Cyan
$rep = Json POST "/api/feedback/cases/$caseId/report" $null
Write-Host "  第 $($rep.data.report.revisionNo) 版  哈希=$($rep.data.report.contentHash.Substring(0,16))…  正文 $($rep.data.report.contentMd.Length) 字"

if (-not $SkipV2) {
  # ── 可选：把风险推进到终审区
  # 必须先确认并转入整改，风险才会从"新建"走到"整改中"；
  # 直接上传 V2 的话，风险仍停在新建，终审区里什么都不会出现。
  if ($Remediate) {
    Write-Host "`n=== 8. 法务确认并转入整改（终审区演示前置）===" -ForegroundColor Cyan
    $moved = 0
    foreach ($r in $risks.data) {
      Json POST "/api/feedback/risks/$($r.id)/confirm" @{
        opinion = '演示：确认存在风险，需修改后重新提交'
      } | Out-Null
      $x = Json POST "/api/feedback/risks/$($r.id)/to-remediation" @{
        assigneeId = $UserId
        note       = '演示：请品牌部在 3 个工作日内完成修改'
        dueAt      = (Get-Date).AddDays(3).ToString('yyyy-MM-ddTHH:mm:ss')
      }
      if ($x.code -eq 0 -and $x.data.status -eq 'AWAITING_REVISION') { $moved++ }
    }
    Write-Host "  已转入整改 $moved / $($risks.data.Count) 条"
  }

  Write-Host "`n=== 9. 上传 V2 并解析 ===" -ForegroundColor Cyan
  $up2 = Upload $caseId $materialId $v2File '已删除绝对化用语与无依据数据'
  Write-Host "  version=$($up2.data.versionLabel)"
  $p2 = Json POST "/api/intake/materials/$materialId/parse" $null
  Write-Host "  V2 锚点=$($p2.data.anchorCount)  状态=$($p2.data.parseStatus)"

  if ($Remediate) {
    $fr = Json GET "/api/final-review/risks?caseId=$caseId" $null
    Write-Host "  终审区可见风险 $($fr.data.Count) 条（状态 $($fr.data[0].status)）"
    $d = Json GET "/api/final-review/materials/$materialId/diff" $null
    Write-Host "  版本差异：变更 $($d.data.payload.stats.changed)/$($d.data.payload.stats.total) 处（$($d.data.payload.baseVersionLabel) → $($d.data.payload.targetVersionLabel)）"
  }
}

Write-Host "`n完成。caseId=$caseId" -ForegroundColor Green
Write-Host "前端默认会认领最近的任务；若未自动选中，可在顶部「任务」下拉里切换。`n"
