// Exercise the actual frontend Markdown renderer without a browser or server.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { createRequire } = require('node:module');
const { pathToFileURL } = require('node:url');
const frontend = path.resolve(__dirname, '../frontend/nextjs');
const localRequire = createRequire(path.join(frontend, 'package.json'));

async function main() {
  const modules = {};
  for (const name of ['remark', 'remark-html', 'remark-gfm', 'vfile', 'isomorphic-dompurify']) {
    modules[name] = await import(pathToFileURL(localRequire.resolve(name)).href);
  }
  const ts = localRequire('typescript');
  const source = fs.readFileSync(path.join(frontend, 'helpers/markdownHelper.ts'), 'utf8');
  const sandbox = {
    exports: {}, console,
    require: name => name === './getHost'
      ? { getHost: () => 'http://localhost:8000' }
      : modules[name],
  };
  vm.runInNewContext(ts.transpileModule(source, {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 },
  }).outputText, sandbox);
  const { escapeCitationTitlePipes: escape, markdownToHtml: render } = sandbox.exports;
  const url = 'https://example.org/article?q=one&x=%20#part';
  const citation = `[网页 21：Title | Publisher](${url})`;
  const fixed = `[网页 21：Title \\| Publisher](${url})`;
  assert.equal(escape(citation), fixed);
  assert.equal(escape(fixed), fixed);
  assert.equal(escape(escape(citation)), fixed);
  assert.equal(escape('[ordinary | link](https://example.org)'), '[ordinary | link](https://example.org)');
  assert.equal(escape('![网页 21：Title | Publisher](https://example.org/image)'), '![网页 21：Title | Publisher](https://example.org/image)');
  assert.equal(escape('| Topic | Evidence |'), '| Topic | Evidence |');

  const table = `| Topic | Evidence |\n| --- | --- |\n| A | ${citation}${citation} |\n| B | ${fixed} |`;
  const html = await render(table);
  assert.equal((html.match(/<td>/g) || []).length, 4);
  assert.equal((html.match(/<a /g) || []).length, 3);
  assert.equal((html.match(/Title \| Publisher/g) || []).length, 3);
  assert.ok(html.includes('href="https://example.org/article?q=one&amp;x=%20#part"'));
  assert.ok(html.includes('target="_blank"'));
  assert.ok((await render(`Paragraph ${citation}`)).includes('<a '));
  assert.ok((await render(new modules.vfile.VFile(citation))).includes('<a '));

  const internal = '/api/research-runs/11111111-1111-1111-1111-111111111111/evidence/1/view';
  const document = `[资料 1：File | Notes，第 2 页](${internal})`;
  const documentHtml = await render(`| File | Evidence |\n| --- | --- |\n| A | ${document} |`);
  assert.ok(documentHtml.includes(`href="http://localhost:8000${internal}"`));
  assert.ok(documentHtml.includes('File | Notes，第 2 页'));
  console.log('Citation Markdown regression checks passed');
}

main().catch(error => { console.error(error); process.exitCode = 1; });
