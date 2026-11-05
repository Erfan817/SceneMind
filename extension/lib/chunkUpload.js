/**
 * 扩展侧的 5MB 分片上传（与网页端 chunkUpload 使用同一套后端协议）。
 * 只做「首次上传」，不做断点续传 —— 扩展侧边栏生命周期短，续传收益有限；
 * 失败后用户可以重新选择文件重试。
 */

const CHUNK_SIZE = 5 * 1024 * 1024
const MAX_TOTAL_CHUNKS = 410
const UPLOAD_CONCURRENCY = 3
const MAX_ATTEMPTS = 4

export const MAX_UPLOAD_BYTES = MAX_TOTAL_CHUNKS * CHUNK_SIZE

export function formatBytes(bytes) {
  const value = Number(bytes) || 0
  if (value < 1024) return `${value} B`
  const units = ['KB', 'MB', 'GB', 'TB']
  let scaled = value / 1024
  let index = 0
  while (scaled >= 1024 && index < units.length - 1) {
    scaled /= 1024
    index += 1
  }
  return `${scaled >= 10 ? Math.round(scaled) : scaled.toFixed(1)} ${units[index]}`
}

export function validateVideoFile(file) {
  if (!file) return '请先选择视频文件'
  if (!/\.(mp4|mov|mkv|avi|webm|m4v)$/i.test(file.name || '')) {
    return '仅支持 MP4、MOV、MKV、AVI、WEBM 和 M4V 视频'
  }
  if (!file.size) return '该文件大小为 0，可能已损坏或仍在同步，请重新选择'
  if (file.size > MAX_UPLOAD_BYTES) {
    return `文件 ${formatBytes(file.size)}，超过 ${formatBytes(MAX_UPLOAD_BYTES)} 上限`
  }
  return ''
}

/**
 * @param {File} file
 * @param {{ request: Function, onProgress?: Function, signal?: AbortSignal }} options
 * @returns {Promise<{id:number, filename:string}>}
 */
export async function uploadVideoInChunks(file, { request, onProgress = () => {}, signal } = {}) {
  const invalid = validateVideoFile(file)
  if (invalid) throw new Error(invalid)
  if (typeof request !== 'function') throw new Error('缺少上传请求函数')
  throwIfAborted(signal)

  const totalChunks = Math.ceil(file.size / CHUNK_SIZE)
  const initParams = new URLSearchParams({ filename: file.name, totalChunks: String(totalChunks) })
  const initResponse = await request(`/media/init-upload?${initParams}`, { method: 'POST', signal })
  if (!initResponse.ok) {
    throw new Error((await initResponse.text()) || '上传初始化失败，请稍后重试')
  }
  const uploadId = (await initResponse.text()).trim()
  if (!uploadId) throw new Error('后端未返回 uploadId')

  let completed = 0
  const emit = () => onProgress({
    completedChunks: completed,
    totalChunks,
    percent: Math.round((completed / totalChunks) * 100),
  })

  const indexes = Array.from({ length: totalChunks }, (_, index) => index)
  let cursor = 0
  let fatalError = null

  const worker = async () => {
    while (!fatalError && cursor < indexes.length) {
      throwIfAborted(signal)
      const index = indexes[cursor++]
      try {
        await uploadChunkWithRetry({ file, uploadId, index, totalChunks, request, signal })
        completed += 1
        emit()
      } catch (error) {
        fatalError = error
      }
    }
  }

  await Promise.all(Array.from(
    { length: Math.min(UPLOAD_CONCURRENCY, indexes.length) },
    worker,
  ))
  if (fatalError) throw fatalError
  throwIfAborted(signal)

  emit()
  const completeParams = new URLSearchParams({ uploadId })
  const completeResponse = await request(`/media/complete-upload?${completeParams}`, {
    method: 'POST',
    signal,
  })
  if (!completeResponse.ok) {
    throw new Error((await completeResponse.text()) || '分片合并失败，请重新选择文件重试')
  }
  return completeResponse.json()
}

async function uploadChunkWithRetry({ file, uploadId, index, totalChunks, request, signal }) {
  let lastError = null
  for (let attempt = 1; attempt <= MAX_ATTEMPTS; attempt += 1) {
    throwIfAborted(signal)
    try {
      const start = index * CHUNK_SIZE
      const end = Math.min(file.size, start + CHUNK_SIZE)
      const form = new FormData()
      form.append('uploadId', uploadId)
      form.append('chunkIndex', String(index))
      form.append('totalChunks', String(totalChunks))
      form.append('file', file.slice(start, end))
      const response = await request('/media/upload-chunk', { method: 'POST', body: form, signal })
      if (response.ok) return
      const error = new Error((await response.text()) || '服务端未接收该分片')
      error.status = response.status
      throw error
    } catch (error) {
      lastError = error
      if (attempt === MAX_ATTEMPTS || !isRetriable(error)) break
      await sleep(600 * 2 ** (attempt - 1), signal)
    }
  }
  throw new Error(`分片 ${index + 1}/${totalChunks} 上传失败：${lastError?.message || '网络异常'}`)
}

function isRetriable(error) {
  const status = error?.status
  if (status === undefined) return true
  if (status === 408 || status === 429) return true
  return status >= 500
}

function sleep(ms, signal) {
  return new Promise((resolve, reject) => {
    if (signal?.aborted) {
      reject(new DOMException('上传已取消', 'AbortError'))
      return
    }
    const timer = setTimeout(() => {
      signal?.removeEventListener('abort', onAbort)
      resolve()
    }, ms)
    const onAbort = () => {
      clearTimeout(timer)
      reject(new DOMException('上传已取消', 'AbortError'))
    }
    signal?.addEventListener('abort', onAbort, { once: true })
  })
}

function throwIfAborted(signal) {
  if (signal?.aborted) throw new DOMException('上传已取消', 'AbortError')
}
