"use strict";

const $ = id => document.getElementById(id);
let listRequest = null;
let saving = false;
let hasList = false;

function message(text, error = false) {
  $("status").textContent = text;
  $("status").classList.toggle("error", error);
  $("status").hidden = !text;
}

function connection(online) {
  $("connection").textContent = online ? "已连接电视" : "连接中断";
  $("connection").classList.toggle("online", online);
  $("connection").classList.toggle("offline", !online);
}

function buttons() {
  $("save").disabled = saving;
  $("save").textContent = saving ? "正在保存…" : "保存到电视";
  $("refresh").disabled = saving || listRequest !== null;
}

async function request(path, fields, controller = new AbortController()) {
  let timedOut = false;
  const timer = setTimeout(() => { timedOut = true; controller.abort(); }, 12000);
  try {
    const response = await fetch(path, {
      method: fields ? "POST" : "GET",
      body: fields ? new URLSearchParams(fields) : undefined,
      signal: controller.signal,
      cache: "no-store"
    });
    let data;
    try { data = await response.json(); }
    catch (error) { throw new Error("电视返回内容异常，请重试"); }
    if (!data || typeof data !== "object") throw new Error("电视返回内容异常，请重试");
    if (!response.ok) throw new Error(data.message || "请求失败，请重试");
    return data;
  } catch (error) {
    if (timedOut || error instanceof TypeError) {
      const networkError = new Error(timedOut ? "连接超时，请确认电视已打开并连接同一局域网" : "无法连接电视，请检查网络后重试");
      networkError.connectionLost = true;
      throw networkError;
    }
    throw error;
  } finally {
    clearTimeout(timer);
  }
}

function render(items) {
  const fragment = document.createDocumentFragment();
  for (const item of items) {
    const li = document.createElement("li");
    const name = document.createElement("div");
    name.className = "sourceName";
    name.textContent = item.name;
    li.append(name);
    if (item.active) {
      const badge = document.createElement("span");
      badge.className = "activeBadge";
      badge.textContent = "当前使用";
      li.append(badge);
    }
    fragment.append(li);
  }
  $("sources").replaceChildren(fragment);
  $("empty").hidden = items.length !== 0;
  $("sourceCount").textContent = String(items.length);
  $("sourceCount").hidden = false;
  hasList = true;
}

async function refresh() {
  if (listRequest) listRequest.abort();
  const controller = new AbortController();
  listRequest = controller;
  buttons();
  $("listContent").setAttribute("aria-busy", "true");
  $("listStatus").hidden = false;
  $("listStatus").classList.remove("error");
  $("listStatus").textContent = hasList ? "正在更新线路…" : "正在读取线路…";
  try {
    const data = await request("/api/sources", undefined, controller);
    if (listRequest !== controller) return;
    if (!Array.isArray(data.items) || data.items.some(item => !item || typeof item.name !== "string" || typeof item.active !== "boolean")) {
      throw new Error("线路列表内容异常，请重试");
    }
    render(data.items);
    connection(true);
    $("listStatus").hidden = true;
    $("refresh").textContent = "刷新";
  } catch (error) {
    if (listRequest !== controller) return;
    connection(false);
    $("listStatus").classList.add("error");
    $("listStatus").textContent = hasList ? "更新失败，已保留上次的列表。" + error.message : error.message;
    $("refresh").textContent = "重试";
  } finally {
    if (listRequest === controller) {
      listRequest = null;
      $("listContent").setAttribute("aria-busy", "false");
      buttons();
    }
  }
}

$("sourceForm").addEventListener("submit", async event => {
  event.preventDefault();
  if (saving) return;
  const input = $("sourceUrl").value;
  const url = input.trim();
  if (!url) {
    message("请粘贴配置链接", true);
    $("sourceUrl").focus();
    return;
  }
  saving = true;
  buttons();
  message("正在识别名称并保存…");
  try {
    const data = await request("/api/sources", { url });
    connection(true);
    message(data.message || "已保存，电视正在切换到该线路");
    if ($("sourceUrl").value === input) $("sourceUrl").value = "";
    await refresh();
  } catch (error) {
    if (error.connectionLost) connection(false);
    message(error.message, true);
  } finally {
    saving = false;
    buttons();
  }
});

$("sourceUrl").addEventListener("keydown", event => {
  if (event.key === "Enter" && (event.ctrlKey || event.metaKey)) {
    event.preventDefault();
    $("sourceForm").requestSubmit();
  }
});
$("refresh").addEventListener("click", refresh);
refresh();
