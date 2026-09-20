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
  $req = [System.Net.HttpWebRequest]::Create($uri)
  $req.Method = $method
  $req.Headers.Add('X-Actor-User-Id', "$UserId")
  $req.Headers.Add('X-Actor-Permissions', $perms)

  if ($null -ne $body) {
    $json = ($body | ConvertTo-Json -Depth 8 -Compress)
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($json)
    $req.ContentType = 'application/json; charset=utf-8'
    $req.ContentLength = $bytes.Length
    $stream = $req.GetRequestStream()
    $stream.Write($bytes, 0, $bytes.Length)
    $stream.Close()
  }

  $resp = $null
  try {
    $resp = $req.GetResponse()
  } catch [System.Net.WebException] {
    $resp = $_.Exception.Response
    if (-not $resp) { throw }
  }

  # 显式按 UTF-8 解码响应体。
  # 不能用 Invoke-RestMethod：Spring 返回的 Content-Type 不带 charset 时，
  # Windows PowerShell 5.1 会按 Latin-1 解码，中文变成「ç»­èª」这种乱码。
  # 那会让所有"中文文案合规性"断言（免责声明、是否冒充法务结论）静默失效——
  # 断言失败而产品其实是对的，比没有断言更浪费时间。
  $ms = New-Object System.IO.MemoryStream
  $resp.GetResponseStream().CopyTo($ms)
  $text = [System.Text.Encoding]::UTF8.GetString($ms.ToArray())
  $status = [int]$resp.StatusCode
  $resp.Close()

  if ($status -ge 400) {
    return [pscustomobject]@{ __httpStatus = $status; __error = $text }
  }
  if ([string]::IsNullOrWhiteSpace($text)) {
    return [pscustomobject]@{ code = 0; data = $null }
  }
  return ($text | ConvertFrom-Json)
}

function Check([string]$name, [bool]$cond, [string]$detail = "") {
  if ($cond) { $script:pass++; Write-Host ("  [PASS] " + $name) -ForegroundColor Green }
  else { $script:fail++; Write-Host ("  [FAIL] " + $name + " " + $detail) -ForegroundColor Red }
}

<#
  真实上传（multipart）。
  必须走这条路而不是"只登记元数据"的 /api/intake/materials：
  解析阶段会去对象存储读取文件真实内容，元数据登记方式下 MinIO 里没有对象，
  解析会如实失败——这是正确行为，所以验证脚本也要传真文件。
#>
function Upload([long]$caseId, [long]$materialId, [string]$file, [string]$reason, [string]$perms) {
  $argv = @(
    '-s', '-X', 'POST', "$Base/api/intake/materials/upload",
    '-H', "X-Actor-User-Id: $UserId",
    '-H', "X-Actor-Permissions: $perms",
    '-F', "caseId=$caseId",
    '-F', "file=@$file"
  )
  if ($materialId -gt 0) { $argv += @('-F', "materialId=$materialId") }
  if ($reason) { $argv += @('-F', "uploadReason=$reason") }
  $raw = & curl.exe @argv
  return $raw | ConvertFrom-Json
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

Write-Host "`n=== 2. 上传物料（真实文件 → 对象存储；解析会读取文件真实内容）===" -ForegroundColor Cyan
$tmpDir = Join-Path $env:TEMP "gw-e2e-$caseNo"
New-Item -ItemType Directory -Path $tmpDir -Force | Out-Null
$v1File = Join-Path $tmpDir '秋季新品宣传语.txt'
$v2File = Join-Path $tmpDir '秋季新品宣传语-V2.txt'
[System.IO.File]::WriteAllLines($v1File, @(
  '秋季新品上市宣传语',
  '本产品行业第一',
  '100% 保障安全',
  '续航可达 1000km'
), (New-Object System.Text.UTF8Encoding($false)))
# V2 去掉全部命中表述，用于验证 AI 复审能据实判定"原风险已解决"。
# 注意不能出现「续航」——它是 EVIDENCE_MISSING 的关键词，留着会让"仍有剩余风险"为真，
# 那样这条链路就走不到法务终审（这恰恰说明复审是真的在按内容判断，不是走过场）。
[System.IO.File]::WriteAllLines($v2File, @(
  '秋季新品上市宣传语',
  '专注该领域十余年',
  '通过国家强制性产品认证（CCC）',
  'CLTC 综合工况电耗 12.5kWh/100km'
), (New-Object System.Text.UTF8Encoding($false)))

$up = Upload $caseId 0 $v1File '首次上传' $legalPerms
Check "上传物料（真实 multipart）" ($up.code -eq 0 -and $up.data.fileSha256.Length -eq 64) ($up | ConvertTo-Json -Compress)
$materialId = $up.data.materialId
$versionId  = $up.data.versionId

Write-Host "`n=== 3. 触发解析（生成证据锚点）===" -ForegroundColor Cyan
$parse = Call POST "/api/intake/materials/$materialId/parse" $null $legalPerms
Check "解析产出锚点" ($parse.code -eq 0 -and $parse.data.anchorCount -gt 0) ($parse | ConvertTo-Json -Compress)
Write-Host "  锚点数=$($parse.data.anchorCount) 解析状态=$($parse.data.parseStatus)"
Check "锚点数与文件行数一致（说明审的是文件真实内容）" ($parse.data.anchorCount -eq 4) ("锚点=" + $parse.data.anchorCount)

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

Write-Host "`n=== 6b. 跨模块取任务：接收区创建的任务必须能被后续模块找到 ===" -ForegroundColor Cyan
$cases = Call GET '/api/intake/cases?limit=20' $null $legalPerms
Check "最近任务列表可查" ($cases.code -eq 0 -and $cases.data.Count -gt 0)
Check "列表包含本次任务" ([bool]($cases.data | Where-Object { $_.id -eq $caseId })) ("caseId=$caseId")
Check "列表不泄露风险明细" (-not ($cases.data[0].PSObject.Properties.Name -contains 'riskText'))

Write-Host "`n=== 6c. 审核依据：rule_refs 必须能还原成人能读的条款 ===" -ForegroundColor Cyan
$withBasis = @($risks.data | Where-Object { $_.basisText })
Check "至少一条风险带出条款原文" ($withBasis.Count -gt 0) ("带依据 " + $withBasis.Count + "/" + $risks.data.Count)
if ($withBasis.Count -gt 0) {
  Write-Host ("  示例：" + $withBasis[0].riskNo + " → " + ($withBasis[0].basisText -replace "`n", " / "))
}
Check "依据来自已发布法规（含法律名）" ($withBasis.Count -eq 0 -or $withBasis[0].basisText -match '广告法|民法典')

Write-Host "`n=== 6d. 整改责任方候选（越权应被拒）===" -ForegroundColor Cyan
$asg = Call GET '/api/feedback/assignees' $null $legalPerms
Check "法务可取责任方列表" ($asg.code -eq 0 -and $asg.data.Count -gt 0)
$asgDeny = Call GET '/api/feedback/assignees' $null $brandPerms
Check "品牌方取责任方列表被拒" ($asgDeny.__httpStatus -eq 403 -or $asgDeny.code -eq 40301) ($asgDeny | ConvertTo-Json -Compress)

Write-Host "`n=== 6e. AI 初审报告：数字必须与库中风险一致 ===" -ForegroundColor Cyan
$report = Call POST "/api/feedback/cases/$caseId/report" $null $legalPerms
Check "生成初审报告" ($report.code -eq 0 -and $report.data.exists) ($report | ConvertTo-Json -Compress)
$rep = $report.data.report
Check "报告记录内容哈希（64 位）" ($rep.contentHash.Length -eq 64) ("hash=" + $rep.contentHash)
Check "报告版本号从 1 开始" ($rep.revisionNo -eq 1) ("rev=" + $rep.revisionNo)
# 关键一致性：报告通过的不变量 —— 三分类之和 = 参与初审物料数
Check "三分类之和 = 参与初审物料数" (
  ($rep.initialPassCount + $rep.pendingHumanCount + $rep.riskFailCount) -eq $rep.reviewedMaterialCount
) ("$($rep.initialPassCount)+$($rep.pendingHumanCount)+$($rep.riskFailCount) vs $($rep.reviewedMaterialCount)")
# 报告正文里的风险条目数必须等于 risk_case 实际条数（AGENTS.md 第 11 条）
$mdRiskNo = ([regex]::Matches($rep.contentMd, '###\s+RK-\d+-\d+')).Count
Check "报告风险条目数 = 库中 Risk Case 数" ($mdRiskNo -eq $risks.data.Count) ("报告 $mdRiskNo 条 vs 库 " + $risks.data.Count + " 条")
Check "报告声明 AI 结论不等于法务批准" ($rep.contentMd -match '不等于法务最终批准|法务确认或批准之前')
Check "报告没写成法务已批准" (-not ($rep.contentMd -match '法务已批准|最终批准通过'))

$report2 = Call POST "/api/feedback/cases/$caseId/report" $null $legalPerms
Check "重新生成产生新修订而非覆盖" ($report2.data.report.revisionNo -eq 2) ("rev=" + $report2.data.report.revisionNo)

$reportGet = Call GET "/api/feedback/cases/$caseId/report" $null $legalPerms
Check "可取回最新报告（rev=2）" ($reportGet.data.report.revisionNo -eq 2)

Write-Host "`n=== 6f. 沟通话术（AGENTS.md 第 6 条）===" -ForegroundColor Cyan
$scripts = Call GET "/api/feedback/risks/$riskId/scripts" $null $legalPerms
Check "风险下有已生成话术" ($scripts.code -eq 0 -and $scripts.data.Count -gt 0) ($scripts | ConvertTo-Json -Compress)
if ($scripts.data.Count -gt 0) {
  Check "话术受众为品牌方" ($scripts.data[0].audience -eq 'BRAND')
  Check "话术不冒充法务定论" ($scripts.data[0].scriptText -match '以法务意见为准|供沟通参考')
}

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
$up2 = Upload $caseId $materialId $v2File '已删除绝对化用语' $legalPerms
Check "上传 V2" ($up2.code -eq 0 -and $up2.data.versionLabel -eq 'V2') ($up2 | ConvertTo-Json -Compress)
$v2Id = $up2.data.versionId
$parse2 = Call POST "/api/intake/materials/$materialId/parse" $null $legalPerms
Check "V2 解析" ($parse2.code -eq 0) ($parse2 | ConvertTo-Json -Compress)
Check "V2 锚点独立于 V1（版本内容不可覆盖）" ($parse2.data.anchorCount -gt 0 -and $v2Id -ne $versionId) ("v2Id=$v2Id v1Id=$versionId")

$afterV2 = Call GET "/api/final-review/risks?caseId=$caseId" $null $legalPerms
$cur = $afterV2.data | Where-Object { $_.id -eq $riskId }
Check "风险已进入终审区且状态为已重新提交" ($cur.status -eq 'RESUBMITTED') ("实际=" + $cur.status)

Write-Host "`n=== 10. 阻断性风险未关闭时，批准必须被拒 ===" -ForegroundColor Cyan
$ctx0 = Call GET "/api/final-review/materials/$materialId/approval-context?versionId=$v2Id" $null $legalPerms
Write-Host "  未关闭阻断性风险=$($ctx0.data.openBlockingRiskCount) 可批准=$(-not $ctx0.data.blockingRiskOpen)"
$early = Call POST "/api/final-review/materials/$materialId/approvals" @{ versionId = $v2Id } $legalPerms
Check "有阻断性风险时批准被拒（AGENTS.md 第 7 条）" ($early.__httpStatus -eq 422 -and $early.__error -match '43201') ($early | ConvertTo-Json -Compress)

Write-Host "`n=== 10b. Version Diff（AGENTS.md 第 7 条）===" -ForegroundColor Cyan
$diff = Call GET "/api/final-review/materials/$materialId/diff" $null $legalPerms
Check "生成版本差异" ($diff.code -eq 0 -and $diff.data.hasChange -eq 1) ($diff | ConvertTo-Json -Compress)
Check "差异指向 V1 → V2" ($diff.data.baseVersionId -eq $versionId -and $diff.data.targetVersionId -eq $v2Id) `
  ("base=" + $diff.data.baseVersionId + " target=" + $diff.data.targetVersionId)
$replaced = @($diff.data.payload.changes | Where-Object { $_.changeType -eq 'REPLACED' })
$unchanged = @($diff.data.payload.changes | Where-Object { $_.changeType -eq 'UNCHANGED' })
Write-Host "  变更=$($diff.data.payload.stats.changed)/$($diff.data.payload.stats.total) 替换=$($replaced.Count) 未变=$($unchanged.Count)"
Check "删掉的绝对化用语被识别为变更" ($replaced -or @($diff.data.payload.changes | Where-Object { $_.changeType -eq 'DELETED' }).Count -gt 0)
# 差异是"当时看到的事实"：重复请求必须复用同一份记录，而不是每次重算产生新版本
$diff2 = Call GET "/api/final-review/materials/$materialId/diff" $null $legalPerms
Check "重复请求复用同一份差异记录" ($diff2.data.diffId -eq $diff.data.diffId -and $diff.data.diffId) `
  ("1=" + $diff.data.diffId + " 2=" + $diff2.data.diffId)

Write-Host "`n=== 11. AI 复审（必须携带范围、拒绝 FULL、结论由服务端算出）===" -ForegroundColor Cyan
$badScope = Call POST "/api/final-review/risks/$riskId/rereview" @{ reviewScope = 'FULL' } $legalPerms
Check "复审范围 FULL 被拒" ($badScope.__httpStatus -eq 422 -or $badScope.__httpStatus -eq 400 -or $badScope.code -ne 0) ($badScope | ConvertTo-Json -Compress)

$re = Call POST "/api/final-review/risks/$riskId/rereview" @{ reviewScope = 'CHANGED_REGION_WITH_CONTEXT' } $legalPerms
Check "启动复审" ($re.code -eq 0 -and $re.data.status -eq 'AI_REREVIEW') ($re | ConvertTo-Json -Compress)

# 关键：结论由服务端根据"新版本锚点里还有没有这段原文"算出，前端只传范围。
# 旧接口把 originalResolved 当请求参数收，等于让调用方指定 AI 的判断。
$rer = Call POST "/api/final-review/risks/$riskId/rereview/result" `
  @{ reviewScope = 'CHANGED_REGION_WITH_CONTEXT' } $legalPerms
Check "复审返回三问结论" (
  $rer.code -eq 0 -and $null -ne $rer.data.originalResolved -and $null -ne $rer.data.remainingRisk
) ($rer | ConvertTo-Json -Compress)
Check "原风险已从新版本消失（据实判定）" ($rer.data.originalResolved -eq $true) `
  ("originalResolved=" + $rer.data.originalResolved + " summary=" + $rer.data.summary)
Check "未发现剩余同类风险" ($rer.data.remainingRisk -eq $false) ("remainingRisk=" + $rer.data.remainingRisk)
Check "复审结论带锚点证据" (@($rer.data.evidence.reviewedAnchorCount).Count -gt 0 -and $rer.data.evidence.reviewedAnchorCount -gt 0)
Check "复审措辞不冒充法律判断" ($rer.data.summary -match '仅文本比对|非法律判断|以法务意见为准')
Check "复审通过 → 法务终审" ($rer.data.status -eq 'LEGAL_FINAL_REVIEW') ("实际=" + $rer.data.status)

Write-Host "`n=== 11b. 法务终审（认可 = 落意见、不改状态）===" -ForegroundColor Cyan
$lf = Call POST "/api/final-review/risks/$riskId/legal-final-review" @{
  opinion  = 'E2E：认可 AI 复审结论，风险整改到位'
  decision = 'ACCEPT_REREVIEW'
} $legalPerms
Check "法务终审认可成功（此前该路径必然失败）" ($lf.code -eq 0) ($lf | ConvertTo-Json -Compress)
Check "认可后仍处于法务终审（等签名关闭）" ($lf.data.status -eq 'LEGAL_FINAL_REVIEW') ("实际=" + $lf.data.status)

Write-Host "`n=== 12. 无签名直接关闭应被拒（数据库触发器兜底）===" -ForegroundColor Cyan
$closeNoSign = Call POST "/api/final-review/risks/$riskId/close" @{ closeReason = '未签名尝试关闭' } $legalPerms
Check "无签名关闭被拒" ($closeNoSign.code -ne 0) ($closeNoSign | ConvertTo-Json -Compress)

Write-Host "`n=== 13. 逐条复审 → 法务终审 → 签名 → 关闭，直至全部风险关闭 ===" -ForegroundColor Cyan
$closedCount = 0
foreach ($r in $allRisks) {
  $id = $r.id
  if ($id -ne $riskId) {
    Call POST "/api/final-review/risks/$id/rereview" @{ reviewScope = 'CHANGED_REGION_WITH_CONTEXT' } $legalPerms | Out-Null
    Call POST "/api/final-review/risks/$id/rereview/result" `
      @{ reviewScope = 'CHANGED_REGION_WITH_CONTEXT' } $legalPerms | Out-Null
    Call POST "/api/final-review/risks/$id/legal-final-review" @{
      opinion = 'E2E：认可复审结论'; decision = 'ACCEPT_REREVIEW'
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

Write-Host "`n=== 14b. 撤销批准（此前无可查 approvalId 的接口）===" -ForegroundColor Cyan
$approvals = Call GET "/api/final-review/materials/$materialId/approvals" $null $legalPerms
Check "可查出批准记录" ($approvals.code -eq 0 -and $approvals.data.Count -gt 0) ($approvals | ConvertTo-Json -Compress)
$approvalId = $approvals.data[0].id
$revokeNoReason = Call POST "/api/final-review/materials/$materialId/approvals/$approvalId/revoke" @{ revokeReason = '' } $legalPerms
Check "撤销批准必须填原因" ($revokeNoReason.code -ne 0) ($revokeNoReason | ConvertTo-Json -Compress)
$revoke = Call POST "/api/final-review/materials/$materialId/approvals/$approvalId/revoke" `
  @{ revokeReason = 'E2E：验证撤销批准链路' } $legalPerms
Check "撤销批准" ($revoke.code -eq 0 -and $revoke.data.status -eq 'REVOKED') ($revoke | ConvertTo-Json -Compress)

Write-Host "`n=== 15. 设置：AI 配置读写与权限 ===" -ForegroundColor Cyan
$adminPerms = $legalPerms + ',admin.config'

# 权限：品牌方不该能看系统 AI 配置（模型型号属于运维信息）
$cfgDeny = Call GET '/api/settings/ai' $null $brandPerms
Check "无 admin.config 不能读 AI 配置" ($cfgDeny.__httpStatus -eq 403 -or $cfgDeny.code -eq 40301) ($cfgDeny | ConvertTo-Json -Compress)

$cfg0 = Call GET '/api/settings/ai' $null $adminPerms
Check "可读 AI 配置" ($cfg0.code -eq 0 -and $cfg0.data.models.text) ($cfg0 | ConvertTo-Json -Compress)
$origModel = $cfg0.data.models.text
$origKeyConfigured = $cfg0.data.apiKeyConfigured
Write-Host "  provider=$($cfg0.data.provider) text=$origModel keyConfigured=$origKeyConfigured"

# 只回掩码：哪怕调用方有 admin.config，也不该拿到完整 Key
Check "API Key 不回显明文" (
  -not $cfg0.data.apiKeyConfigured -or
  ($cfg0.data.apiKeyHint -and $cfg0.data.apiKeyHint -match '\*')
) ("hint=" + $cfg0.data.apiKeyHint)

# 写入一项非敏感配置并确认立即生效
$saveModel = Call PUT '/api/settings/ai' @{ textModel = 'qwen-max' } $adminPerms
Check "保存模型型号立即生效" ($saveModel.code -eq 0 -and $saveModel.data.models.text -eq 'qwen-max') `
  ("实际=" + $saveModel.data.models.text)

# 清除该项应回落到环境变量默认值，而不是保留上一次的值
$clearModel = Call PUT '/api/settings/ai' @{ textModel = $origModel } $adminPerms
Check "恢复原模型型号" ($clearModel.code -eq 0 -and $clearModel.data.models.text -eq $origModel) `
  ("实际=" + $clearModel.data.models.text)

# 越权：品牌方不能改
$saveDeny = Call PUT '/api/settings/ai' @{ textModel = 'qwen-turbo' } $brandPerms
Check "无 admin.config 不能改 AI 配置" ($saveDeny.__httpStatus -eq 403 -or $saveDeny.code -eq 40301) ($saveDeny | ConvertTo-Json -Compress)

Write-Host "`n=== 15b. 设置：个人信息 ===" -ForegroundColor Cyan
$prof0 = Call GET '/api/settings/profile' $null $legalPerms
Check "可读个人信息" ($prof0.code -eq 0 -and $prof0.data.username) ($prof0 | ConvertTo-Json -Compress)
$origDisplay = $prof0.data.displayName
$origDept = $prof0.data.dept
Write-Host "  username=$($prof0.data.username) role=$($prof0.data.roleCodes) displayName=$origDisplay"

$badName = Call PUT '/api/settings/profile' @{ displayName = '' } $legalPerms
Check "空显示名被拒" ($badName.code -ne 0) ($badName | ConvertTo-Json -Compress)

$prof1 = Call PUT '/api/settings/profile' @{ displayName = $origDisplay; dept = $origDept } $legalPerms
Check "可改本人信息（且只改显示名与部门）" ($prof1.code -eq 0 -and $prof1.data.username -eq $prof0.data.username) `
  ($prof1 | ConvertTo-Json -Compress)

Write-Host "`n=== 16. 反向追溯 ===" -ForegroundColor Cyan
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
