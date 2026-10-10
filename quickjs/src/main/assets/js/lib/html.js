import cheerio from 'assets://js/lib/cheerio.min.js'

function select(html, expression) {
    const $ = cheerio.load(typeof html === 'string' ? html : '')
    const selectors = String(expression || '').split('&&').map(part => part.trim()).filter(Boolean)
    let nodes = null
    for (const selector of selectors) nodes = nodes === null ? $(selector) : nodes.find(selector)
    return { $, nodes: nodes === null ? $.root() : nodes }
}

function parseValue(html, expression) {
    const parts = String(expression || '').split('&&')
    const field = parts.length > 1 ? parts.pop().trim() : 'Text'
    const { $, nodes } = select(html, parts.join('&&'))
    if (field === 'Text') return nodes.toArray().map(node => $(node).text().replace(/\s+/g, ' ').trim()).filter(Boolean).join(' ')
    if (field === 'Html') return nodes.first().html() || ''
    return nodes.first().attr(field) || ''
}

function parseUrl(html, expression, baseUrl) {
    const value = parseValue(html, expression)
    return value && baseUrl ? joinUrl(baseUrl, value) : value
}

if (typeof globalThis.pdfh !== 'function') {
    globalThis.pdfh = (html, expression, baseUrl) => baseUrl && /&&\s*(?:href|src|url)\s*$/i.test(expression)
        ? parseUrl(html, expression, baseUrl) : parseValue(html, expression)
}
if (typeof globalThis.pdfa !== 'function') {
    globalThis.pdfa = (html, expression) => {
        const { $, nodes } = select(html, expression)
        return nodes.toArray().map(node => $.html(node))
    }
}
if (typeof globalThis.pd !== 'function') globalThis.pd = parseUrl
