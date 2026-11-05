import assert from 'node:assert/strict'
import { test } from 'node:test'
import { MAX_UPLOAD_BYTES, formatBytes, uploadVideoInChunks, validateVideoFile } from '../lib/chunkUpload.js'

function makeFile(bytes, name = 'sample.mp4') {
  return new File([new Uint8Array(bytes)], name, { type: 'video/mp4' })
}

test('validateVideoFile rejects unsupported extensions and empty files', () => {
  assert.match(validateVideoFile(makeFile(10, 'a.txt')), /仅支持/)
  assert.match(validateVideoFile(makeFile(0)), /大小为 0/)
  assert.equal(validateVideoFile(makeFile(10)), '')
})

test('formatBytes and max upload size are exposed', () => {
  assert.equal(formatBytes(1024), '1.0 KB')
  assert.ok(MAX_UPLOAD_BYTES > 0)
})

test('uploadVideoInChunks uploads every chunk and completes', async () => {
  const file = makeFile(12 * 1024 * 1024)
  const calls = []
  const request = async (path, options = {}) => {
    calls.push({ path, options })
    if (path.startsWith('/media/init-upload')) {
      return { ok: true, text: async () => 'upload-123', json: async () => null }
    }
    if (path.startsWith('/media/upload-chunk')) {
      return { ok: true, text: async () => '', json: async () => null }
    }
    if (path.startsWith('/media/complete-upload')) {
      return { ok: true, text: async () => '', json: async () => ({ id: 7, filename: 'sample.mp4' }) }
    }
    throw new Error(`unexpected path ${path}`)
  }

  const progress = []
  const media = await uploadVideoInChunks(file, {
    request,
    onProgress: value => progress.push(value.percent),
  })

  assert.deepEqual(media, { id: 7, filename: 'sample.mp4' })
  const chunkCalls = calls.filter(call => call.path.startsWith('/media/upload-chunk'))
  assert.equal(chunkCalls.length, 3, '12MB 文件应切成 3 个 5MB 分片')
  assert.deepEqual(progress.at(-1), 100)
  assert.ok(calls.some(call => call.path.startsWith('/media/complete-upload')))
})
