package build.raft.app

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal data class PlaygroundSample(
    val id: String,
    val title: String,
    val chipLabel: String,
    val defaultPrompt: String,
    val introProse: String,
    val outroProse: String,
    val surfaceId: String,
    val source: String,
    val note: String? = null,
)

internal data class HostilePlaygroundSample(
    val id: String,
    val title: String,
    val expect: String,
    val source: String,
)

internal object SproutPlaygroundCatalog {

    val LUNCH_POLL_SOURCE = """
export default function TeamLunchPoll() {
  const [ramen, setRamen] = useSharedState("ramen", 4);
  const [poke, setPoke] = useSharedState("poke", 3);
  const [tacos, setTacos] = useSharedState("tacos", 2);
  const [hotpot, setHotpot] = useSharedState("hotpot", 5);
  const [dietNote, setDietNote] = useSharedState("dietNote", "");
  const [summary, setSummary] = useSharedState("summary", "");

  const total = ramen + poke + tacos + hotpot;
  const pct = (n) => Math.round((n / Math.max(1, total)) * 100);

  return (
    <Card>
      <CardHeader>
        <Row justify="between" align="center" wrap={true}>
          <Stack gap="xs">
            <CardTitle><Heading level={3}>周五团队午餐投票</Heading></CardTitle>
            <CardDescription>
              <Text size="small" tone="muted">点击任一选项实时计票，多人视角共享同一份投票数据</Text>
            </CardDescription>
          </Stack>
          <Badge variant="primary" appearance="solid">{String(total) + " 票"}</Badge>
        </Row>
      </CardHeader>
      <Divider />
      <CardContent>
        <Stack gap="md">
          <Stack gap="xs">
            <Row justify="between" align="center">
              <Text weight="bold">🍲 潮汕牛肉火锅</Text>
              <Badge variant="success">{String(pct(hotpot)) + "% · " + String(hotpot) + " 票"}</Badge>
            </Row>
            <Progress value={pct(hotpot)} />
            <Row gap="xs">
              <Button size="sm" variant="primary" onClick={() => setHotpot(hotpot + 1)}>投一票 (+1)</Button>
              <Button size="sm" variant="outline" onClick={() => setHotpot(Math.max(0, hotpot - 1))}>撤回 (-1)</Button>
            </Row>
          </Stack>

          <Stack gap="xs">
            <Row justify="between" align="center">
              <Text weight="bold">🍜 豚骨拉面 & 煎饺</Text>
              <Badge variant="primary">{String(pct(ramen)) + "% · " + String(ramen) + " 票"}</Badge>
            </Row>
            <Progress value={pct(ramen)} />
            <Row gap="xs">
              <Button size="sm" variant="primary" onClick={() => setRamen(ramen + 1)}>投一票 (+1)</Button>
              <Button size="sm" variant="outline" onClick={() => setRamen(Math.max(0, ramen - 1))}>撤回 (-1)</Button>
            </Row>
          </Stack>

          <Stack gap="xs">
            <Row justify="between" align="center">
              <Text weight="bold">🥗 夏威夷三文鱼波奇饭</Text>
              <Badge variant="muted">{String(pct(poke)) + "% · " + String(poke) + " 票"}</Badge>
            </Row>
            <Progress value={pct(poke)} />
            <Row gap="xs">
              <Button size="sm" variant="primary" onClick={() => setPoke(poke + 1)}>投一票 (+1)</Button>
              <Button size="sm" variant="outline" onClick={() => setPoke(Math.max(0, poke - 1))}>撤回 (-1)</Button>
            </Row>
          </Stack>

          <Stack gap="xs">
            <Row justify="between" align="center">
              <Text weight="bold">🌮 炭烤牛排塔可拼盘</Text>
              <Badge variant="muted">{String(pct(tacos)) + "% · " + String(tacos) + " 票"}</Badge>
            </Row>
            <Progress value={pct(tacos)} />
            <Row gap="xs">
              <Button size="sm" variant="primary" onClick={() => setTacos(tacos + 1)}>投一票 (+1)</Button>
              <Button size="sm" variant="outline" onClick={() => setTacos(Math.max(0, tacos - 1))}>撤回 (-1)</Button>
            </Row>
          </Stack>

          <Input
            label="忌口或加菜备注"
            placeholder="例如：不吃香菜、两份微辣..."
            value={dietNote}
            onChange={(e) => setDietNote(e.value)}
          />

          {summary ? (
            <Card>
              <CardContent>
                <Stack gap="xs">
                  <Badge variant="information" appearance="soft">Agent 实时汇总结论</Badge>
                  <Text size="small">{summary}</Text>
                </Stack>
              </CardContent>
            </Card>
          ) : null}

          <Row justify="between" align="center" wrap={true}>
            <Text size="small" tone="muted">点击右侧按钮唤醒 Agent 读取票数并敲定餐厅</Text>
            <Button variant="primary" size="md" onClick={() => raft.reply("summarize")}>
              让 Agent 总结订餐方案
            </Button>
          </Row>
        </Stack>
      </CardContent>
    </Card>
  );
}
""".trimIndent()

    val RELEASE_CHECKLIST_SOURCE = """
export default function ReleaseChecklistCard() {
  const [unitGreen, setUnitGreen] = useSharedState("unitGreen", true);
  const [dbMigrated, setDbMigrated] = useSharedState("dbMigrated", true);
  const [canaryVerified, setCanaryVerified] = useSharedState("canaryVerified", false);
  const [rollbackReady, setRollbackReady] = useSharedState("rollbackReady", false);
  const [flagGated, setFlagGated] = useSharedState("flagGated", true);
  const [releaseOwner, setReleaseOwner] = useSharedState("releaseOwner", "@Artea");
  const [summary, setSummary] = useSharedState("summary", "");

  const items = [unitGreen, dbMigrated, canaryVerified, rollbackReady, flagGated];
  const doneCount = items.filter(Boolean).length;
  const pct = Math.round((doneCount / items.length) * 100);

  return (
    <Card>
      <CardHeader>
        <Row justify="between" align="center" wrap={true}>
          <Stack gap="xs">
            <CardTitle><Heading level={3}>上线前发布检查清单</Heading></CardTitle>
            <CardDescription>
              <Text size="small" tone="muted">{"负责人 " + (releaseOwner || "未指定") + " · 已完成 " + String(doneCount) + "/5 项"}</Text>
            </CardDescription>
          </Stack>
          <Badge variant={doneCount === 5 ? "success" : "warning"} appearance="solid">
            {doneCount === 5 ? "可正式放量" : ("进度 " + String(pct) + "%")}
          </Badge>
        </Row>
      </CardHeader>
      <Divider />
      <CardContent>
        <Stack gap="md">
          <Progress value={pct} />

          <Checkbox
            label="1. 全仓单元测试与双端协议向量全绿"
            checked={unitGreen}
            onCheckedChange={(v) => setUnitGreen(v)}
          />
          <Checkbox
            label="2. 预发环境数据库迁移与权限边界已核验"
            checked={dbMigrated}
            onCheckedChange={(v) => setDbMigrated(v)}
          />
          <Checkbox
            label="3. Feature Flag 默认关闭并配置内部金丝雀白名单"
            checked={flagGated}
            onCheckedChange={(v) => setFlagGated(v)}
          />
          <Checkbox
            label="4. 安卓 ARM64 真机与 Web 双端回归通过"
            checked={canaryVerified}
            onCheckedChange={(v) => setCanaryVerified(v)}
          />
          <Checkbox
            label="5. 紧急回滚预案与值班告警已就绪"
            checked={rollbackReady}
            onCheckedChange={(v) => setRollbackReady(v)}
          />

          <Input
            label="值班发布负责人"
            placeholder="@handle"
            value={releaseOwner}
            onChange={(e) => setReleaseOwner(e.value)}
          />

          {summary ? (
            <Card>
              <CardContent>
                <Stack gap="xs">
                  <Badge variant={doneCount === 5 ? "success" : "warning"} appearance="soft">Agent 发布评估回写</Badge>
                  <Text size="small">{summary}</Text>
                </Stack>
              </CardContent>
            </Card>
          ) : null}

          <Row justify="end" gap="sm">
            <Button variant="primary" size="md" onClick={() => raft.reply("summarize")}>
              让 Agent 总结就绪状态
            </Button>
          </Row>
        </Stack>
      </CardContent>
    </Card>
  );
}
""".trimIndent()

    val TRIP_PACKING_SOURCE = """
export default function TripPackingCard() {
  const [passport, setPassport] = useSharedState("passport", true);
  const [charger, setCharger] = useSharedState("charger", true);
  const [adapter, setAdapter] = useSharedState("adapter", false);
  const [jacket, setJacket] = useSharedState("jacket", false);
  const [meds, setMeds] = useSharedState("meds", false);
  const [city, setCity] = useSharedState("city", "东京 · 5 天");
  const [summary, setSummary] = useSharedState("summary", "");

  const packed = [passport, charger, adapter, jacket, meds].filter(Boolean).length;

  return (
    <Card>
      <CardHeader>
        <Row justify="between" align="center" wrap={true}>
          <Stack gap="xs">
            <CardTitle><Heading level={3}>出行打包行李单</Heading></CardTitle>
            <CardDescription><Text size="small" tone="muted">{"目的地：" + city}</Text></CardDescription>
          </Stack>
          <Badge variant="primary">{String(packed) + "/5 已装包"}</Badge>
        </Row>
      </CardHeader>
      <Divider />
      <CardContent>
        <Stack gap="sm">
          <Input
            label="目的地与天数"
            value={city}
            onChange={(e) => setCity(e.value)}
          />
          <Checkbox label="护照 / 身份证与签注" checked={passport} onCheckedChange={(v) => setPassport(v)} />
          <Checkbox label="手机与笔记本快充线、充电宝" checked={charger} onCheckedChange={(v) => setCharger(v)} />
          <Checkbox label="多国转换插头" checked={adapter} onCheckedChange={(v) => setAdapter(v)} />
          <Checkbox label="防风外套与折叠雨伞" checked={jacket} onCheckedChange={(v) => setJacket(v)} />
          <Checkbox label="随身常备药与创可贴" checked={meds} onCheckedChange={(v) => setMeds(v)} />

          {summary ? (
            <Card>
              <CardContent>
                <Stack gap="xs">
                  <Badge variant="information" appearance="soft">Agent 查漏补缺建议</Badge>
                  <Text size="small">{summary}</Text>
                </Stack>
              </CardContent>
            </Card>
          ) : null}

          <Button variant="primary" onClick={() => raft.reply("summarize")}>
            看看还漏了什么？
          </Button>
        </Stack>
      </CardContent>
    </Card>
  );
}
""".trimIndent()

    val EXPENSE_SPLITTER_SOURCE = """
export default function DinnerSplitterCard() {
  const [subtotal, setSubtotal] = useSharedState("subtotal", 680);
  const [servicePct, setServicePct] = useSharedState("servicePct", 10);
  const [people, setPeople] = useSharedState("people", 4);
  const [paidCount, setPaidCount] = useSharedState("paidCount", 1);
  const [payer, setPayer] = useSharedState("payer", "@Artea");
  const [summary, setSummary] = useSharedState("summary", "");

  const safePeople = Math.max(1, people);
  const grandTotal = Math.round(subtotal * (1 + servicePct / 100));
  const perPerson = Math.round((grandTotal / safePeople) * 100) / 100;
  const paidClamped = Math.min(safePeople, Math.max(0, paidCount));

  return (
    <Card>
      <CardHeader>
        <Row justify="between" align="center" wrap={true}>
          <Stack gap="xs">
            <CardTitle><Heading level={3}>聚餐 AA 分账计算器</Heading></CardTitle>
            <CardDescription>
              <Text size="small" tone="muted">{"垫付人 " + payer + " · 含 " + String(servicePct) + "% 服务费"}</Text>
            </CardDescription>
          </Stack>
          <Badge variant="success" appearance="solid">{"人均 ¥" + String(perPerson)}</Badge>
        </Row>
      </CardHeader>
      <Divider />
      <CardContent>
        <Stack gap="md">
          <Row justify="between" align="center" wrap={true}>
            <Text weight="bold">{"账单原价：¥" + String(subtotal)}</Text>
            <Row gap="xs">
              <Button size="sm" variant="outline" onClick={() => setSubtotal(Math.max(0, subtotal - 50))}>-¥50</Button>
              <Button size="sm" variant="outline" onClick={() => setSubtotal(subtotal + 50)}>+¥50</Button>
              <Button size="sm" variant="outline" onClick={() => setSubtotal(subtotal + 200)}>+¥200</Button>
            </Row>
          </Row>

          <Row justify="between" align="center" wrap={true}>
            <Text weight="bold">{"分摊人数：" + String(safePeople) + " 人"}</Text>
            <Row gap="xs">
              <Button size="sm" variant="outline" onClick={() => setPeople(Math.max(1, safePeople - 1))}>-1 人</Button>
              <Button size="sm" variant="outline" onClick={() => setPeople(safePeople + 1)}>+1 人</Button>
            </Row>
          </Row>

          <Row justify="between" align="center" wrap={true}>
            <Text weight="bold">{"服务费比例：" + String(servicePct) + "%"}</Text>
            <Row gap="xs">
              <Button size="sm" variant={servicePct === 0 ? "primary" : "outline"} onClick={() => setServicePct(0)}>0%</Button>
              <Button size="sm" variant={servicePct === 10 ? "primary" : "outline"} onClick={() => setServicePct(10)}>10%</Button>
              <Button size="sm" variant={servicePct === 15 ? "primary" : "outline"} onClick={() => setServicePct(15)}>15%</Button>
            </Row>
          </Row>

          <Stack gap="xs">
            <Row justify="between" align="center">
              <Text size="small" tone="muted">{"收款进度：" + String(paidClamped) + "/" + String(safePeople) + " 人已转账（总计 ¥" + String(grandTotal) + "）"}</Text>
              <Button size="sm" variant="primary" onClick={() => setPaidCount(Math.min(safePeople, paidClamped + 1))}>记一笔已收 (+1)</Button>
            </Row>
            <Progress value={Math.round((paidClamped / safePeople) * 100)} />
          </Stack>

          {summary ? (
            <Card>
              <CardContent>
                <Stack gap="xs">
                  <Badge variant="success" appearance="soft">Agent 生成的群收款文案</Badge>
                  <Text size="small">{summary}</Text>
                </Stack>
              </CardContent>
            </Card>
          ) : null}

          <Button variant="primary" onClick={() => raft.reply("summarize")}>
            让 Agent 生成群收款明细
          </Button>
        </Stack>
      </CardContent>
    </Card>
  );
}
""".trimIndent()

    val SVG_OPS_MONITOR_SOURCE = """
export default function NativeSvgOpsCard() {
  const [tab, setTab] = useSharedState("tab", "vector");
  const [svgHit, setSvgHit] = useSharedState("svgHit", "点击图形任意区域测试命中");
  const [boost, setBoost] = useSharedState("boost", 1);
  const [autoHeal, setAutoHeal] = useSharedState("autoHeal", true);
  const [note, setNote] = useSharedState("note", "");
  const [summary, setSummary] = useSharedState("summary", "");

  return (
    <Card>
      <CardHeader>
        <Row justify="between" align="center" wrap={true}>
          <Row gap="sm" align="center">
            <Avatar name="Sprout" size="md" type="agent" />
            <Stack gap="xs">
              <CardTitle><Heading level={3}>原生矢量渐变与控件面板</Heading></CardTitle>
              <CardDescription>
                <Text size="small" tone="muted">{"SVG <defs> 渐变 + Skia 亮度遮罩 + 几何变换触控"}</Text>
              </CardDescription>
            </Stack>
          </Row>
          <Badge variant="primary" appearance="solid">ARM64 QuickJS</Badge>
        </Row>
      </CardHeader>
      <Divider />
      <CardContent>
        <Stack gap="md">
          <Tabs value={tab} onValueChange={(v) => setTab(v)}>
            <TabsList variant="underline">
              <TabsTab value="vector">SVG 渐变与遮罩</TabsTab>
              <TabsTab value="controls">旋转按钮与输入法</TabsTab>
            </TabsList>

            <TabsPanel value="vector">
              <Stack gap="sm">
                <Text size="small" tone="muted">点按左侧空心渐变环（点圆心空白会穿透到底板）、中间径向圆或右侧遮罩椭圆：</Text>
                <svg width={260} height={120} viewBox="0 0 260 120">
                  <defs>
                    <linearGradient id="ringGrad" x1="0%" y1="0%" x2="100%" y2="100%">
                      <stop offset="0%" stopColor="#2563eb" />
                      <stop offset="100%" stopColor="#7c3aed" />
                    </linearGradient>
                    <radialGradient id="radBadge" cx="50%" cy="50%" r="50%">
                      <stop offset={0} stopColor="#fde047" />
                      <stop offset={1} stopColor="#ea580c" />
                    </radialGradient>
                    <mask id="fadeMask" x={0} y={0} width={260} height={120}>
                      <rect x={0} y={0} width={260} height={120} fill="#ffffff" />
                      <circle cx={205} cy={60} r={16} fill="#222222" />
                    </mask>
                  </defs>
                  <rect x={4} y={4} width={252} height={112} rx={10} fill="#f8fafc" stroke="#cbd5e1" onClick={() => setSvgHit("底板背景 (backdrop-hole)")} />
                  <path
                    d="M 62 16 A 44 44 0 1 0 62 104 A 44 44 0 1 0 62 16 Z M 62 38 A 22 22 0 1 1 62 82 A 22 22 0 1 1 62 38 Z"
                    fill="url(#ringGrad)"
                    fillRule="evenodd"
                    onClick={() => setSvgHit("线性渐变空心圆环 (hollow-ring)")}
                  />
                  <circle cx={136} cy={60} r={22} fill="url(#radBadge)" onClick={() => setSvgHit("径向渐变徽章 (radial-circle)")} />
                  <ellipse cx={205} cy={60} rx={34} ry={28} fill="#059669" mask="url(#fadeMask)" onClick={() => setSvgHit("亮度遮罩椭圆 (masked-ellipse)")} />
                </svg>
                <Badge variant="success">{"最近命中节点：" + svgHit}</Badge>
              </Stack>
            </TabsPanel>

            <TabsPanel value="controls">
              <Stack gap="sm">
                <Box transform="translateX(6px) rotate(-2deg)">
                  <Button variant="primary" onClick={() => setBoost(boost + 1)}>
                    {"带旋转矩阵的按钮 (+1)：" + String(boost)}
                  </Button>
                </Box>
                <Input
                  label="中文输入法预编辑抗重绘测试"
                  placeholder="支持拼音组词期间并发状态刷新..."
                  value={note}
                  onChange={(e) => setNote(e.value)}
                />
                <Switch
                  label="启用节点故障自动摘除"
                  checked={autoHeal}
                  onCheckedChange={(v) => setAutoHeal(v)}
                />
              </Stack>
            </TabsPanel>
          </Tabs>

          {summary ? (
            <Card>
              <CardContent>
                <Stack gap="xs">
                  <Badge variant="information" appearance="soft">Agent 巡检摘要</Badge>
                  <Text size="small">{summary}</Text>
                </Stack>
              </CardContent>
            </Card>
          ) : null}

          <Button variant="outline" size="sm" onClick={() => raft.reply("summarize")}>
            让 Agent 记录当前面板状态
          </Button>
        </Stack>
      </CardContent>
    </Card>
  );
}
""".trimIndent()

    val LIVE_CLOCK_SOURCE = """
export default function OrbitClockCard() {
  const [ticks, setTicks] = useState(0);
  const [phase, setPhase] = useState(0);
  const [paused, setPaused] = useState(false);

  useEffect(() => {
    if (paused) return;
    const id = setInterval(() => setTicks((n) => n + 1), 1000);
    return () => clearInterval(id);
  }, [paused]);

  useEffect(() => {
    if (paused) return;
    let id = requestAnimationFrame(function step(now) {
      setPhase(now / 1000);
      id = requestAnimationFrame(step);
    });
    return () => cancelAnimationFrame(id);
  }, [paused]);

  const cx = 130 + Math.cos(phase * 2.2) * 84;
  const cy = 54 + Math.sin(phase * 2.2) * 32;

  return (
    <Card>
      <CardHeader>
        <Row justify="between" align="center">
          <CardTitle><Heading level={3}>沙箱定时器与 60fps 帧动画</Heading></CardTitle>
          <Badge variant={paused ? "warning" : "success"}>{paused ? "已暂停" : ("运行 " + String(ticks) + "s")}</Badge>
        </Row>
      </CardHeader>
      <Divider />
      <CardContent>
        <Stack gap="sm">
          <Text size="small" tone="muted">{"setInterval 计数：" + String(ticks) + " 秒 · rAF 相位：" + phase.toFixed(1) + "s（退后台自动冻结时钟）"}</Text>
          <svg viewBox="0 0 260 108" width={260} height={108}>
            <defs>
              <radialGradient id="orbGlow" cx="50%" cy="50%" r="50%">
                <stop offset="0%" stopColor="#38bdf8" />
                <stop offset="100%" stopColor="#1d4ed8" />
              </radialGradient>
            </defs>
            <rect x={2} y={2} width={256} height={104} rx={12} fill="#0f172a" />
            <ellipse cx={130} cy={54} rx={84} ry={32} fill="none" stroke="#334155" strokeWidth={1.5} />
            <circle cx={cx} cy={cy} r={10} fill="url(#orbGlow)" />
          </svg>
          <Button size="sm" variant="outline" onClick={() => setPaused(!paused)}>
            {paused ? "恢复帧动画" : "暂停帧动画"}
          </Button>
        </Stack>
      </CardContent>
    </Card>
  );
}
""".trimIndent()

    val SAMPLES: List<PlaygroundSample> = listOf(
        PlaygroundSample(
            id = "lunch-poll",
            title = "周五团队午餐投票",
            chipLabel = "🗳 团队午餐投票",
            defaultPrompt = "帮我做一个周五团队午餐投票，支持实时计票、写忌口备注，并能让你总结订餐方案",
            introProse = "好的！给你生成了一个多人实时同步的团队午餐投票面板，大家可以直接点按投票或填写忌口备注：",
            outroProse = "等大家投完后，直接点卡片右下角的「让 Agent 总结订餐方案」，我会读取实时票数并把结论写回卡片。",
            surfaceId = "lunch-poll",
            source = LUNCH_POLL_SOURCE,
            note = "点击「让 Agent 总结订餐方案」会触发 raft.reply('summarize')，Agent 读取当前票数后回写 summary 字段并回复消息。",
        ),
        PlaygroundSample(
            id = "release-checklist",
            title = "上线前发布检查清单",
            chipLabel = "✅ 发布检查清单",
            defaultPrompt = "做一个版本上线检查清单，我可以逐项打勾，点按钮让你评估能不能正式放量",
            introProse = "没问题，这是为你准备的上线前检查清单（勾选状态在所有查看者之间实时同步）：",
            outroProse = "勾选完成后点「让 Agent 总结就绪状态」，我会核对还有哪些卡点。",
            surfaceId = "release-checklist",
            source = RELEASE_CHECKLIST_SOURCE,
        ),
        PlaygroundSample(
            id = "trip-packing",
            title = "旅行打包行李单（查漏补缺）",
            chipLabel = "🧳 旅行打包查漏",
            defaultPrompt = "做一个去东京 5 天的旅行打包清单，带一个“看看还漏了什么”的按钮",
            introProse = "已经按 5 天行程整理好随身必备清单，先把已经装进箱子的物品勾上吧：",
            outroProse = "随时点底部按钮，我根据你还没勾的项提醒出发前容易忘带的东西。",
            surfaceId = "trip-packing",
            source = TRIP_PACKING_SOURCE,
        ),
        PlaygroundSample(
            id = "expense-splitter",
            title = "四人聚餐 AA 分账器",
            chipLabel = "🧾 四人聚餐分账",
            defaultPrompt = "做一个四人晚餐 AA 分账计算器，可以调金额、人数、服务费和收款进度",
            introProse = "分账计算器做好了，支持动态调整总金额、服务费比例与人数，并追踪已收款人数：",
            outroProse = "点「让 Agent 生成群收款明细」即可一键生成发群里的收款通知。",
            surfaceId = "expense-splitter",
            source = EXPENSE_SPLITTER_SOURCE,
        ),
        PlaygroundSample(
            id = "svg-ops-monitor",
            title = "原生 SVG 渐变环与旋转控件",
            chipLabel = "📊 渐变与遮罩面板",
            defaultPrompt = "展示一个包含 SVG <defs> 线性/径向渐变空心环、Skia 亮度遮罩和旋转按钮的原生面板",
            introProse = "这是覆盖 SVG 线性/径向渐变、Skia 亮度转透明度遮罩与 CSS 变换矩阵命中测试的综合原生面板：",
            outroProse = "可切换上方页签分别体验矢量图形节点级点击命中与中文输入法抗重绘。",
            surfaceId = "svg-ops-monitor",
            source = SVG_OPS_MONITOR_SOURCE,
        ),
        PlaygroundSample(
            id = "live-clock",
            title = "沙箱定时器与 60fps 轨道动画",
            chipLabel = "⏱ 动态轨道动画",
            defaultPrompt = "做一个用 setInterval 和 requestAnimationFrame 驱动的实时轨道动画卡片",
            introProse = "已在独立 QuickJS 沙箱中启动定时器与 60fps 帧动画轨道面板：",
            outroProse = "当卡片滚出可视区或应用退到后台时，宿主会自动挂起沙箱逻辑时钟，不浪费电量。",
            surfaceId = "live-clock",
            source = LIVE_CLOCK_SOURCE,
        ),
    )

    val HOSTILE_SAMPLES: List<HostilePlaygroundSample> = listOf(
        HostilePlaygroundSample(
            id = "loop-render",
            title = "1. 渲染死循环 (while(true))",
            expect = "超过沙箱单次调用时间预算后立即触发 INTERRUPTED 中断，标记为只读无效帧，宿主聊天页与其它界面完全不卡顿。",
            source = """
export default function InfiniteRenderLoop() {
  while (true) {}
  return <Text>永远不会执行到这里</Text>;
}
""".trimIndent(),
        ),
        HostilePlaygroundSample(
            id = "loop-handler",
            title = "2. 点击回调死循环（邻居按钮照常存活）",
            expect = "界面正常渲染；点红色「触发死循环」会被沙箱超时中断 (INTERRUPTED)，紧接着点右侧「我依然存活」依然流畅累加。",
            source = """
export default function HandlerLoop() {
  const [aliveCount, setAliveCount] = useState(0);
  return (
    <Card>
      <CardContent>
        <Stack gap="sm">
          <Text size="small" tone="muted">先点红色死循环按钮，再点蓝色正常按钮验证沙箱恢复：</Text>
          <Row gap="sm" wrap={true}>
            <Button variant="danger" onClick={() => { while (true) {} }}>触发死循环</Button>
            <Button variant="primary" onClick={() => setAliveCount(aliveCount + 1)}>
              {"我依然存活: " + String(aliveCount)}
            </Button>
          </Row>
        </Stack>
      </CardContent>
    </Card>
  );
}
""".trimIndent(),
        ),
        HostilePlaygroundSample(
            id = "huge-tree",
            title = "3. 节点洪泛攻击 (一次请求 20,000 个节点)",
            expect = "被沙箱与协议校验器以 TREE_TOO_LARGE 拦截（上限 5,000 个节点），不会撑爆客户端内存。",
            source = """
export default function HugeTreeAttack() {
  return (
    <Stack>
      {Array.from({ length: 20000 }, (_, i) => <Text key={i}>{"节点 #" + String(i)}</Text>)}
    </Stack>
  );
}
""".trimIndent(),
        ),
        HostilePlaygroundSample(
            id = "deep-tree",
            title = "4. 超深递归嵌套 (100 层 Box)",
            expect = "被协议校验器以 TREE_TOO_DEEP 拦截（上限 40 层），防止 Compose 测量栈溢出。",
            source = """
const Nest = ({ n }) => (n === 0 ? <Text>最底层</Text> : <Box><Nest n={n - 1} /></Box>);
export default function DeepTreeAttack() {
  return <Nest n={100} />;
}
""".trimIndent(),
        ),
        HostilePlaygroundSample(
            id = "nested-dispatch",
            title = "5. 篡改内置原型 + 伪造 __dispatch 重入与定时器写状态",
            expect = "点击「伪造重入」尝试在回调内直接调宿主 __dispatch 会被拒绝（EFFECT_SUPPRESSED）；50ms 后台定时器尝试偷改共享状态也会因缺少真实用户手势令牌被静默回滚丢弃。",
            source = """
export default function HostileReentrancy() {
  const [n, setN] = useSharedState("n", 0);
  const [timerStatus, setTimerStatus] = useState("定时器：等待触发");
  const [nestStatus, setNestStatus] = useState("重入攻击：尚未尝试");

  useEffect(() => {
    const id = setTimeout(() => {
      try {
        __dispatch(0, "[]");
        setTimerStatus("定时器：竟然执行成功？!");
      } catch (e) {
        setTimerStatus("定时器：__dispatch 已被沙箱拒绝");
      }
      setN(999);
    }, 60);
    return () => clearTimeout(id);
  }, []);

  return (
    <Card>
      <CardContent>
        <Stack gap="sm">
          <Row gap="sm" wrap={true}>
            <Button variant="primary" onClick={() => setN(n + 1)}>{"真实点击 (+1): " + String(n)}</Button>
            <Button
              variant="danger"
              onClick={() => {
                try {
                  __dispatch(0, "[]");
                  setNestStatus("重入攻击：未拦截？!");
                } catch (e) {
                  setNestStatus("重入攻击：已被沙箱当场拦截");
                }
              }}
            >
              伪造 __dispatch 重入
            </Button>
          </Row>
          <Text size="small">{timerStatus + " · " + nestStatus}</Text>
        </Stack>
      </CardContent>
    </Card>
  );
}
""".trimIndent(),
        ),
    )

    fun matchOrSynthesize(
        prompt: String,
        targetSurfaceId: String? = null,
        existingSource: String? = null,
    ): PlaygroundSample {
        val q = prompt.trim().lowercase()
        fun sanitizeForJsx(raw: String, maxLen: Int): String =
            raw.trim()
                .replace("\\", "")
                .replace("\"", "'")
                .replace("{", "(")
                .replace("}", ")")
                .replace("<", "‹")
                .replace(">", "›")
                .replace("\n", " ")
                .take(maxLen)

        if (targetSurfaceId != null && existingSource != null) {
            val safeNoteTitle = sanitizeForJsx(prompt, 36).ifBlank { "补充调整" }
            val revised = buildRevisedSource(existingSource, safeNoteTitle)
            return PlaygroundSample(
                id = "revised-$targetSurfaceId",
                title = "已按要求原地更新界面",
                chipLabel = "修订",
                defaultPrompt = prompt,
                introProse = "已按你的要求「${prompt.trim()}」原地升级了原界面（已填写的共享状态完整保留）：",
                outroProse = "你可以在上方原消息里直接看到新增控件，之前勾选或输入的数据都没有丢失。",
                surfaceId = targetSurfaceId,
                source = revised,
            )
        }

        val matched = when {
            "投票" in q || "午餐" in q || "吃什么" in q || "poll" in q -> SAMPLES[0]
            "发布" in q || "上线" in q || "checklist" in q || "检查" in q -> SAMPLES[1]
            "旅行" in q || "打包" in q || "行李" in q || "漏" in q || "pack" in q || "trip" in q -> SAMPLES[2]
            "分账" in q || "聚餐" in q || "aa" in q || "钱" in q || "账单" in q || "split" in q -> SAMPLES[3]
            "svg" in q || "渐变" in q || "遮罩" in q || "监控" in q || "圆环" in q -> SAMPLES[4]
            "时钟" in q || "动画" in q || "轨道" in q || "计时" in q || "clock" in q -> SAMPLES[5]
            else -> null
        }
        if (matched != null) return matched

        val cleanTitle = sanitizeForJsx(prompt, 28).ifBlank { "自定义交互卡片" }
        val customId = "custom-" + (kotlin.math.abs(prompt.hashCode()) % 10000)
        val customSource = """
export default function CustomGeneratedSurface() {
  const [stepA, setStepA] = useSharedState("stepA", true);
  const [stepB, setStepB] = useSharedState("stepB", false);
  const [stepC, setStepC] = useSharedState("stepC", false);
  const [priority, setPriority] = useSharedState("priority", "high");
  const [comment, setComment] = useSharedState("comment", "");
  const [score, setScore] = useSharedState("score", 3);
  const [summary, setSummary] = useSharedState("summary", "");

  const doneCount = [stepA, stepB, stepC].filter(Boolean).length;

  return (
    <Card>
      <CardHeader>
        <Row justify="between" align="center" wrap={true}>
          <Stack gap="xs">
            <CardTitle><Heading level={3}>$cleanTitle</Heading></CardTitle>
            <CardDescription><Text size="small" tone="muted">实时生成的原生交互卡片（多人共享状态）</Text></CardDescription>
          </Stack>
          <Badge variant="primary" appearance="solid">{"优先级: " + priority.toUpperCase()}</Badge>
        </Row>
      </CardHeader>
      <Divider />
      <CardContent>
        <Stack gap="md">
          <Row gap="xs" wrap={true}>
            <Button size="sm" variant={priority === "low" ? "primary" : "outline"} onClick={() => setPriority("low")}>低优</Button>
            <Button size="sm" variant={priority === "medium" ? "primary" : "outline"} onClick={() => setPriority("medium")}>常规</Button>
            <Button size="sm" variant={priority === "high" ? "primary" : "outline"} onClick={() => setPriority("high")}>高优推进</Button>
          </Row>

          <Progress value={Math.round((doneCount / 3) * 100)} />
          <Checkbox label="核心需求范围已确认" checked={stepA} onCheckedChange={(v) => setStepA(v)} />
          <Checkbox label="关键交互细节已走查" checked={stepB} onCheckedChange={(v) => setStepB(v)} />
          <Checkbox label="验收结果已同步团队" checked={stepC} onCheckedChange={(v) => setStepC(v)} />

          <Row justify="between" align="center">
            <Text size="small" weight="bold">{"评分指数：" + String(score) + " / 5"}</Text>
            <Row gap="xs">
              <Button size="sm" variant="outline" onClick={() => setScore(Math.max(1, score - 1))}>-1</Button>
              <Button size="sm" variant="outline" onClick={() => setScore(Math.min(5, score + 1))}>+1</Button>
            </Row>
          </Row>

          <Input
            label="补充备注"
            placeholder="输入你的想法，所有查看者实时可见..."
            value={comment}
            onChange={(e) => setComment(e.value)}
          />

          {summary ? (
            <Card>
              <CardContent>
                <Stack gap="xs">
                  <Badge variant="information" appearance="soft">Agent 跟进反馈</Badge>
                  <Text size="small">{summary}</Text>
                </Stack>
              </CardContent>
            </Card>
          ) : null}

          <Button variant="primary" onClick={() => raft.reply("summarize")}>
            提交给 Agent 汇总
          </Button>
        </Stack>
      </CardContent>
    </Card>
  );
}
""".trimIndent()

        return PlaygroundSample(
            id = customId,
            title = cleanTitle,
            chipLabel = cleanTitle,
            defaultPrompt = prompt,
            introProse = "已根据你的描述「${prompt.trim()}」生成可交互原生界面：",
            outroProse = "你可以在卡片里直接勾选、调优先级或输入备注，也可以点卡片下方的「修改这个界面」继续追加功能。",
            surfaceId = customId,
            source = customSource,
        )
    }

    private fun buildRevisedSource(existingSource: String, userInstruction: String): String {
        val anchor = "</CardContent>"
        val idx = existingSource.lastIndexOf(anchor)
        if (idx < 0 || "revisionExtraNote" in existingSource) {
            return """
export default function RevisedSurfaceCard() {
  const [urgent, setUrgent] = useSharedState("revisionUrgent", true);
  const [extraNote, setExtraNote] = useSharedState("revisionExtraNote", "");
  const [summary, setSummary] = useSharedState("summary", "");
  return (
    <Card>
      <CardHeader>
        <Row justify="between" align="center">
          <CardTitle><Heading level={3}>已更新界面：$userInstruction</Heading></CardTitle>
          <Badge variant="success">v2 原地修订</Badge>
        </Row>
      </CardHeader>
      <Divider />
      <CardContent>
        <Stack gap="sm">
          <Switch label="加急优先处理" checked={urgent} onCheckedChange={(v) => setUrgent(v)} />
          <Input label="修订补充说明" placeholder="保留原有共享状态..." value={extraNote} onChange={(e) => setExtraNote(e.value)} />
          {summary ? (
            <Card>
              <CardContent>
                <Stack gap="xs">
                  <Badge variant="information" appearance="soft">Agent 总结</Badge>
                  <Text size="small">{summary}</Text>
                </Stack>
              </CardContent>
            </Card>
          ) : null}
          <Button variant="primary" onClick={() => raft.reply("summarize")}>让 Agent 重新总结</Button>
        </Stack>
      </CardContent>
    </Card>
  );
}
""".trimIndent()
        }
        val stateHookInjection = """
  const [revisionUrgent, setRevisionUrgent] = useSharedState("revisionUrgent", false);
  const [revisionExtraNote, setRevisionExtraNote] = useSharedState("revisionExtraNote", "");
"""
        val firstBrace = existingSource.indexOf('{')
        val withHooks = if (firstBrace > 0) {
            existingSource.substring(0, firstBrace + 1) + stateHookInjection + existingSource.substring(firstBrace + 1)
        } else {
            existingSource
        }
        val updatedIdx = withHooks.lastIndexOf(anchor)
        val extraUi = """
          <Divider />
          <Card>
            <CardContent>
              <Stack gap="xs">
                <Badge variant="success" appearance="soft">{"已按指令原地修订：" + "$userInstruction"}</Badge>
                <Switch
                  label="标记为高优跟进项（新增控件，原状态完整保留）"
                  checked={revisionUrgent}
                  onCheckedChange={(v) => setRevisionUrgent(v)}
                />
                <Input
                  label="追加修订备注"
                  placeholder="在此输入补充要求..."
                  value={revisionExtraNote}
                  onChange={(e) => setRevisionExtraNote(e.value)}
                />
              </Stack>
            </CardContent>
          </Card>
        """.trimIndent()
        val lastStackClose = withHooks.lastIndexOf("</Stack>", updatedIdx)
        return if (lastStackClose > 0) {
            withHooks.substring(0, lastStackClose) + "\n" + extraUi + "\n" + withHooks.substring(lastStackClose)
        } else {
            withHooks
        }
    }

    fun summarizeSharedState(
        surfaceId: String,
        actionName: String,
        payload: JsonElement,
        currentState: JsonObject,
    ): Pair<String, JsonObject> {
        fun intVal(k: String, def: Int = 0): Int = currentState[k]?.jsonPrimitive?.intOrNull ?: def
        fun boolVal(k: String, def: Boolean = false): Boolean = currentState[k]?.jsonPrimitive?.booleanOrNull ?: def
        fun strVal(k: String, def: String = ""): String = currentState[k]?.jsonPrimitive?.content ?: def

        val replyCmd = if (actionName == "reply") {
            (payload as? JsonObject)?.get("text")?.jsonPrimitive?.content?.trim()
                ?: (payload as? JsonPrimitive)?.content?.trim()
                ?: ""
        } else {
            ""
        }

        val summaryText = when {
            actionName == "reply" && replyCmd.isNotEmpty() && replyCmd != "summarize" -> {
                "已收到来自界面的指令：「$replyCmd」。"
            }
            "hotpot" in currentState || "ramen" in currentState || surfaceId.contains("lunch") -> {
                val options = listOf(
                    "潮汕牛肉火锅" to intVal("hotpot", 5),
                    "豚骨拉面" to intVal("ramen", 4),
                    "三文鱼波奇饭" to intVal("poke", 3),
                    "炭烤牛排塔可" to intVal("tacos", 2),
                ).sortedByDescending { it.second }
                val total = options.sumOf { it.second }
                val top = options.first()
                val note = strVal("dietNote").trim()
                val notePart = if (note.isNotEmpty()) "；已记下忌口备注「$note」" else ""
                "共收到 $total 票，「${top.first}」以 ${top.second} 票领先（其次是 ${options[1].first} ${options[1].second} 票）$notePart。建议直接预订 ${top.first}！"
            }
            "unitGreen" in currentState || surfaceId.contains("release") -> {
                val checks = listOf(
                    "全仓单测与协议向量" to boolVal("unitGreen", true),
                    "预发迁移与权限边界" to boolVal("dbMigrated", true),
                    "Feature Flag 白名单门控" to boolVal("flagGated", true),
                    "双端真机金丝雀回归" to boolVal("canaryVerified", false),
                    "紧急回滚预案" to boolVal("rollbackReady", false),
                )
                val missing = checks.filterNot { it.second }.map { it.first }
                val owner = strVal("releaseOwner", "@Artea")
                if (missing.isEmpty()) {
                    "5/5 项检查全部通过！负责人 $owner 可以按计划推进灰度放量。"
                } else {
                    "当前已完成 ${5 - missing.size}/5 项，尚缺：${missing.joinToString("、")}。建议 $owner 补齐后再放量。"
                }
            }
            "passport" in currentState || surfaceId.contains("trip") -> {
                val items = listOf(
                    "护照/证件" to boolVal("passport", true),
                    "快充与充电宝" to boolVal("charger", true),
                    "多国转换插头" to boolVal("adapter", false),
                    "防风外套与雨伞" to boolVal("jacket", false),
                    "随身常备药" to boolVal("meds", false),
                )
                val missing = items.filterNot { it.second }.map { it.first }
                val city = strVal("city", "目的地")
                if (missing.isEmpty()) {
                    "去往「$city」的 5 类核心物品已全部装箱完毕，安心出发！"
                } else {
                    "去往「$city」还差 ${missing.size} 项未勾选：${missing.joinToString("、")}，出门前记得装包。"
                }
            }
            "subtotal" in currentState || surfaceId.contains("expense") -> {
                val sub = currentState["subtotal"]?.jsonPrimitive?.doubleOrNull ?: 680.0
                val pct = currentState["servicePct"]?.jsonPrimitive?.doubleOrNull ?: 10.0
                val ppl = (intVal("people", 4)).coerceAtLeast(1)
                val paid = intVal("paidCount", 1).coerceIn(0, ppl)
                val payer = strVal("payer", "@Artea")
                val total = kotlin.math.round(sub * (1.0 + pct / 100.0)).toInt()
                val each = "%.2f".format(total.toDouble() / ppl)
                "账单含 ${pct.toInt()}% 服务费共 ¥$total，$ppl 人平摊每人 ¥$each（请转给垫付人 $payer；目前 $paid/$ppl 人已付，剩余 ${ppl - paid} 人待转）。"
            }
            else -> {
                val previewEntries = currentState.entries
                    .filter { it.key != "summary" }
                    .joinToString("，") { (k, v) -> "$k=${v.jsonPrimitiveOrJsonString()}" }
                    .ifBlank { "默认初始状态" }
                "已读取当前界面实时状态（$previewEntries），汇总结果已同步写回卡片。"
            }
        }

        val patched = buildJsonObject {
            for ((k, v) in currentState) put(k, v)
            put("summary", JsonPrimitive(summaryText))
        }
        return summaryText to patched
    }

    private fun JsonElement.jsonPrimitiveOrJsonString(): String =
        (this as? JsonPrimitive)?.content ?: toString()
}
