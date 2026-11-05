import assert from 'node:assert/strict'
import test from 'node:test'
import { renderMarkdown } from './markdown.js'

// renderMarkdown 依赖浏览器 DOM（template.innerHTML / querySelectorAll / createTextNode）。
// node:test 没有 DOM，仓库里也没有 jsdom/linkedom 等库，且不允许新增依赖，
// 所以这里自带一个零依赖的最小 DOM：只实现 sanitizeNode 用到的接口
//（解析成节点树、属性增删、节点替换、textContent 回读、序列化回 HTML）。
// 它只需要正确处理 marked 产出的（以及测试里刻意传入的）合法/恶意 HTML。

const VOID_TAGS = new Set([
  'BR', 'HR', 'IMG', 'INPUT', 'META', 'LINK', 'AREA', 'BASE',
  'COL', 'EMBED', 'SOURCE', 'TRACK', 'WBR'
])

const decode = text => text
  .replace(/&amp;/g, '&')
  .replace(/&lt;/g, '<')
  .replace(/&gt;/g, '>')
  .replace(/&quot;/g, '"')
  .replace(/&#x27;|&#39;|&apos;/g, "'")

const escapeText = text => text
  .replace(/&/g, '&amp;')
  .replace(/</g, '&lt;')
  .replace(/>/g, '&gt;')

const escapeAttr = value => escapeText(value).replace(/"/g, '&quot;')

class TextNode {
  constructor(data) {
    this.nodeType = 3
    this.tagName = undefined
    this.attributes = []
    this.childNodes = []
    this.parentNode = null
    this._data = String(data)
  }
  get textContent() { return this._data }
}

class ElementNode {
  constructor(tagName) {
    this.nodeType = 1
    this.tagName = tagName
    this.attributes = []
    this.childNodes = []
    this.parentNode = null
  }
  get textContent() { return this.childNodes.map(node => node.textContent).join('') }
  getAttribute(name) {
    const found = this.attributes.find(attribute => attribute.name === name)
    return found ? found.value : null
  }
  setAttribute(name, value) {
    const normalized = String(name)
    const found = this.attributes.find(attribute => attribute.name === normalized)
    if (found) found.value = String(value)
    else this.attributes.push({ name: normalized, value: String(value) })
  }
  removeAttribute(name) {
    this.attributes = this.attributes.filter(attribute => attribute.name !== name)
  }
  replaceWith(...nodes) {
    const parent = this.parentNode
    if (!parent) return
    const index = parent.childNodes.indexOf(this)
    if (index === -1) return
    for (const node of nodes) node.parentNode = parent
    parent.childNodes.splice(index, 1, ...nodes)
    this.parentNode = null
  }
  querySelectorAll() {
    const found = []
    const walk = node => {
      for (const child of node.childNodes) {
        if (child.nodeType !== 1) continue
        found.push(child)
        walk(child)
      }
    }
    walk(this)
    return found
  }
}

function appendChild(parent, child) {
  child.parentNode = parent
  parent.childNodes.push(child)
}

function tokenize(html) {
  const tokens = []
  let index = 0
  while (index < html.length) {
    if (html.startsWith('<!--', index)) {
      const end = html.indexOf('-->', index + 4)
      index = end === -1 ? html.length : end + 3
      continue
    }
    if (html[index] === '<') {
      let cursor = index + 1
      let quote = null
      while (cursor < html.length) {
        const char = html[cursor]
        if (quote) {
          if (char === quote) quote = null
        } else if (char === '"' || char === "'") quote = char
        else if (char === '>') break
        cursor += 1
      }
      tokens.push({ type: 'tag', raw: html.slice(index, Math.min(cursor + 1, html.length)) })
      index = cursor + 1
    } else {
      let cursor = index
      while (cursor < html.length && html[cursor] !== '<') cursor += 1
      tokens.push({ type: 'text', raw: html.slice(index, cursor) })
      index = cursor
    }
  }
  return tokens
}

function parseTag(raw) {
  const closing = raw.startsWith('</')
  const selfClosing = raw.endsWith('/>')
  let content = raw.slice(1, -1)
  if (closing) content = content.replace(/^\s*\//, '')
  content = content.replace(/\/\s*$/, '').trim()
  const name = content.match(/^([a-zA-Z][\w-]*)/)
  if (!name) return null
  const attributes = []
  const pattern = /([^\s=/>]+)(?:\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'>]+)))?/g
  const rest = content.slice(name[0].length)
  let match
  while ((match = pattern.exec(rest))) {
    attributes.push({
      name: match[1].toLowerCase(),
      value: decode(match[2] ?? match[3] ?? match[4] ?? '')
    })
  }
  return { closing, selfClosing, tagName: name[1].toUpperCase(), attributes }
}

function parseInto(fragment, html) {
  fragment.childNodes = []
  const stack = [fragment]
  for (const token of tokenize(html)) {
    if (token.type === 'text') {
      appendChild(stack[stack.length - 1], new TextNode(decode(token.raw)))
      continue
    }
    const tag = parseTag(token.raw)
    if (!tag) continue
    if (tag.closing) {
      const matchIndex = stack.findLastIndex(node => node.tagName === tag.tagName)
      if (matchIndex > 0) stack.length = matchIndex
      continue
    }
    const element = new ElementNode(tag.tagName)
    element.attributes = tag.attributes
    appendChild(stack[stack.length - 1], element)
    if (!VOID_TAGS.has(tag.tagName) && !tag.selfClosing) stack.push(element)
  }
}

function serialize(node) {
  if (node.nodeType === 3) return escapeText(node._data)
  if (node.tagName === '#fragment') return node.childNodes.map(serialize).join('')
  const attrs = node.attributes
    .map(attribute => ` ${attribute.name}="${escapeAttr(attribute.value)}"`).join('')
  const open = `<${node.tagName.toLowerCase()}${attrs}>`
  if (VOID_TAGS.has(node.tagName)) return open
  return `${open}${node.childNodes.map(serialize).join('')}</${node.tagName.toLowerCase()}>`
}

globalThis.document = {
  createElement(tagName) {
    if (String(tagName).toLowerCase() !== 'template') {
      return new ElementNode(String(tagName).toUpperCase())
    }
    const content = new ElementNode('#fragment')
    return {
      content,
      set innerHTML(html) { parseInto(content, String(html)) },
      get innerHTML() { return serialize(content) }
    }
  },
  createTextNode(text) { return new TextNode(text) }
}

// ---- XSS 白名单：危险标签/协议/事件属性必须被过滤 ----

test('a raw <script> block is unwrapped and its text kept as inert text', () => {
  const html = renderMarkdown('<script>alert(1)</script>')
  assert.doesNotMatch(html, /<script/i)
  assert.doesNotMatch(html, /<\/script>/i)
  assert.match(html, /alert\(1\)/)
})

test('javascript: link destinations lose their href', () => {
  const html = renderMarkdown('<a href="javascript:alert(1)">click</a>')
  assert.doesNotMatch(html, /javascript:/i)
  assert.doesNotMatch(html, /href\s*=/i)
  assert.match(html, /click/)
})

test('img onerror and event-handler attributes on disallowed tags are stripped', () => {
  const html = renderMarkdown('<img src="x" onerror="alert(1)">\n\n<div onclick="alert(2)">hi</div>')
  assert.doesNotMatch(html, /onerror/i)
  assert.doesNotMatch(html, /onclick/i)
  assert.doesNotMatch(html, /<img/i)
  assert.doesNotMatch(html, /<div/i)
  assert.match(html, /hi/)
})

test('disallowed attributes on allowed tags are removed while title is kept', () => {
  const html = renderMarkdown('<p style="color:red" onclick="alert(1)">safe</p>\n\n[x](https://example.com "a title")')
  assert.doesNotMatch(html, /onclick/i)
  assert.doesNotMatch(html, /style=/i)
  assert.match(html, /<p>safe<\/p>/)
  assert.match(html, /title="a title"/)
})

// ---- 正常 Markdown 与 [mm:ss] 时间戳链接仍要正常渲染 ----

test('normal markdown, safe external links and [mm:ss] timestamp links still render', () => {
  const html = renderMarkdown('# Title\n\n**bold** and [link](https://example.com) and [01:02]')
  assert.match(html, /<h1>Title<\/h1>/)
  assert.match(html, /<strong>bold<\/strong>/)
  assert.match(html, /href="https:\/\/example.com"/)
  assert.match(html, /target="_blank"/)
  assert.match(html, /rel="noopener noreferrer"/)
  assert.match(html, /href="#video-t=62"/)
  assert.doesNotMatch(html, /<script/i)
})

test('empty input never touches the DOM and returns an empty string', () => {
  assert.equal(renderMarkdown(''), '')
  assert.equal(renderMarkdown(null), '')
})
