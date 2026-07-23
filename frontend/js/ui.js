// =========================================================
// ui.js - DOM 渲染函数（纯函数式，不含业务逻辑）
// 接收数据操作 DOM，所有格式化工具也集中在此模块。
// 通过 window.UI 全局暴露。
// =========================================================

const UI = {
  /* ---------- 工具：HTML 转义，防止标题等注入 ---------- */
  escapeHtml(str) {
    return String(str == null ? "" : str)
      .replace(/&/g, "&amp;")
      .replace(/</g, "&lt;")
      .replace(/>/g, "&gt;")
      .replace(/"/g, "&quot;")
      .replace(/'/g, "&#39;");
  },
  // 属性上下文复用 escapeHtml（已转义引号）
  escapeAttr(str) {
    return UI.escapeHtml(str);
  },

  /* ---------- 格式化工具 ---------- */
  /** 秒转 mm:ss 或 hh:mm:ss */
  formatDuration(seconds) {
    const s = Number(seconds) || 0;
    if (s < 0) return "00:00";
    const h = Math.floor(s / 3600);
    const m = Math.floor((s % 3600) / 60);
    const sec = Math.floor(s % 60);
    const pad = (n) => String(n).padStart(2, "0");
    return h > 0
      ? pad(h) + ":" + pad(m) + ":" + pad(sec)
      : pad(m) + ":" + pad(sec);
  },

  /** 字节转可读：B/KB/MB/GB */
  formatFileSize(bytes) {
    const b = Number(bytes) || 0;
    if (b <= 0) return "0 B";
    const units = ["B", "KB", "MB", "GB", "TB"];
    let i = 0;
    let val = b;
    while (val >= 1024 && i < units.length - 1) {
      val /= 1024;
      i++;
    }
    return val.toFixed(i === 0 ? 0 : 1) + " " + units[i];
  },

  /** 时间格式化：兼容 ISO 字符串与 Unix 时间戳（秒） */
  formatTime(value) {
    if (!value && value !== 0) return "-";
    let d;
    if (typeof value === "number") {
      d = new Date(value * 1000);
    } else {
      d = new Date(value);
    }
    if (isNaN(d.getTime())) return String(value);
    return d.toLocaleString("zh-CN", {
      year: "numeric",
      month: "2-digit",
      day: "2-digit",
      hour: "2-digit",
      minute: "2-digit",
    });
  },

  /** 状态码转中文文案 */
  _statusText(status, type) {
    if (type === "completed") return "下载完成";
    if (type === "error") return "下载失败";
    const map = {
      pending: "等待中",
      downloading: "下载中",
      merging: "合并中",
      completed: "下载完成",
      failed: "下载失败",
    };
    return map[status] || "处理中";
  },

  /** eta 兼容秒数与字符串 */
  _formatEta(eta) {
    if (eta == null || eta === "") return "";
    const n = Number(eta);
    if (!isNaN(n) && n > 0) return UI.formatDuration(n);
    return String(eta);
  },

  /* ---------- 渲染：解析结果 ---------- */
  /**
   * 渲染解析结果：视频卡片 + 清晰度单选列表 + 可用字幕 + 下载按钮 + 进度容器。
   * @param {Object} data - /api/parse 返回的结构
   */
  renderParseResult(data) {
    const el = document.getElementById("parse-result");
    if (!el) return;
    el.hidden = false;

    const platform = data.platform || "unknown";
    const duration = UI.formatDuration(data.duration);
    const uploader = data.uploader || "未知";

    // 清晰度列表（后端已按分辨率降序，默认选第一个）
    const formats = data.formats || [];
    const formatsHtml = formats.length
      ? formats
          .map((f, i) => {
            const sizeStr = f.filesize
              ? UI.formatFileSize(f.filesize)
              : "未知大小";
            // 从 "1080p" 提取高度判断高清
            const m = /^(\d+)p$/.exec(f.resolution || "");
            const isHd = m && parseInt(m[1], 10) >= 720;
            const badges = [];
            if (isHd) badges.push('<span class="badge badge-hd">高清</span>');
            const codecs = [
              f.vcodec && f.vcodec !== "none" ? f.vcodec : null,
              f.acodec && f.acodec !== "none" ? f.acodec : null,
            ]
              .filter(Boolean)
              .join(" / ");
            const checked = i === 0 ? " checked" : "";
            return (
              '<label class="format-item">' +
              '<input type="radio" name="format-id" value="' +
              UI.escapeAttr(f.id) +
              '"' +
              checked +
              " />" +
              '<span class="format-main">' +
              '<span class="format-resolution">' +
              UI.escapeHtml(f.resolution || "未知") +
              "</span>" +
              '<span class="format-detail">(' +
              UI.escapeHtml(f.ext || "?") +
              ", " +
              sizeStr +
              ")</span>" +
              badges.join("") +
              (codecs
                ? '<span class="format-codecs">' +
                  UI.escapeHtml(codecs) +
                  "</span>"
                : "") +
              "</span>" +
              "</label>"
            );
          })
          .join("")
      : '<div class="empty-state">无可用清晰度</div>';

    // 可用字幕（信息展示，非勾选）
    const subs = data.subtitles || [];
    const subsHtml = subs.length
      ? subs
          .map((s) => {
            const cls = s.auto_generated ? "badge badge-auto" : "badge";
            const suffix = s.auto_generated ? " · 自动" : "";
            return (
              '<span class="' +
              cls +
              '">' +
              UI.escapeHtml(s.name || s.lang) +
              suffix +
              "</span>"
            );
          })
          .join("")
      : '<span class="empty-state">无可用字幕</span>';

    el.innerHTML =
      '<div class="section-head"><h2 class="section-title">解析结果</h2></div>' +
      '<div class="video-card">' +
      (data.thumbnail
        ? '<img class="video-thumb" src="' +
          UI.escapeAttr(data.thumbnail) +
          '" alt="封面" onerror="this.remove()" />'
        : "") +
      '<div class="video-info">' +
      '<h3 class="video-title">' +
      UI.escapeHtml(data.title || "未知标题") +
      "</h3>" +
      '<div class="video-meta">' +
      '<span class="badge badge-platform">' +
      UI.escapeHtml(platform) +
      "</span>" +
      "<span>作者：" +
      UI.escapeHtml(uploader) +
      "</span>" +
      "<span>时长：" +
      duration +
      "</span>" +
      "</div>" +
      "</div>" +
      "</div>" +
      '<div class="subsection">' +
      '<div class="subsection-title">选择清晰度</div>' +
      '<div class="format-list">' +
      formatsHtml +
      "</div>" +
      "</div>" +
      '<div class="subsection">' +
      '<div class="subsection-title">可用字幕</div>' +
      '<div class="video-meta">' +
      subsHtml +
      "</div>" +
      "</div>" +
      '<div class="download-bar">' +
      '<label class="checkbox-label">' +
      '<input type="checkbox" id="download-subtitles" />' +
      "<span>下载字幕</span>" +
      "</label>" +
      '<button id="download-btn" class="btn btn-primary" type="button">下载视频</button>' +
      '<button id="download-audio-btn" class="btn btn-secondary" type="button">下载音频</button>' +
      "</div>" +
      '<div id="progress-container"></div>';
  },

  /** 清空解析结果区 */
  clearParseResult() {
    const el = document.getElementById("parse-result");
    if (el) {
      el.innerHTML = "";
      el.hidden = true;
    }
  },

  /* ---------- 渲染：下载进度 ---------- */
  /**
   * 渲染或更新单任务进度条。
   * @param {string} task_id
   * @param {Object} progress - {type, status, percent, speed, eta, file_path, error}
   */
  renderProgress(task_id, progress) {
    const container = document.getElementById("progress-container");
    if (!container) return;
    let card = container.querySelector(".progress-card");
    if (!card) {
      card = document.createElement("div");
      card.className = "progress-card";
      card.dataset.taskId = task_id;
      container.appendChild(card);
    }
    const percent = Math.max(
      0,
      Math.min(100, Math.round(Number(progress.percent) || 0))
    );
    const statusText = UI._statusText(progress.status, progress.type);
    const speed = progress.speed ? UI.escapeHtml(String(progress.speed)) : "";
    const eta = UI._formatEta(progress.eta);
    let fillClass = "progress-fill";
    if (progress.type === "completed") fillClass += " is-completed";
    else if (progress.type === "error") fillClass += " is-error";

    card.innerHTML =
      '<div class="progress-head">' +
      '<span class="progress-status">' +
      UI.escapeHtml(statusText) +
      "</span>" +
      '<span class="progress-percent">' +
      percent +
      "%</span>" +
      "</div>" +
      '<div class="progress-track"><div class="' +
      fillClass +
      '" style="width:' +
      percent +
      '%"></div></div>' +
      '<div class="progress-info">' +
      (speed ? "<span>速度：" + speed + "</span>" : "") +
      (eta ? "<span>剩余：" + UI.escapeHtml(eta) + "</span>" : "") +
      "</div>";
  },

  /* ---------- 渲染：批次队列状态 ---------- */
  /**
   * 渲染批次汇总 + 各任务队列。
   * @param {Object} batch - {batch_id, total, completed, failed, tasks:[]}
   */
  renderBatchStatus(batch) {
    const el = document.getElementById("batch-status");
    if (!el) return;
    const total = batch.total || 0;
    const completed = batch.completed || 0;
    const failed = batch.failed || 0;
    const done = completed + failed;
    const percent = total > 0 ? Math.round((done / total) * 100) : 0;

    const tasksHtml = (batch.tasks || [])
      .map((t) => {
        const status = t.status || "pending";
        const statusText = UI._statusText(
          status,
          t.progress && t.progress.type
        );
        const url = t.url || "";
        return (
          '<div class="task-queue-item">' +
          '<span class="task-queue-url" title="' +
          UI.escapeAttr(url) +
          '">' +
          UI.escapeHtml(url) +
          "</span>" +
          '<span class="badge badge-' +
          UI.escapeAttr(status) +
          '">' +
          UI.escapeHtml(statusText) +
          "</span>" +
          "</div>"
        );
      })
      .join("");

    el.innerHTML =
      '<div class="batch-summary">' +
      "<span>总数：<strong>" +
      total +
      "</strong></span>" +
      '<span>完成：<strong style="color:var(--color-success)">' +
      completed +
      "</strong></span>" +
      '<span>失败：<strong style="color:var(--color-error)">' +
      failed +
      "</strong></span>" +
      "<span>进度：<strong>" +
      percent +
      "%</strong></span>" +
      "</div>" +
      '<div class="progress-track" style="margin-top:8px">' +
      '<div class="progress-fill" style="width:' +
      percent +
      '%"></div>' +
      "</div>" +
      '<div class="task-queue">' +
      (tasksHtml || '<div class="empty-state">暂无任务</div>') +
      "</div>";
  },

  /* ---------- 渲染：历史记录 ---------- */
  /**
   * 渲染历史列表：顶部全选+批量删除工具栏，每条带 checkbox + 删除按钮。
   * @param {Array} items - [{id,url,title,file_path,platform,time}]
   */
  renderHistory(items) {
    const el = document.getElementById("history-list");
    if (!el) return;
    if (!items || items.length === 0) {
      el.innerHTML = '<div class="empty-state">暂无历史记录</div>';
      return;
    }
    // 顶部工具栏：全选 + 批量删除
    const toolbar =
      '<div class="history-toolbar">' +
      '<label class="checkbox-label">' +
      '<input type="checkbox" id="history-select-all" />' +
      "<span>全选</span>" +
      "</label>" +
      '<button id="history-batch-delete-btn" class="btn btn-danger btn-sm" type="button" disabled>批量删除</button>' +
      "</div>";

    const listHtml = items
      .map(
        (item) =>
          '<div class="list-item">' +
          '<input type="checkbox" class="history-checkbox" data-id="' +
          UI.escapeAttr(item.id) +
          '" />' +
          '<div class="list-item-main">' +
          '<span class="list-item-title" title="' +
          UI.escapeAttr(item.title || "") +
          '">' +
          UI.escapeHtml(item.title || item.url || "未知") +
          "</span>" +
          '<div class="list-item-meta">' +
          (item.platform
            ? '<span class="badge badge-platform">' +
              UI.escapeHtml(item.platform) +
              "</span>"
            : "") +
          (item.url
            ? '<span title="' +
              UI.escapeAttr(item.url) +
              '">' +
              UI.escapeHtml(item.url) +
              "</span>"
            : "") +
          "<span>" +
          UI.formatTime(item.time) +
          "</span>" +
          "</div>" +
          "</div>" +
          '<button class="btn btn-danger btn-sm history-delete" data-id="' +
          UI.escapeAttr(item.id) +
          '" type="button">删除</button>' +
          "</div>"
      )
      .join("");
    el.innerHTML = toolbar + listHtml;
  },

  /* ---------- 渲染：已下载文件 ---------- */
  /**
   * 渲染本地文件列表。
   * @param {Array} files - [{name,size,path,mtime}]
   */
  renderFiles(files) {
    const el = document.getElementById("files-list");
    if (!el) return;
    if (!files || files.length === 0) {
      el.innerHTML = '<div class="empty-state">暂无文件</div>';
      return;
    }
    el.innerHTML = files
      .map(
        (f) =>
          '<div class="list-item">' +
          '<div class="list-item-main">' +
          '<span class="list-item-title" title="' +
          UI.escapeAttr(f.name || "") +
          '">' +
          UI.escapeHtml(f.name || "未知文件") +
          "</span>" +
          '<div class="list-item-meta">' +
          "<span>" +
          UI.formatFileSize(f.size) +
          "</span>" +
          "<span>" +
          UI.formatTime(f.mtime) +
          "</span>" +
          "</div>" +
          "</div>" +
          "</div>"
      )
      .join("");
  },

  /* ---------- 渲染：Toast 提示 ---------- */
  /**
   * 右上角浮层提示，3 秒自动消失。
   * @param {string} message
   * @param {string} type - info / success / error
   */
  showToast(message, type = "info") {
    const container = document.getElementById("toast-container");
    if (!container) return;
    const toast = document.createElement("div");
    toast.className = "toast toast-" + type;
    toast.textContent = message;
    container.appendChild(toast);
    // 触发入场动画
    requestAnimationFrame(() => toast.classList.add("show"));
    // error 类型显示更久，方便用户复制错误信息
    const duration = type === "error" ? 10000 : 3000;
    setTimeout(() => {
      toast.classList.remove("show");
      setTimeout(() => toast.remove(), 300);
    }, duration);
  },
};

window.UI = UI;
