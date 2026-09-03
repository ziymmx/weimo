import { exec, hasKernelSuBridge, shellQuote } from "./bridge.js";

const $ = (selector) => document.querySelector(selector);
const featureList = $("#feature-list");
const featureLoading = $("#feature-loading");
const emptyState = $("#empty-state");
const enabledCount = $("#enabled-count");
const resetAllButton = $("#reset-all");
const toastEl = $("#toast");

// 与 config.sh / native companion / FeatureFlags 保持一致。
const FEATURES = [
  { id: "anti-recall", name: "消息防撤回", desc: "对方撤回后消息仍保留，可通过系统提示点击定位原消息" },
  { id: "force-tablet", name: "强制平板布局", desc: "启用微信平板布局并放行「以平板身份登录」校验" },
  { id: "anti-xposed-detect", name: "阻止 Xposed 检测", desc: "让微信检测不到 Xposed / Zygisk 注入痕迹" },
  { id: "disable-hot-update", name: "禁用热更新", desc: "关闭微信内置 Tinker 热更新机制" },
  { id: "moments-anti-recall", name: "朋友圈防撤回（实验）", desc: "保留被撤回的朋友圈内容" },
  { id: "moments-comment-anti-recall", name: "朋友圈评论防撤回（实验）", desc: "保留被撤回的朋友圈评论" },
  { id: "moments-ad-block", name: "朋友圈广告拦截（实验）", desc: "过滤朋友圈信息流中的广告" },
];
const FEATURE_IDS = new Set(FEATURES.map((feature) => feature.id));

let toastTimer = 0;

function configScriptPath() {
  try {
    const path = decodeURIComponent(
      new URL("../config.sh", document.baseURI).pathname,
    );
    if (
      path.startsWith("/data/adb/modules/") ||
      path.startsWith("/data/adb/modules_update/")
    ) {
      return path;
    }
  } catch (_) {
    // Fall through to the stable module path.
  }
  return "/data/adb/modules/weimo_zygisk/config.sh";
}

async function runCheckedCommand(commandLine, operation) {
  let result;
  try {
    result = await exec(commandLine);
  } catch (error) {
    console.error("[Weimo WebUI] bridge failure", { commandLine, error });
    throw error;
  }
  const exitCode = Number.isFinite(Number(result.errno))
    ? Number(result.errno)
    : -1;
  console.info("[Weimo WebUI] command result", {
    commandLine,
    exitCode,
    stdout: result.stdout,
    stderr: result.stderr,
  });
  if (exitCode !== 0) {
    const stderr = (result.stderr || "").trim();
    const stdout = (result.stdout || "").trim();
    const firstDetail =
      (stderr || stdout).split("\n")[0] || "命令没有返回错误文本";
    throw new Error(`${operation} 失败 (exit ${exitCode}): ${firstDetail}`);
  }
  return result.stdout || "";
}

async function configCommand(command, ...args) {
  const commandLine = [
    "sh",
    shellQuote(configScriptPath()),
    shellQuote(command),
    ...args.map(shellQuote),
  ].join(" ");
  return runCheckedCommand(commandLine, command);
}

function showToast(message, isError = false) {
  toastEl.textContent = message;
  toastEl.classList.toggle("error", isError);
  toastEl.classList.add("visible");
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => toastEl.classList.remove("visible"), 2600);
}

function parseState(stdout) {
  const state = {};
  for (const line of stdout.split(/\r?\n/)) {
    const [name, value] = line.split("\t");
    if (FEATURE_IDS.has(name) && (value === "0" || value === "1")) {
      state[name] = value === "1";
    }
  }
  return state;
}

function createFeatureRow(feature, enabled) {
  const row = document.createElement("article");
  row.className = "feature-row";

  const main = document.createElement("div");
  main.className = "feature-main";
  const nameEl = document.createElement("div");
  nameEl.className = "feature-name";
  nameEl.textContent = feature.name;
  const descEl = document.createElement("div");
  descEl.className = "feature-desc";
  descEl.textContent = feature.desc;
  main.append(nameEl, descEl);

  const label = document.createElement("label");
  label.className = "switch";
  label.title = enabled ? "已启用" : "已关闭";
  const toggle = document.createElement("input");
  toggle.type = "checkbox";
  toggle.checked = enabled;
  toggle.setAttribute("role", "switch");
  toggle.setAttribute("aria-label", feature.name);
  const track = document.createElement("span");
  track.className = "switch-track";
  label.append(toggle, track);

  toggle.addEventListener("change", async () => {
    const requested = toggle.checked;
    toggle.disabled = true;
    try {
      await configCommand("set", feature.id, requested ? "1" : "0");
      label.title = requested ? "已启用" : "已关闭";
      showToast(`${feature.name}已${requested ? "启用" : "关闭"}，重启微信后生效`);
      await loadFeatures();
    } catch (error) {
      toggle.checked = !requested;
      showToast(error?.message || "更新失败", true);
    } finally {
      toggle.disabled = false;
    }
  });

  row.append(main, label);
  return row;
}

async function loadFeatures() {
  featureLoading.hidden = false;
  featureList.hidden = true;
  emptyState.hidden = true;
  try {
    const state = parseState(await configCommand("list"));
    const rows = FEATURES.map((feature) =>
      createFeatureRow(feature, state[feature.id] !== false),
    );
    featureList.replaceChildren(...rows);
    const enabled = FEATURES.filter(
      (feature) => state[feature.id] !== false,
    ).length;
    enabledCount.textContent = String(enabled);
    featureList.hidden = false;
    return true;
  } catch (error) {
    emptyState.hidden = false;
    showToast(error?.message || "无法读取开关", true);
    return false;
  } finally {
    featureLoading.hidden = true;
  }
}

resetAllButton.addEventListener("click", async () => {
  resetAllButton.disabled = true;
  try {
    await configCommand("reset");
    showToast("已恢复默认（全部启用），重启微信后生效");
    await loadFeatures();
  } catch (error) {
    showToast(error?.message || "重置失败", true);
  } finally {
    resetAllButton.disabled = false;
  }
});

if (!hasKernelSuBridge()) {
  featureLoading.textContent = "请在 KernelSU 管理器中打开此页面";
  resetAllButton.disabled = true;
} else {
  void loadFeatures();
}
