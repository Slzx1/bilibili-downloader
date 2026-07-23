// =========================================================
// app.js - 主逻辑：事件绑定 + 状态编排 + 模块初始化
// 依赖：window.API（api.js）、window.UI（ui.js）
// =========================================================

document.addEventListener("DOMContentLoaded", () => {
  /* ---------- 应用状态 ---------- */
  const state = {
    parsedUrl: null, // 当前解析成功的视频 URL
    currentES: null, // 当前下载的 EventSource，用于再次下载前关闭
    batchInterval: null, // 批次轮询定时器
  };

  /* ---------- 元素引用 ---------- */
  const urlInput = document.getElementById("url-input");
  const parseBtn = document.getElementById("parse-btn");
  const parseResult = document.getElementById("parse-result");

  const batchUrls = document.getElementById("batch-urls");
  const batchFormatList = document.getElementById("batch-format-list");
  const batchSubtitles = document.getElementById("batch-subtitles");
  const batchDownloadBtn = document.getElementById("batch-download-btn");
  const batchDownloadAudioBtn = document.getElementById("batch-download-audio-btn");

  const historyList = document.getElementById("history-list");
  const refreshHistoryBtn = document.getElementById("refresh-history-btn");
  const filesList = document.getElementById("files-list");
  const refreshFilesBtn = document.getElementById("refresh-files-btn");

  const biliSessdataInput = document.getElementById("bili-sessdata-input");
  const biliSaveBtn = document.getElementById("bili-save-btn");

  /* ---------- 工具：读取选中清晰度 ---------- */
  function getSelectedFormatId() {
    const checked = parseResult.querySelector(
      'input[name="format-id"]:checked'
    );
    return checked ? checked.value : null;
  }

  /* ---------- 解析流程 ---------- */
  async function handleParse() {
    const url = urlInput.value.trim();
    if (!url) {
      UI.showToast("请输入视频链接", "error");
      return;
    }
    parseBtn.disabled = true;
    parseBtn.textContent = "解析中…";
    // 关闭可能存在的旧 SSE，清空旧结果
    closeCurrentES();
    try {
      const data = await API.parse(url);
      state.parsedUrl = data.url || url;
      UI.renderParseResult(data);
    } catch (e) {
      UI.showToast(e.message || "解析失败", "error");
      UI.clearParseResult();
      state.parsedUrl = null;
    } finally {
      parseBtn.disabled = false;
      parseBtn.textContent = "解析";
    }
  }

  /* ---------- 下载流程（事件委托，按钮在解析后动态生成） ---------- */
  async function handleDownload(audioOnly) {
    if (!state.parsedUrl) {
      UI.showToast("请先解析视频", "error");
      return;
    }
    const formatId = audioOnly ? null : getSelectedFormatId();
    const downloadSubs = audioOnly
      ? false
      : document.getElementById("download-subtitles")
      ? document.getElementById("download-subtitles").checked
      : false;
    const downloadBtn = document.getElementById(audioOnly ? "download-audio-btn" : "download-btn");
    if (downloadBtn) downloadBtn.disabled = true;

    closeCurrentES();
    try {
      const { task_id } = await API.download(
        state.parsedUrl,
        formatId,
        downloadSubs,
        audioOnly
      );
      UI.renderProgress(task_id, {
        status: "pending",
        percent: 0,
        speed: "",
        eta: "",
      });
      state.currentES = API.subscribeProgress(
        task_id,
        (msg) => {
          UI.renderProgress(task_id, msg);
          if (msg.type === "completed") {
            UI.showToast(audioOnly ? "音频下载完成" : "下载完成", "success");
            loadHistory();
            loadFiles();
          } else if (msg.type === "error") {
            UI.showToast(msg.error || "下载失败", "error");
          }
        },
        (err) => {
          UI.showToast(err.message || "进度连接异常", "error");
        },
        () => {
          // 流结束：恢复按钮
          state.currentES = null;
          const vBtn = document.getElementById("download-btn");
          const aBtn = document.getElementById("download-audio-btn");
          if (vBtn) vBtn.disabled = false;
          if (aBtn) aBtn.disabled = false;
        }
      );
    } catch (e) {
      UI.showToast(e.message || "下载请求失败", "error");
      if (downloadBtn) downloadBtn.disabled = false;
    }
  }

  function closeCurrentES() {
    if (state.currentES) {
      state.currentES.close();
      state.currentES = null;
    }
  }

  /* ---------- 批量：读取选中清晰度 ---------- */
  function getBatchFormatId() {
    const checked = batchFormatList.querySelector('input[name="batch-quality"]:checked');
    return checked ? checked.value : "";
  }

  /* ---------- 批量：开始下载 + 轮询 ---------- */
  async function handleBatchDownload(audioOnly) {
    const urls = batchUrls.value
      .split("\n")
      .map((s) => s.trim())
      .filter(Boolean);
    // 仅下载音频时清晰度无意义，强制置空；否则取 radio 选中值
    const formatId = audioOnly ? null : (getBatchFormatId() || null);
    const downloadSubs = audioOnly ? false : batchSubtitles.checked;

    if (urls.length === 0) {
      UI.showToast("请输入视频链接", "error");
      return;
    }
    batchDownloadBtn.disabled = true;
    if (batchDownloadAudioBtn) batchDownloadAudioBtn.disabled = true;
    stopBatchPolling();
    try {
      const { batch_id, count } = await API.batch(urls, formatId, downloadSubs, audioOnly);
      UI.showToast("已创建 " + count + " 个下载任务", "success");
      startBatchPolling(batch_id);
    } catch (e) {
      UI.showToast(e.message || "批量下载失败", "error");
      batchDownloadBtn.disabled = false;
      if (batchDownloadAudioBtn) batchDownloadAudioBtn.disabled = false;
    }
  }

  /** 启动批次状态轮询：每 2s 查询，全部完成时停止 */
  function startBatchPolling(batch_id) {
    stopBatchPolling();
    state.batchInterval = setInterval(async () => {
      try {
        const batch = await API.getBatchStatus(batch_id);
        UI.renderBatchStatus(batch);
        if (batch.completed + batch.failed >= batch.total) {
          stopBatchPolling();
          batchDownloadBtn.disabled = false;
          if (batchDownloadAudioBtn) batchDownloadAudioBtn.disabled = false;
          const type = batch.failed > 0 ? "error" : "success";
          UI.showToast(
            "批次完成：成功 " + batch.completed + "，失败 " + batch.failed,
            type
          );
          loadHistory();
          loadFiles();
        }
      } catch (_) {
        // 单次轮询失败不中断，下次重试
      }
    }, 2000);
  }

  function stopBatchPolling() {
    if (state.batchInterval) {
      clearInterval(state.batchInterval);
      state.batchInterval = null;
    }
  }

  /* ---------- 历史记录 ---------- */
  async function loadHistory() {
    try {
      const { items } = await API.getHistory(50, 0);
      UI.renderHistory(items);
    } catch (e) {
      historyList.innerHTML =
        '<div class="empty-state">加载失败</div>';
    }
  }

  async function handleDeleteHistory(recordId) {
    if (!confirm("确定删除该历史记录？对应本地文件也会被删除。")) return;
    try {
      await API.deleteHistory(recordId);
      UI.showToast("已删除", "success");
      loadHistory();
      loadFiles();
    } catch (e) {
      UI.showToast(e.message || "删除失败", "error");
    }
  }

  /* ---------- 文件列表 ---------- */
  async function loadFiles() {
    try {
      const files = await API.getFiles();
      UI.renderFiles(files);
    } catch (e) {
      filesList.innerHTML = '<div class="empty-state">加载失败</div>';
    }
  }

  /* ---------- B站登录配置 ---------- */
  async function loadBiliConfig() {
    try {
      const { sessdata } = await API.getBiliConfig();
      if (biliSessdataInput) biliSessdataInput.value = sessdata || "";
    } catch (_) {
      // 加载失败不阻塞主流程
    }
  }

  async function handleSaveBiliConfig() {
    const value = biliSessdataInput ? biliSessdataInput.value.trim() : "";
    biliSaveBtn.disabled = true;
    try {
      await API.saveBiliConfig(value);
      UI.showToast(
        value
          ? "已保存，重新解析视频可刷新清晰度列表"
          : "已清除登录配置",
        "success"
      );
    } catch (e) {
      UI.showToast(e.message || "保存失败", "error");
    } finally {
      biliSaveBtn.disabled = false;
    }
  }

  /* ---------- 事件绑定 ---------- */
  parseBtn.addEventListener("click", handleParse);
  urlInput.addEventListener("keydown", (e) => {
    if (e.key === "Enter") handleParse();
  });

  // 解析结果区事件委托：下载按钮
  parseResult.addEventListener("click", (e) => {
    if (e.target && e.target.id === "download-btn") {
      handleDownload(false);
    } else if (e.target && e.target.id === "download-audio-btn") {
      handleDownload(true);
    }
  });

  batchDownloadBtn.addEventListener("click", () => handleBatchDownload(false));
  batchDownloadAudioBtn.addEventListener("click", () => handleBatchDownload(true));

  /* ---------- 历史记录：全选 + 批量删除 ---------- */
  function updateBatchDeleteBtn() {
    const checked = historyList.querySelectorAll(".history-checkbox:checked");
    const btn = document.getElementById("history-batch-delete-btn");
    if (btn) {
      btn.disabled = checked.length === 0;
      if (checked.length > 0) {
        btn.textContent = "批量删除(" + checked.length + ")";
      } else {
        btn.textContent = "批量删除";
      }
    }
    // 同步全选框状态
    const selectAll = document.getElementById("history-select-all");
    const allBoxes = historyList.querySelectorAll(".history-checkbox");
    if (selectAll && allBoxes.length > 0) {
      selectAll.checked = checked.length === allBoxes.length;
    }
  }

  // 历史列表事件委托：删除按钮 + checkbox + 全选 + 批量删除
  historyList.addEventListener("click", (e) => {
    const deleteBtn = e.target.closest(".history-delete");
    if (deleteBtn) {
      handleDeleteHistory(deleteBtn.dataset.id);
      return;
    }
    const batchBtn = e.target.closest("#history-batch-delete-btn");
    if (batchBtn) {
      handleBatchDelete();
      return;
    }
    const selectAll = e.target.closest("#history-select-all");
    if (selectAll) {
      const checkboxes = historyList.querySelectorAll(".history-checkbox");
      checkboxes.forEach((cb) => { cb.checked = selectAll.checked; });
      updateBatchDeleteBtn();
    }
  });

  // checkbox 变化时更新批量删除按钮状态
  historyList.addEventListener("change", (e) => {
    if (e.target.classList && e.target.classList.contains("history-checkbox")) {
      updateBatchDeleteBtn();
    }
  });

  async function handleBatchDelete() {
    const checked = historyList.querySelectorAll(".history-checkbox:checked");
    if (checked.length === 0) return;
    if (!confirm("确定删除选中的 " + checked.length + " 条记录？对应本地文件也会被删除。")) return;
    const ids = Array.from(checked).map((cb) => cb.dataset.id);
    try {
      const { deleted } = await API.batchDeleteHistory(ids);
      UI.showToast("已删除 " + deleted + " 条记录", "success");
      loadHistory();
      loadFiles();
    } catch (e) {
      UI.showToast(e.message || "批量删除失败", "error");
    }
  }

  refreshHistoryBtn.addEventListener("click", loadHistory);
  refreshFilesBtn.addEventListener("click", loadFiles);
  biliSaveBtn.addEventListener("click", handleSaveBiliConfig);

  /* ---------- 初始化：加载历史与文件 ---------- */
  loadHistory();
  loadFiles();
  loadBiliConfig();
});
