// =========================================================
// api.js - API 请求封装 + SSE 进度监听
// 所有 HTTP 请求统一走 API._request，错误抛出含 message 的 Error
// 通过 window.API 全局暴露（无构建工具，不用模块系统）
// =========================================================

const API = {
  /**
   * 统一 fetch 封装：处理 JSON 序列化、错误抛出、204 空响应。
   * 非 2xx 响应抛出 Error，message 优先取后端 detail/message。
   */
  async _request(path, { method = "GET", body } = {}) {
    const opts = { method, headers: {} };
    if (body !== undefined) {
      opts.headers["Content-Type"] = "application/json";
      opts.body = JSON.stringify(body);
    }
    let res;
    try {
      res = await fetch(path, opts);
    } catch (e) {
      throw new Error("网络请求失败：" + (e.message || "请检查后端服务"));
    }
    if (!res.ok) {
      let msg = "请求失败 (" + res.status + ")";
      try {
        const data = await res.json();
        msg = data.detail || data.message || msg;
      } catch (_) {
        /* 响应非 JSON，沿用默认 msg */
      }
      throw new Error(msg);
    }
    if (res.status === 204) return null;
    return res.json();
  },

  /** POST /api/parse：解析视频元数据 */
  parse(url) {
    return this._request("/api/parse", { method: "POST", body: { url } });
  },

  /** POST /api/download：创建单个下载任务，返回 {task_id} */
  download(url, format_id, download_subtitles, audio_only) {
    return this._request("/api/download", {
      method: "POST",
      body: { url, format_id, download_subtitles, audio_only: !!audio_only },
    });
  },

  /** POST /api/batch：批量下载多个 URL */
  batch(urls, format_id, download_subtitles, audio_only) {
    return this._request("/api/batch", {
      method: "POST",
      body: { urls, format_id, download_subtitles, audio_only: !!audio_only },
    });
  },

  /** GET /api/tasks/{task_id}：查询单任务状态 */
  getTaskStatus(task_id) {
    return this._request("/api/tasks/" + encodeURIComponent(task_id));
  },

  /** GET /api/batch/{batch_id}：查询批次状态汇总 */
  getBatchStatus(batch_id) {
    return this._request("/api/batch/" + encodeURIComponent(batch_id));
  },

  /** GET /api/history?limit=&offset=：分页查询历史 */
  getHistory(limit = 50, offset = 0) {
    return this._request(
      "/api/history?limit=" + limit + "&offset=" + offset
    );
  },

  /** DELETE /api/history/{record_id}：删除一条历史记录 */
  deleteHistory(record_id) {
    return this._request(
      "/api/history/" + encodeURIComponent(record_id),
      { method: "DELETE" }
    );
  },

  /** POST /api/history/batch-delete：批量删除历史记录，返回 {deleted} */
  batchDeleteHistory(ids) {
    return this._request("/api/history/batch-delete", {
      method: "POST",
      body: { ids },
    });
  },

  /** GET /api/bili/config：读取 B站登录配置 */
  getBiliConfig() {
    return this._request("/api/bili/config");
  },

  /** POST /api/bili/config：保存 B站登录配置（SESSDATA），空串清除 */
  saveBiliConfig(sessdata) {
    return this._request("/api/bili/config", {
      method: "POST",
      body: { sessdata },
    });
  },

  /** GET /api/files：列出本地下载文件 */
  getFiles() {
    return this._request("/api/files");
  },

  /**
   * SSE 进度监听：用 fetch + ReadableStream 替代 EventSource。
   *
   * 为什么不用 EventSource：EventSource 的 onerror 在服务端正常关闭、
   * 网络中断、客户端主动 close 三种情况下都会触发，无法可靠区分，
   * 导致下载完成时误报"连接中断"。
   *
   * fetch + ReadableStream 方案能从语义层面区分：
   * - 服务端正常关闭 → reader.read() 返回 {done: true}，正常结束不报错
   * - 网络中断 → reader.read() 抛异常，走 catch 报错
   * - 客户端主动 close → AbortController.abort()，标记 aborted 后静默
   *
   * 回调约定与 EventSource 版本一致：
   * - onMessage(data)：每条进度消息
   * - onError(err)：仅真正的网络异常
   * - onClose()：连接结束（仅触发一次）
   * 返回 { close() } 对象，调用方可主动取消订阅。
   */
  subscribeProgress(task_id, onMessage, onError, onClose) {
    const url = "/api/tasks/" + encodeURIComponent(task_id) + "/progress";
    let aborted = false;
    const controller = new AbortController();

    // 解析并派发单个 SSE 事件；返回 true 表示收到终态消息（调用方应结束）
    function processEvent(raw) {
      if (raw.startsWith(":")) return false; // 心跳注释
      if (!raw.startsWith("data: ")) return false;
      let data;
      try {
        data = JSON.parse(raw.slice(6));
      } catch (_) {
        return false;
      }
      try {
        if (onMessage) onMessage(data);
      } catch (_) {
        /* onMessage 异常不中断流读取 */
      }
      return data.type === "completed" || data.type === "error";
    }

    // 把 buffer 按事件分隔，返回 [事件列表, 残留buffer]
    function splitEvents(buffer) {
      const events = buffer.split(/\r?\n\r?\n/);
      return [events, events.pop()]; // pop 返回最后不完整的一段
    }

    async function run() {
      try {
        const res = await fetch(url, { signal: controller.signal });
        if (!res.ok) {
          throw new Error("进度连接失败 (" + res.status + ")");
        }
        const reader = res.body.getReader();
        const decoder = new TextDecoder();
        let buffer = "";

        while (true) {
          const { done, value } = await reader.read();
          if (done) {
            // 流结束：处理 buffer 中可能残留的最后一个事件
            //（服务端发送 completed 后立即关闭流，completed 可能还在 buffer 里）
            if (buffer.trim()) {
              if (processEvent(buffer)) {
                if (onClose) onClose();
                return;
              }
            }
            break;
          }

          buffer += decoder.decode(value, { stream: true });
          const [events, rest] = splitEvents(buffer);
          buffer = rest;

          for (const raw of events) {
            if (processEvent(raw)) {
              if (onClose) onClose();
              return;
            }
          }
        }
        // 服务端关闭连接但未收到终态消息：视为正常结束，不报错
        if (!aborted && onClose) onClose();
      } catch (err) {
        // 主动 abort 不报错；其他异常才是真正的连接中断
        if (aborted) return;
        if (onError) onError(err);
        if (onClose) onClose();
      }
    }

    run();

    return {
      close() {
        aborted = true;
        controller.abort();
      },
    };
  },
};

window.API = API;
