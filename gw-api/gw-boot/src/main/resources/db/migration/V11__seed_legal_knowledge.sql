-- ============================================================================
-- V11 导入法定依据：《中华人民共和国广告法》《中华人民共和国民法典》
--
-- 来源（均为官方站点，用于保证可追溯性 —— AGENTS.md 第 10 条要求每条知识保存来源）：
--   广告法：国家市场监督管理总局
--     https://www.samr.gov.cn/zw/zfxxgk/fdzdgknr/fgs/art/2023/art_5474cf75173c45d6a0379730fb4e8d97.html
--   民法典：中华人民共和国最高人民法院
--     https://www.court.gov.cn/zixun/xiangqing/233181.html
--
-- 治理状态直接置为 PUBLISHED：这两部是公开发布的国家法律，
-- 且已在 kb_item_version 中记录发布日期与生效日期，不存在"未经治理写回"的问题。
-- 内部规则、产品参数、历史案例等其他类型知识仍必须走 DRAFT → PENDING_APPROVAL → PUBLISHED。
--
-- 条款范围：只导入与广宣内容审核直接相关的条款，不是全文转录。
-- 全文可另作长期归档，此处按"可被风险规则引用"的标准筛选。
-- ============================================================================

SET NAMES utf8mb4;

-- ── 广告法 ──────────────────────────────────────────────────────────────────
INSERT INTO kb_item (id, item_type, title, region, applicable_business, publish_date,
                     effective_date, trust_level, governance_status, maintainer_id)
VALUES (9001, 'LAW', '中华人民共和国广告法', '中国', '广告与宣传内容审核',
        '2021-04-29', '2021-04-29', 'HIGH', 'PUBLISHED', 1);

INSERT INTO kb_item_version (kb_item_id, version_no, content, source_url, source_note,
                             content_hash, effective_from, approved_by, approved_at)
VALUES (9001, 1,
'（1994年10月27日第八届全国人民代表大会常务委员会第十次会议通过；2015年4月24日修订；2018年10月26日第一次修正；2021年4月29日第二次修正）',
'https://www.samr.gov.cn/zw/zfxxgk/fdzdgknr/fgs/art/2023/art_5474cf75173c45d6a0379730fb4e8d97.html',
'国家市场监督管理总局 · 总局主要执行的法律',
SHA2('广告法-v1', 256), '2021-04-29', 1, NOW(3));

-- 逐条切片：每条一个 chunk，检索时可精确定位到条款
INSERT INTO kb_chunk (kb_item_version_id, chunk_no, text, token_count)
SELECT v.id, 1, '第四条　广告不得含有虚假或者引人误解的内容，不得欺骗、误导消费者。广告主应当对广告内容的真实性负责。', 45
  FROM kb_item_version v WHERE v.kb_item_id = 9001 AND v.version_no = 1;

INSERT INTO kb_chunk (kb_item_version_id, chunk_no, text, token_count)
SELECT v.id, 2, '第八条　广告中对商品的性能、功能、产地、用途、质量、成分、价格、生产者、有效期限、允诺等或者对服务的内容、提供者、形式、质量、价格、允诺等有表示的，应当准确、清楚、明白。法律、行政法规规定广告中应当明示的内容，应当显著、清晰表示。', 105
  FROM kb_item_version v WHERE v.kb_item_id = 9001 AND v.version_no = 1;

INSERT INTO kb_chunk (kb_item_version_id, chunk_no, text, token_count)
SELECT v.id, 3, '第九条　广告不得有下列情形：（三）使用“国家级”、“最高级”、“最佳”等用语；（十一）法律、行政法规规定禁止的其他情形。', 62
  FROM kb_item_version v WHERE v.kb_item_id = 9001 AND v.version_no = 1;

INSERT INTO kb_chunk (kb_item_version_id, chunk_no, text, token_count)
SELECT v.id, 4, '第十一条　广告内容涉及的事项需要取得行政许可的，应当与许可的内容相符合。广告使用数据、统计资料、调查结果、文摘、引用语等引证内容的，应当真实、准确，并表明出处。引证内容有适用范围和有效期限的，应当明确表示。', 100
  FROM kb_item_version v WHERE v.kb_item_id = 9001 AND v.version_no = 1;

INSERT INTO kb_chunk (kb_item_version_id, chunk_no, text, token_count)
SELECT v.id, 5, '第十三条　广告不得贬低其他生产经营者的商品或者服务。', 26
  FROM kb_item_version v WHERE v.kb_item_id = 9001 AND v.version_no = 1;

INSERT INTO kb_chunk (kb_item_version_id, chunk_no, text, token_count)
SELECT v.id, 6, '第二十八条　广告以虚假或者引人误解的内容欺骗、误导消费者的，构成虚假广告。广告有下列情形之一的，为虚假广告：（二）商品的性能、功能、产地、用途、质量、规格、成分、价格、生产者、有效期限、销售状况、曾获荣誉等信息……与实际情况不符，对购买行为有实质性影响的；（三）使用虚构、伪造或者无法验证的科研成果、统计资料、调查结果、文摘、引用语等信息作证明材料的；（四）虚构使用商品或者接受服务的效果的。', 175
  FROM kb_item_version v WHERE v.kb_item_id = 9001 AND v.version_no = 1;

INSERT INTO kb_chunk (kb_item_version_id, chunk_no, text, token_count)
SELECT v.id, 7, '第五十五条　违反本法规定，发布虚假广告的，由市场监督管理部门责令停止发布广告，责令广告主在相应范围内消除影响，处广告费用三倍以上五倍以下的罚款；两年内有三次以上违法行为或者有其他严重情节的，处广告费用五倍以上十倍以下的罚款，可以吊销营业执照。', 130
  FROM kb_item_version v WHERE v.kb_item_id = 9001 AND v.version_no = 1;

-- ── 民法典 ──────────────────────────────────────────────────────────────────
INSERT INTO kb_item (id, item_type, title, region, applicable_business, publish_date,
                     effective_date, trust_level, governance_status, maintainer_id)
VALUES (9002, 'LAW', '中华人民共和国民法典', '中国', '民事法律行为与责任',
        '2020-05-28', '2021-01-01', 'HIGH', 'PUBLISHED', 1);

INSERT INTO kb_item_version (kb_item_id, version_no, content, source_url, source_note,
                             content_hash, effective_from, approved_by, approved_at)
VALUES (9002, 1,
'（2020年5月28日第十三届全国人民代表大会第三次会议通过，自2021年1月1日起施行）',
'https://www.court.gov.cn/zixun/xiangqing/233181.html',
'中华人民共和国最高人民法院 · 要闻（来源：新华网）',
SHA2('民法典-v1', 256), '2021-01-01', 1, NOW(3));

INSERT INTO kb_chunk (kb_item_version_id, chunk_no, text, token_count)
SELECT v.id, 1, '第七条　民事主体从事民事活动，应当遵循诚信原则，秉持诚实，恪守承诺。', 34
  FROM kb_item_version v WHERE v.kb_item_id = 9002 AND v.version_no = 1;

INSERT INTO kb_chunk (kb_item_version_id, chunk_no, text, token_count)
SELECT v.id, 2, '第四百七十三条　要约邀请是希望他人向自己发出要约的表示。拍卖公告、招标公告、招股说明书、债券募集办法、基金招募说明书、商业广告和宣传、寄送的价目表等为要约邀请。商业广告和宣传的内容符合要约条件的，构成要约。', 92
  FROM kb_item_version v WHERE v.kb_item_id = 9002 AND v.version_no = 1;

INSERT INTO kb_chunk (kb_item_version_id, chunk_no, text, token_count)
SELECT v.id, 3, '第一百四十八条　一方以欺诈手段，使对方在违背真实意思的情况下实施的民事法律行为，受欺诈方有权请求人民法院或者仲裁机构予以撤销。', 63
  FROM kb_item_version v WHERE v.kb_item_id = 9002 AND v.version_no = 1;

INSERT INTO kb_chunk (kb_item_version_id, chunk_no, text, token_count)
SELECT v.id, 4, '第五百条　当事人在订立合同过程中有下列情形之一，造成对方损失的，应当承担赔偿责任：（一）假借订立合同，恶意进行磋商；（二）故意隐瞒与订立合同有关的重要事实或者提供虚假情况；（三）有其他违背诚信原则的行为。', 100
  FROM kb_item_version v WHERE v.kb_item_id = 9002 AND v.version_no = 1;

INSERT INTO kb_chunk (kb_item_version_id, chunk_no, text, token_count)
SELECT v.id, 5, '第一百七十九条　承担民事责任的方式主要有：（一）停止侵害；（八）赔偿损失；（十）消除影响、恢复名誉；（十一）赔礼道歉。法律规定惩罚性赔偿的，依照其规定。', 78
  FROM kb_item_version v WHERE v.kb_item_id = 9002 AND v.version_no = 1;

-- ── 风险规则：把规则与法条绑定 ──────────────────────────────────────────────
-- ruleset_version 会写入 risk_case，使"规则变了所以结论变了"可被解释
INSERT INTO risk_rule (id, rule_code, name, rule_type, description, severity_default,
                       ruleset_version, status, applicable_scope)
VALUES
 (9101, 'ABSOLUTE_CLAIM_01', '绝对化用语', 'ABSOLUTE_CLAIM',
  '广告不得使用“国家级”“最高级”“最佳”等用语。除明确列举外，“第一”“首个”“唯一”“顶级”等具有排他性或最高级含义的表述同样属于本项规制范围。',
  'HIGH', 'v1.0', 'ACTIVE', JSON_OBJECT('materialTypes', JSON_ARRAY('IMAGE','VIDEO','TEXT','PPT','PDF','WORD'))),

 (9102, 'EVIDENCE_MISSING_01', '引证内容未标明出处', 'EVIDENCE_MISSING',
  '广告使用数据、统计资料、调查结果、文摘、引用语等引证内容的，应当真实、准确，并表明出处；有适用范围和有效期限的，应当明确表示。续航、销量、市占率、检测结论等数值型表述均属引证内容。',
  'MEDIUM', 'v1.0', 'ACTIVE', NULL),

 (9103, 'COMPETITOR_COMPARISON_01', '贬低或不当比较竞品', 'COMPETITOR_COMPARISON',
  '广告不得贬低其他生产经营者的商品或者服务。“同级最优”“碾压”“吊打”等比较性表述，若无客观依据与明确统计口径，存在贬低竞品或不正当竞争风险。',
  'MEDIUM', 'v1.0', 'ACTIVE', NULL),

 (9104, 'MISLEADING_01', '虚假或引人误解', 'MISLEADING',
  '广告不得含有虚假或者引人误解的内容，不得欺骗、误导消费者。以虚假或者引人误解的内容欺骗、误导消费者的，构成虚假广告。',
  'HIGH', 'v1.0', 'ACTIVE', NULL),

 (9105, 'PRICE_CLAIM_01', '价格宣传不规范', 'PRICE_CLAIM',
  '广告中对价格的表示应当准确、清楚、明白。限时优惠、立减、最低价等表述需标明适用范围、期限与依据，不得使消费者产生误解。',
  'MEDIUM', 'v1.0', 'ACTIVE', NULL),

 (9106, 'SAFETY_PROMISE_01', '绝对化安全承诺', 'SAFETY_PROMISE',
  '“100%安全”“零风险”“绝对保障”等表述属于对安全性的绝对化承诺，通常无法验证，易被认定为虚假或引人误解的内容。',
  'HIGH', 'v1.0', 'ACTIVE', NULL),

 (9107, 'DISCLAIMER_MISSING_01', '必要提示或免责声明缺失', 'DISCLAIMER_MISSING',
  '法律、行政法规规定广告中应当明示的内容，应当显著、清晰表示。涉及数据、测试结论、个体差异的表述，应配合必要的说明或免责声明。',
  'LOW', 'v1.0', 'ACTIVE', NULL)
ON DUPLICATE KEY UPDATE description = VALUES(description), severity_default = VALUES(severity_default);

-- 规则 → 法条依据。rule_refs 只能引用这些真实存在的 kb_item_version.id，
-- 从机制上防止模型编造法条（见 docs/03 §5.3）。
INSERT INTO risk_rule_kb_ref (rule_id, kb_item_version_id)
SELECT r.id, v.id FROM risk_rule r, kb_item_version v
 WHERE r.rule_code = 'ABSOLUTE_CLAIM_01' AND v.kb_item_id = 9001 AND v.version_no = 1
ON DUPLICATE KEY UPDATE rule_id = rule_id;

INSERT INTO risk_rule_kb_ref (rule_id, kb_item_version_id)
SELECT r.id, v.id FROM risk_rule r, kb_item_version v
 WHERE r.rule_code = 'EVIDENCE_MISSING_01' AND v.kb_item_id = 9001 AND v.version_no = 1
ON DUPLICATE KEY UPDATE rule_id = rule_id;

INSERT INTO risk_rule_kb_ref (rule_id, kb_item_version_id)
SELECT r.id, v.id FROM risk_rule r, kb_item_version v
 WHERE r.rule_code = 'COMPETITOR_COMPARISON_01' AND v.kb_item_id = 9001 AND v.version_no = 1
ON DUPLICATE KEY UPDATE rule_id = rule_id;

INSERT INTO risk_rule_kb_ref (rule_id, kb_item_version_id)
SELECT r.id, v.id FROM risk_rule r, kb_item_version v
 WHERE r.rule_code = 'MISLEADING_01' AND v.kb_item_id = 9001 AND v.version_no = 1
ON DUPLICATE KEY UPDATE rule_id = rule_id;

INSERT INTO risk_rule_kb_ref (rule_id, kb_item_version_id)
SELECT r.id, v.id FROM risk_rule r, kb_item_version v
 WHERE r.rule_code = 'PRICE_CLAIM_01' AND v.kb_item_id = 9001 AND v.version_no = 1
ON DUPLICATE KEY UPDATE rule_id = rule_id;

INSERT INTO risk_rule_kb_ref (rule_id, kb_item_version_id)
SELECT r.id, v.id FROM risk_rule r, kb_item_version v
 WHERE r.rule_code = 'SAFETY_PROMISE_01' AND v.kb_item_id = 9001 AND v.version_no = 1
ON DUPLICATE KEY UPDATE rule_id = rule_id;

INSERT INTO risk_rule_kb_ref (rule_id, kb_item_version_id)
SELECT r.id, v.id FROM risk_rule r, kb_item_version v
 WHERE r.rule_code = 'DISCLAIMER_MISSING_01' AND v.kb_item_id = 9001 AND v.version_no = 1
ON DUPLICATE KEY UPDATE rule_id = rule_id;

-- 绝对化用语与虚假广告同时关联民法典（诚信原则、缔约过失）
INSERT INTO risk_rule_kb_ref (rule_id, kb_item_version_id)
SELECT r.id, v.id FROM risk_rule r, kb_item_version v
 WHERE r.rule_code IN ('ABSOLUTE_CLAIM_01', 'MISLEADING_01')
   AND v.kb_item_id = 9002 AND v.version_no = 1
ON DUPLICATE KEY UPDATE rule_id = rule_id;
